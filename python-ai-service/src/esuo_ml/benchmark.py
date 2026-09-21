"""Сквозной замер: что прогноз даёт отделению, а не одному предсказанию.

Точность прогноза (MAE) сама по себе ничего не говорит о работе отделения. Прогноз попадает в решения
через `score = 1 / прогноз` (дизайн-док 6.5), и важно не то, насколько он точен, а то, какие решения
из него следуют и сколько в сумме ждут клиенты. Поэтому здесь проигрывается очередь целиком.

Логика выбора повторяет `QueueSelection.choose` из ноды один в один:

1. есть просроченные головы (`due_at <= now`) — берётся самая ранняя по `(due_at, seq)`, score не участвует;
2. иначе, если в окно `slack` попадает больше одной головы — берётся с максимальным score;
3. иначе — обычный EDF.

Окно берёт следующего клиента, когда освобождается, поэтому медленный сотрудник сам по себе получает
меньше клиентов: это «вытягивающая» схема, её не надо балансировать отдельно. Важное следствие:
**постоянный множитель сотрудника не меняет ни одного решения** — score всех кандидатов этого окна
умножается на одно и то же число, argmax не сдвигается. На выбор влияет только взаимодействие
«сотрудник × услуга» и различия между услугами и категориями.

Это и проверяется: три руки на одной трассе, одинаковые приходы, одинаковый шум, разный источник прогноза.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta

from .baselines import CascadeBaseline, DEFAULT_SERVICE_DURATION_SEC
from .generate import Observation, profile_for, planned_windows_at, simulate_day
from .predict import ModelParams
from .world import World

# Целевые времена ожидания из queue-defaults.yml: due_at = приход + значение (дизайн-док 6.3).
TARGET_WAIT_MIN = {
    "qr": {"default": 20, "pensioner": 15, "svo": 5},
    "walkin": {"default": 30, "pensioner": 20, "svo": 5},
}
SLACK_MIN = 5


@dataclass
class Client:
    seq: int
    arrived_at: datetime
    service_code: str
    client_category: str
    source: str
    noise: float
    due_at: datetime
    started_at: datetime | None = None
    completed_at: datetime | None = None
    window_id: str | None = None
    employee_id: str | None = None

    @property
    def wait_sec(self) -> float:
        return (self.started_at - self.arrived_at).total_seconds() if self.started_at else 0.0

    @property
    def overdue(self) -> bool:
        return bool(self.started_at and self.started_at > self.due_at)


@dataclass
class BranchResult:
    arm: str
    served: int
    unserved: int
    avg_wait_sec: float
    p90_wait_sec: float
    max_wait_sec: float
    overdue: int
    overdue_share: float
    makespan_min: float
    per_employee: dict[str, int] = field(default_factory=dict)
    per_employee_minutes: dict[str, float] = field(default_factory=dict)
    # Сколько раз сработало каждое правило выбора. `score_in_slack` — единственное, на которое
    # вообще влияет прогноз; остальные решения от него не зависят ни при какой модели.
    reasons: dict[str, int] = field(default_factory=dict)
    clients: list = field(default_factory=list)
    shift: dict = field(default_factory=dict)
    active: list = field(default_factory=list)
    staffing: dict = field(default_factory=dict)

    @property
    def score_share(self) -> float:
        total = sum(self.reasons.values())
        return self.reasons.get("score_in_slack", 0) / total if total else 0.0


# ---------------------------------------------------------------------- источники прогноза


def uniform_predictor(world: World):
    """`uniform`: все параметры на значениях по умолчанию, score одинаков — чистый EDF."""
    def predict(service_code: str, category: str, employee_id: str, hour: int) -> float:
        return DEFAULT_SERVICE_DURATION_SEC
    return predict


def cascade_predictor(cascade: CascadeBaseline):
    """Каскад `handling_stats`: то, что уже работает в ветке (дизайн-док 16.2)."""
    def predict(service_code: str, category: str, employee_id: str, hour: int) -> float:
        probe = Observation(datetime.now(), date.today(), hour, service_code, category,
                            employee_id, "", "qr", 0.0, 1.0, datetime.now())
        return cascade.predict(probe)
    return predict


def model_predictor(params: ModelParams, fallback: CascadeBaseline, point: str = "mean"):
    """Модель, с запасной цепочкой на каскад (ml-model.md 5.3).

    `point` — какую точку распределения брать, и это не косметика (ml-model.md 3.2):

    * `mean = exp(m + sigma^2/2)` — для **сумм работы**: ETA, мощность часа, планирование нагрузки.
      Складывать медианы нельзя: у лог-нормального распределения медиана меньше средней
      в `exp(sigma^2/2)` раз (при sigma = 0,35 — на 6%), и на очереди из двадцати человек
      обещание уезжает на эти 6% систематически, в одну сторону.
    * `p50 = exp(m)` — для **выбора одного талона**: медиана минимизирует абсолютную ошибку
      и не зависит от sigma, а для score важен только порядок кандидатов.
    """
    cascade = cascade_predictor(fallback)

    def predict(service_code: str, category: str, employee_id: str, hour: int) -> float:
        prediction = params.predict(service_code, category, employee_id, hour)
        if prediction is None:
            return cascade(service_code, category, employee_id, hour)
        return prediction.mean_sec if point == "mean" else prediction.p50_sec
    return predict


# ---------------------------------------------------------------------- прогон


def run_branch(world: World, day: date, seed: int, predict, flow_bias: float = 1.0,
               arm: str = "", windows: int | None = None, slack_min: float = SLACK_MIN,
               mode: str = "slack", staffing: dict[int, int] | None = None) -> BranchResult:
    """Проигрывает один день отделения на трассе и возвращает метрики зала."""
    observations = simulate_day(world, day, seed, flow_bias)
    if not observations:
        return BranchResult(arm, 0, 0, 0, 0, 0, 0, 0, 0)

    zone = world.timezone
    code, profile = profile_for(world, day)
    clients = [Client(seq=i, arrived_at=o.arrived_at, service_code=o.service_code,
                      client_category=o.client_category, source=o.source, noise=o.noise,
                      due_at=o.arrived_at + timedelta(
                          minutes=TARGET_WAIT_MIN[o.source].get(o.client_category, 20)))
               for i, o in enumerate(sorted(observations, key=lambda x: x.arrived_at))]

    open_count = windows or max(1, planned_windows_at(profile, _hour_of(profile.get("opening_time", "09:00"))))
    open_count = min(open_count, len(world.windows))
    shift = _shift(world, day, seed)
    all_windows = [w.id for w in world.windows]
    active = all_windows[:open_count]
    free_at = {wid: clients[0].arrived_at for wid in all_windows}

    def open_windows(at: datetime) -> list[str]:
        """Сколько окон открыто в этот час: по плану штатного расписания или фиксировано."""
        if staffing is None:
            return active
        count = min(len(all_windows), max(1, staffing.get(_local_hour(at, zone), open_count)))
        return all_windows[:count]

    pending = list(clients)
    slack = timedelta(minutes=slack_min)
    reasons: dict[str, int] = {}

    while pending:
        candidates = open_windows(min(free_at[w] for w in all_windows))
        window_id = min(candidates, key=lambda wid: (free_at[wid], wid))
        now = free_at[window_id]
        # Окно могло открыться только что: оно свободно с момента открытия, а не с утра.
        now = max(now, min(c.arrived_at for c in pending)) if now < min(c.arrived_at for c in pending) else now
        employee_id = shift[window_id]

        eligible = [c for c in pending if c.arrived_at <= now]
        if not eligible:
            free_at[window_id] = min(c.arrived_at for c in pending)
            continue
        if window_id not in open_windows(now):
            free_at[window_id] = now + timedelta(minutes=5)      # окно ещё закрыто, ждём следующего часа
            continue

        heads = _heads(eligible)
        chosen, reason = _choose(heads, now, slack, mode,
                                 lambda c: 1.0 / max(1.0, predict(c.service_code, c.client_category,
                                                                  employee_id, _local_hour(now, zone))))
        reasons[reason] = reasons.get(reason, 0) + 1
        actual = max(20.0, world.median_duration_sec(chosen.service_code, chosen.client_category,
                                                     employee_id, _local_hour(now, zone), window_id)
                     * chosen.noise)
        chosen.started_at = now
        chosen.completed_at = now + timedelta(seconds=actual)
        chosen.window_id = window_id
        chosen.employee_id = employee_id
        free_at[window_id] = chosen.completed_at
        pending.remove(chosen)

    result = _metrics(arm, clients, shift, reasons)
    result.clients = clients
    result.shift = shift
    result.active = active
    result.staffing = staffing or {}
    return result


def forecast_work_min(world: World, day: date, predict, flow_bias: float = 1.0,
                      shift: dict[str, str] | None = None) -> dict[int, float]:
    """Работа каждого часа в минутах окна (load-planning.md 4.1).

        поток(h) = base_arrivals_per_day × flow_multiplier × нормированная кривая(h)
        работа(h) = поток(h) × Σ доли услуг × доли категорий × E[длительность | услуга, категория, час]

    `E[длительность]` — средняя по сотрудникам смены, поэтому берётся `mean`, а не медиана:
    складываются именно ожидаемые длительности (ml-model.md 3.2).

    Здесь величина прогноза важна напрямую, в отличие от выбора талона, где важен только порядок.
    Ошибка в E[длительность] вдвое — это ошибка в числе нужных окон вдвое.
    """
    _, profile = profile_for(world, day)
    if profile.get("closed"):
        return {}
    curve = {int(h): float(w) for h, w in profile.get("hourly_curve", {}).items()}
    total_weight = sum(curve.values()) or 1.0
    day_total = (float(world.planning.get("base_arrivals_per_day", 100))
                 * float(profile.get("flow_multiplier", 1.0)) * flow_bias)
    service_mix = profile.get("service_mix", {})
    category_mix = profile.get("category_mix", {})
    employees = list(shift.values()) if shift else [e.id for e in world.employees]

    work: dict[int, float] = {}
    for hour, weight in sorted(curve.items()):
        arrivals = day_total * weight / total_weight
        expected_sec = 0.0
        service_total = sum(service_mix.values()) or 1.0
        category_total = sum(category_mix.values()) or 1.0
        for service_code, service_share in service_mix.items():
            for category, category_share in category_mix.items():
                per_employee = sum(predict(service_code, category, e, hour) for e in employees) / len(employees)
                expected_sec += (service_share / service_total) * (category_share / category_total) * per_employee
        work[hour] = arrivals * expected_sec / 60
    return work


def needed_windows(work_min: dict[int, float], utilization_target: float) -> dict[int, int]:
    """`needed(h) = ⌈работа(h) / (60 × utilization_target)⌉` (load-planning.md 4.2)."""
    return {hour: max(1, math.ceil(minutes / (60 * utilization_target)))
            for hour, minutes in work_min.items()}


def eta_error_min(world: World, clients: list[Client], predict, shift: dict[str, str],
                  active: list[str]) -> tuple[float, float]:
    """Ошибка обещания: на сколько минут расходятся обещанный клиенту вызов и фактический.

    Повторяет то, что делает `WaitTimeEstimator` на ноде: в момент прихода клиента берётся текущее
    состояние окон и очередь ожидающих, очередь прогоняется вперёд с ПРОГНОЗНЫМИ длительностями,
    и момент, когда симуляция назначает клиента окну, — это обещанный вызов. Сравнивается с тем,
    когда его вызвали на самом деле.

    Здесь важна не только очерёдность, но и величина прогноза: обещание «вас вызовут в 10:42»
    считается суммой длительностей всех, кто впереди. Ошибка в каждой из них накапливается.
    """
    served = [c for c in clients if c.started_at]
    errors: list[float] = []
    for client in served:
        moment = client.arrived_at
        free_at = {}
        for window_id in active:
            done = [c.completed_at for c in served
                    if c.window_id == window_id and c.started_at <= moment]
            free_at[window_id] = max(done) if done and max(done) > moment else moment
        pending = sorted([c for c in served if c.arrived_at <= moment and c.started_at > moment],
                         key=lambda c: (c.due_at, c.seq))
        promised = _simulate_forward(world, client, pending, free_at, shift, predict, moment)
        if promised is not None:
            errors.append(abs((promised - client.started_at).total_seconds()) / 60)
    if not errors:
        return 0.0, 0.0
    errors.sort()
    return sum(errors) / len(errors), errors[int(len(errors) * 0.9)]


def _simulate_forward(world: World, target: Client, pending: list[Client],
                      free_at: dict[str, datetime], shift: dict[str, str], predict,
                      now: datetime) -> datetime | None:
    """Упрощённая симуляция вперёд на прогнозных длительностях — как `QueueSimulation` на ноде."""
    free = dict(free_at)
    remaining = list(pending)
    slack = timedelta(minutes=SLACK_MIN)
    guard = 0
    while remaining and guard < 5000:
        guard += 1
        window_id = min(free, key=lambda wid: (free[wid], wid))
        at = max(free[window_id], now)
        employee_id = shift[window_id]
        eligible = [c for c in remaining if c.arrived_at <= at]
        if not eligible:
            free[window_id] = min(c.arrived_at for c in remaining)
            continue
        heads = _heads(eligible)
        chosen, _ = _choose(heads, at, slack, "slack",
                            lambda c: 1.0 / max(1.0, predict(c.service_code, c.client_category,
                                                             employee_id, at.hour)))
        if chosen is target:
            return at
        free[window_id] = at + timedelta(
            seconds=max(1.0, predict(chosen.service_code, chosen.client_category, employee_id, at.hour)))
        remaining.remove(chosen)
    return None


def _heads(eligible: list[Client]) -> list[Client]:
    """Голова каждой очереди услуги: самый ранний дедлайн, при равенстве — порядок выдачи."""
    heads: dict[str, Client] = {}
    for client in eligible:
        current = heads.get(client.service_code)
        if current is None or (client.due_at, client.seq) < (current.due_at, current.seq):
            heads[client.service_code] = client
    return list(heads.values())


def _choose(heads: list[Client], now: datetime, slack: timedelta, mode: str,
            score) -> tuple[Client, str]:
    """Ровно `QueueSelection.choose`: просрочка → EDF без score; несколько в slack → score; иначе EDF."""
    if mode == "sjf":
        # Потолок: чистый «самая короткая работа вперёд», дедлайны не ограничивают ничего.
        return min(heads, key=lambda c: (-score(c), c.due_at, c.seq)), "sjf"
    overdue = [c for c in heads if c.due_at <= now]
    if overdue:
        if mode == "score_in_overdue" and len(overdue) > 1:
            # Просроченные по-прежнему идут раньше непросроченных — гарантия не нарушена.
            # Меняется только порядок ВНУТРИ просроченных: из них берётся самый быстрый.
            return min(overdue, key=lambda c: (-score(c), c.due_at, c.seq)), "score_in_overdue"
        return min(overdue, key=lambda c: (c.due_at, c.seq)), "overdue_edf"
    in_slack = [c for c in heads if c.due_at <= now + slack]
    if len(in_slack) > 1:
        return min(in_slack, key=lambda c: (-score(c), c.due_at, c.seq)), "score_in_slack"
    return min(heads, key=lambda c: (c.due_at, c.seq)), "edf"


def _metrics(arm: str, clients: list[Client], shift: dict[str, str],
             reasons: dict[str, int]) -> BranchResult:
    served = [c for c in clients if c.started_at]
    waits = sorted(c.wait_sec for c in served)
    overdue = sum(1 for c in served if c.overdue)
    start = min(c.arrived_at for c in clients)
    end = max(c.completed_at for c in served)
    per_employee: dict[str, int] = {}
    per_minutes: dict[str, float] = {}
    for c in served:
        per_employee[c.employee_id] = per_employee.get(c.employee_id, 0) + 1
        per_minutes[c.employee_id] = per_minutes.get(c.employee_id, 0.0) + \
            (c.completed_at - c.started_at).total_seconds() / 60
    return BranchResult(
        arm=arm,
        served=len(served),
        unserved=len(clients) - len(served),
        avg_wait_sec=sum(waits) / len(waits) if waits else 0.0,
        p90_wait_sec=waits[int(len(waits) * 0.9)] if waits else 0.0,
        max_wait_sec=waits[-1] if waits else 0.0,
        overdue=overdue,
        overdue_share=overdue / len(served) if served else 0.0,
        makespan_min=(end - start).total_seconds() / 60,
        per_employee=per_employee,
        per_employee_minutes=per_minutes,
        reasons=dict(sorted(reasons.items())),
    )


# ---------------------------------------------------------------------- мелочи


def _shift(world: World, day: date, seed: int) -> dict[str, str]:
    from .generate import assign_shifts
    import random
    return assign_shifts(world, day, random.Random(f"{seed}:{day.isoformat()}"))


def _hour_of(hhmm: str) -> int:
    return int(hhmm.split(":")[0])


def _local_hour(moment: datetime, timezone_name: str) -> int:
    from zoneinfo import ZoneInfo
    return moment.astimezone(ZoneInfo(timezone_name)).hour
