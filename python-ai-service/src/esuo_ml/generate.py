"""Генератор наблюдений: проигрывает дни мира и записывает, кто кого сколько обслуживал.

Для обучения важны только шесть полей наблюдения: услуга, категория, сотрудник, окно, час начала
обслуживания и фактическая длительность. Порядок очереди на обучение не влияет, поэтому здесь
не EDF, а простая раздача клиентов свободным окнам — достаточная, чтобы у каждого сотрудника
набрались наблюдения по всем услугам и часам (иначе поправки нечем оценивать).

Смены (какой сотрудник в каком окне) перемешиваются каждый день. Это нужно, чтобы эффект сотрудника
и эффект окна не слились в один: иначе модель не смогла бы показать, что окно ни на что не влияет.
"""

from __future__ import annotations

import math
import random
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta
from zoneinfo import ZoneInfo

from .world import World

# Доля живой очереди против QR: на длительность обслуживания источник не влияет,
# но история должна выглядеть как настоящая.
WALKIN_SHARE = 0.38


@dataclass(frozen=True)
class Observation:
    """Одно завершённое обслуживание — ровно то, что нода умеет выгрузить из `tickets`.

    `noise` — вытянутый множитель случайности этого обслуживания, `exp(N(0, sigma^2))`. Он хранится
    отдельно от длительности сознательно: длительность зависит от того, кто обслуживал, поэтому одну
    и ту же трассу нельзя проиграть с другим распределением сотрудников по окнам, если хранить только
    готовое число. Для сквозного замера и для демо-проигрывателя нужен именно шум: длительность
    восстанавливается как `медиана(услуга, категория, сотрудник, час) × noise`.
    """

    started_at: datetime          # UTC, начало обслуживания
    day: date                     # дата в часовом поясе отделения
    hour: int                     # час начала обслуживания в часовом поясе отделения
    service_code: str
    client_category: str
    employee_id: str
    window_id: str
    source: str                   # qr | walkin
    duration_sec: float
    noise: float
    arrived_at: datetime          # UTC, когда клиент пришёл (для истории и трасс)

    CSV_HEADER = ("started_at", "day", "hour", "service_code", "client_category",
                  "employee_id", "window_id", "source", "duration_sec", "noise")

    def csv_row(self) -> list[str]:
        return [self.started_at.isoformat(), self.day.isoformat(), str(self.hour), self.service_code,
                self.client_category, self.employee_id, self.window_id, self.source,
                f"{self.duration_sec:.1f}", f"{self.noise:.4f}"]

    @classmethod
    def from_csv_row(cls, row: dict[str, str]) -> Observation:
        started = datetime.fromisoformat(row["started_at"])
        return cls(
            started_at=started,
            day=date.fromisoformat(row["day"]),
            hour=int(row["hour"]),
            service_code=row["service_code"],
            client_category=row["client_category"],
            employee_id=row["employee_id"],
            window_id=row["window_id"],
            source=row.get("source", "qr"),
            duration_sec=float(row["duration_sec"]),
            noise=float(row.get("noise", 1.0)),
            arrived_at=started,
        )


# ---------------------------------------------------------------------- профиль дня


WEEKDAY_KEYS = ("mon", "tue", "wed", "thu", "fri", "sat", "sun")


def profile_for(world: World, day: date) -> tuple[str, dict]:
    """Профиль на дату: сначала пенсионный день, затем календарь, затем день недели (load-planning.md 3.1)."""
    planning = world.planning
    profiles = planning["profiles"]
    if day.day in world.pension_days and "pension" in profiles:
        return "pension", profiles["pension"]
    for row in planning.get("calendar", ()):
        if row.get("date") == day.isoformat():
            code = row["profile"]
            return code, profiles[code]
    code = planning.get("weekdays", {}).get(WEEKDAY_KEYS[day.weekday()], "normal")
    return code, profiles[code]


def arrivals_by_hour(profile: dict, base_per_day: float, flow_bias: float, rnd: random.Random) -> dict[int, int]:
    """Клиентов по часам: base × flow_multiplier × нормированная кривая, с дневным разбросом ±20%."""
    curve = {int(h): float(w) for h, w in profile.get("hourly_curve", {}).items()}
    total_weight = sum(curve.values())
    if total_weight <= 0:
        return {}
    day_total = base_per_day * float(profile.get("flow_multiplier", 1.0)) * flow_bias
    day_total *= 0.9 + 0.2 * rnd.random()
    out: dict[int, int] = {}
    for hour, weight in sorted(curve.items()):
        expected = day_total * weight / total_weight
        # Целое число клиентов: дробная часть решается броском, иначе редкие часы всегда пустые.
        count = int(expected) + (1 if rnd.random() < expected - int(expected) else 0)
        if count:
            out[hour] = count
    return out


def pick_weighted(mix: dict[str, float], rnd: random.Random) -> str:
    total = sum(mix.values())
    x = rnd.random() * total
    for key, weight in sorted(mix.items()):
        x -= weight
        if x <= 0:
            return key
    return sorted(mix)[-1]


def planned_windows_at(profile: dict, hour: int) -> int:
    """Ступенчатая функция: значение действует с указанного часа до следующего ключа."""
    steps = sorted((int(h), int(n)) for h, n in profile.get("planned_windows", {}).items())
    current = 0
    for start, count in steps:
        if start <= hour:
            current = count
    return current


def assign_shifts(world: World, day: date, rnd: random.Random) -> dict[str, str]:
    """Кто в каком окне сегодня. Перемешивается каждый день, чтобы развязать сотрудника и окно."""
    employees = [e.id for e in world.employees]
    rnd.shuffle(employees)
    return {w.id: employees[i % len(employees)] for i, w in enumerate(world.windows)}


def day_shifts(world: World, day: date, seed: int) -> dict[str, str]:
    """Смена этого дня без прогона симуляции — для трассы сценария.

    Повторяет первый шаг `simulate_day`: тот же ключ зерна и `assign_shifts` как первый потребитель
    свежего `Random`, поэтому отображение окно → сотрудник совпадает с тем, что получит симуляция.
    Связь неявная, и её стережёт тест `test_trace_shift_matches_simulation`: он сверяет смену трассы
    с парами (окно, сотрудник) из самих наблюдений, так что расхождение всплывёт сразу.
    """
    return assign_shifts(world, day, random.Random(f"{seed}:{day.isoformat()}"))


def open_window_ids(world: World, profile: dict, hour: int) -> list[str]:
    """Окна, открытые по плану профиля в этот час. Одна точка правды для симуляции и для трассы."""
    ids = [w.id for w in world.windows]
    count = max(1, min(len(ids), planned_windows_at(profile, hour)))
    return ids[:count]


# ---------------------------------------------------------------------- день


def simulate_day(world: World, day: date, seed: int, flow_bias: float = 1.0) -> list[Observation]:
    """Один день мира: клиенты приходят по профилю, свободные окна их обслуживают."""
    rnd = random.Random(f"{seed}:{day.isoformat()}")
    zone = ZoneInfo(world.timezone)
    code, profile = profile_for(world, day)
    if profile.get("closed"):
        return []

    shifts = assign_shifts(world, day, rnd)
    base = float(world.planning.get("base_arrivals_per_day", 100))
    arrivals = arrivals_by_hour(profile, base, flow_bias, rnd)
    if not arrivals:
        return []

    opening = _instant(day, profile.get("opening_time", "09:00"), zone)
    closing = _instant(day, profile.get("closing_time", "18:00"), zone)
    service_mix = profile.get("service_mix", {})
    category_mix = profile.get("category_mix", {})

    # Клиенты: момент прихода внутри своего часа.
    clients = []
    for hour, count in sorted(arrivals.items()):
        hour_start = _instant(day, f"{hour:02d}:00", zone)
        for _ in range(count):
            clients.append((
                hour_start + timedelta(seconds=rnd.random() * 3600),
                pick_weighted(service_mix, rnd),
                pick_weighted(category_mix, rnd),
                "walkin" if rnd.random() < WALKIN_SHARE else "qr",
            ))
    clients.sort(key=lambda c: c[0])

    # Окна: открыты по плану профиля, каждое обслуживает по очереди.
    window_ids = [w.id for w in world.windows]
    free_at = {wid: opening for wid in window_ids}
    observations: list[Observation] = []

    for arrived, service_code, category, source in clients:
        local_hour = arrived.astimezone(zone).hour
        candidates = open_window_ids(world, profile, local_hour)
        window_id = min(candidates, key=lambda wid: (free_at[wid], wid))
        started = max(free_at[window_id], arrived)
        if started >= closing + timedelta(hours=1):
            continue                                   # отделение уже закрыто, клиента не приняли
        employee_id = shifts[window_id]
        hour = started.astimezone(zone).hour
        noise = math.exp(rnd.gauss(0.0, world.sigma))
        median = world.median_duration_sec(service_code, category, employee_id, hour, window_id)
        duration = max(20.0, median * noise)
        free_at[window_id] = started + timedelta(seconds=duration)
        observations.append(Observation(
            started_at=started, day=day, hour=hour, service_code=service_code,
            client_category=category, employee_id=employee_id, window_id=window_id,
            source=source, duration_sec=duration, noise=noise, arrived_at=arrived,
        ))
    return observations


def simulate_period(world: World, end_day: date, days: int, seed: int,
                    flow_bias: float = 1.0) -> list[Observation]:
    """`days` дней, заканчивая днём `end_day` включительно."""
    out: list[Observation] = []
    for offset in range(days - 1, -1, -1):
        out.extend(simulate_day(world, end_day - timedelta(days=offset), seed, flow_bias))
    out.sort(key=lambda o: o.started_at)
    return out


def _instant(day: date, hhmm: str, zone: ZoneInfo) -> datetime:
    hour, minute = (int(part) for part in hhmm.split(":"))
    return datetime.combine(day, time(hour, minute), tzinfo=zone)
