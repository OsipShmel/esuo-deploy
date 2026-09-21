"""Обучение: аддитивные поправки к логарифму длительности со сжатием (ml-model.md, 3.1).

Алгоритм целиком:

    y = log(длительность)
    повторить 5 раз:
        mu          = средневзвешенное (y − остальные слагаемые)
        для каждой группы каждого слагаемого:
            остаток = средневзвешенное (y − все слагаемые, кроме этого)
            поправка = n / (n + k) × остаток

Это backfitting плюс эмпирический Байес в простейшей форме. Сжатие `n / (n + k)` — единственная защита
от переобучения: у сотрудника с одним обслуживанием поправка берётся на 9%, со ста — на 91%. Никакой
оптимизации, никаких библиотек: только взвешенные средние по группам, поэтому результат можно пересчитать
руками и объяснить руководителю отделения.

Что здесь важно и неочевидно:

* Вес наблюдения убывает с полупериодом 14 дней — вчерашний день важнее, чем месяц назад.
* Порядок слагаемых в проходе фиксирован: сначала широкие группы (услуга), затем узкие (сотрудник+услуга).
  Так узкие поправки подбирают то, что осталось после широких, а не наоборот.
* Контроль (последние дни) в обучении не участвует вообще: ни в подборе поправок, ни в оценке sigma.
* MAE модели считается по p50: медиана — это точечный прогноз, минимизирующий абсолютную ошибку.
  Метрика `mae_mean_sec` по средней приведена рядом, чтобы ничего не выглядело подобранным.
"""

from __future__ import annotations

import math
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import date, datetime, timezone

from .baselines import (CascadeBaseline, ConstantBaseline, DEFAULT_SERVICE_DURATION_SEC,
                        ServiceMeanBaseline, mae)
from .generate import Observation
from .predict import ModelParams, SIGMA_FLOOR

# Слагаемые модели: имя секции → как из наблюдения получается ключ группы.
TERMS: dict[str, callable] = {
    "a": lambda o: o.service_code,
    "b": lambda o: f"{o.service_code}|{o.client_category}",
    "e": lambda o: o.employee_id,
    "g": lambda o: f"{o.employee_id}|{o.service_code}",
    "h": lambda o: str(o.hour),
    "w": lambda o: o.window_id,
}

# Порядок обхода: от широких групп к узким.
TERM_ORDER = ("a", "b", "e", "g", "h", "w")


@dataclass
class FitOptions:
    train_days: int = 28
    holdout_days: int = 3
    half_life_days: float = 14.0
    shrinkage_k: float = 10.0
    shrinkage_k_by_term: dict[str, float] = field(default_factory=lambda: {"g": 20.0})
    passes: int = 5
    min_duration_sec: float = 10.0
    max_duration_sec: float = 3600.0
    min_observations: int = 200
    use_window: bool = False
    accept_mae_ratio: float = 1.05

    def k(self, term: str) -> float:
        return self.shrinkage_k_by_term.get(term, self.shrinkage_k)


@dataclass
class FitResult:
    params: ModelParams
    status: str                      # active | rejected | insufficient_data
    reason: str
    train: list[Observation]
    holdout: list[Observation]

    @property
    def accepted(self) -> bool:
        return self.status == "active"


# ---------------------------------------------------------------------- подготовка


def split(observations: list[Observation], options: FitOptions) -> tuple[list[Observation], list[Observation]]:
    """Окно обучения и контроль: последние `holdout_days` дней в обучении не участвуют."""
    clean = [o for o in observations
             if options.min_duration_sec <= o.duration_sec <= options.max_duration_sec]
    if not clean:
        return [], []
    days = sorted({o.day for o in clean})
    window = set(days[-options.train_days:])
    holdout_days = set(days[-options.holdout_days:]) if options.holdout_days else set()
    train = [o for o in clean if o.day in window and o.day not in holdout_days]
    holdout = [o for o in clean if o.day in holdout_days]
    return train, holdout


def weight_of(observation: Observation, newest: date, half_life_days: float) -> float:
    age = (newest - observation.day).days
    return 0.5 ** (age / half_life_days) if half_life_days > 0 else 1.0


# ---------------------------------------------------------------------- обучение


def fit(observations: list[Observation], options: FitOptions | None = None,
        baseline: ModelParams | None = None, version: str | None = None) -> FitResult:
    options = options or FitOptions()
    dropped = len(observations)
    train, holdout = split(observations, options)
    dropped -= len(train) + len(holdout)

    params = ModelParams(version=version or _version(), use_window=options.use_window)
    if len(train) < options.min_observations:
        params.status = "insufficient_data"
        params.metrics = {"observations": len(train), "min_observations": options.min_observations}
        return FitResult(params, "insufficient_data",
                         f"наблюдений {len(train)}, нужно не меньше {options.min_observations}",
                         train, holdout)

    newest = max(o.day for o in train)
    weights = [weight_of(o, newest, options.half_life_days) for o in train]
    logs = [math.log(o.duration_sec) for o in train]

    terms = [t for t in TERM_ORDER if t != "w" or options.use_window]
    groups = {term: [TERMS[term](o) for o in train] for term in terms}
    counts: dict[str, dict[str, int]] = {
        term: dict(_count(groups[term])) for term in terms
    }
    coefficients: dict[str, dict[str, float]] = {term: defaultdict(float) for term in terms}
    mu = _weighted_mean(logs, weights)

    for _ in range(options.passes):
        mu = _weighted_mean(
            [logs[i] - _sum_terms(coefficients, groups, terms, i) for i in range(len(train))], weights)
        for term in terms:
            others = [t for t in terms if t != term]
            residual_total: dict[str, float] = defaultdict(float)
            residual_weight: dict[str, float] = defaultdict(float)
            for i in range(len(train)):
                key = groups[term][i]
                residual = logs[i] - mu - _sum_terms(coefficients, groups, others, i)
                residual_total[key] += residual * weights[i]
                residual_weight[key] += weights[i]
            updated: dict[str, float] = {}
            for key, total in residual_total.items():
                n = counts[term][key]
                shrink = n / (n + options.k(term))
                updated[key] = shrink * (total / residual_weight[key]) if residual_weight[key] else 0.0
            coefficients[term] = defaultdict(float, updated)

    params.mu = mu
    for term in terms:
        setattr(params, term, {k: v for k, v in coefficients[term].items()})
    _anchor_default_category(params, world_services=sorted({o.service_code for o in train}))
    params.counts = counts
    params.sigma, params.sigma_default = _sigma_by_service(train, logs, weights, mu, coefficients, groups, terms)

    shrunk_over_half = sum(1 for term in terms for key, n in counts[term].items()
                           if n / (n + options.k(term)) < 0.5)
    params.metrics = _metrics(params, train, holdout, dropped, shrunk_over_half)
    params.trained_at = datetime.now(timezone.utc).replace(microsecond=0).isoformat()
    params.train_from = min(o.day for o in train).isoformat()
    params.train_to = max(o.day for o in (holdout or train)).isoformat()

    return _accept(params, baseline, options, train, holdout)


def _accept(params: ModelParams, baseline: ModelParams | None, options: FitOptions,
            train: list[Observation], holdout: list[Observation]) -> FitResult:
    """Приёмка версии (ml-model.md, 4.3): новая не хуже прежней больше чем на `accept_mae_ratio`."""
    previous = (baseline.metrics or {}).get("mae_sec") if baseline else None
    if previous:
        params.metrics["previous_mae_sec"] = round(float(previous), 2)
        limit = float(previous) * options.accept_mae_ratio
        if params.metrics["mae_sec"] > limit:
            params.status = "rejected"
            params.enabled = False
            return FitResult(params, "rejected",
                             f"MAE {params.metrics['mae_sec']:.1f} с хуже допустимого {limit:.1f} с "
                             f"(прежняя версия {float(previous):.1f} с)", train, holdout)
    params.status = "active"
    return FitResult(params, "active", "принята", train, holdout)


# ---------------------------------------------------------------------- метрики


def _metrics(params: ModelParams, train: list[Observation], holdout: list[Observation],
             dropped: int, shrunk_over_half: int) -> dict:
    evaluation = holdout or train
    const = ConstantBaseline()
    service_mean = ServiceMeanBaseline(train)
    cascade = CascadeBaseline(train)

    def p50(o: Observation) -> float:
        prediction = params.predict(o.service_code, o.client_category, o.employee_id, o.hour, o.window_id)
        return prediction.p50_sec if prediction else DEFAULT_SERVICE_DURATION_SEC

    def mean(o: Observation) -> float:
        prediction = params.predict(o.service_code, o.client_category, o.employee_id, o.hour, o.window_id)
        return prediction.mean_sec if prediction else DEFAULT_SERVICE_DURATION_SEC

    covered = 0
    for o in evaluation:
        prediction = params.predict(o.service_code, o.client_category, o.employee_id, o.hour, o.window_id)
        if prediction and o.duration_sec <= prediction.p90_sec:
            covered += 1

    model_mae = mae(evaluation, p50)
    service_mae = mae(evaluation, service_mean.predict)
    return {
        "point": "p50",
        "mae_sec": round(model_mae, 2),
        "mae_mean_sec": round(mae(evaluation, mean), 2),
        "baseline_const_mae_sec": round(mae(evaluation, const.predict), 2),
        "baseline_service_mae_sec": round(service_mae, 2),
        "baseline_cascade_mae_sec": round(mae(evaluation, cascade.predict), 2),
        "gain_over_service_mean": round(1 - model_mae / service_mae, 4) if service_mae else 0.0,
        "p90_coverage": round(covered / len(evaluation), 4) if evaluation else 0.0,
        "observations": len(train),
        "holdout_observations": len(holdout),
        "dropped_outliers": dropped,
        "groups_shrunk_over_half": shrunk_over_half,
    }


# ---------------------------------------------------------------------- мелочи


REFERENCE_CATEGORY = "default"


def _anchor_default_category(params: ModelParams, world_services: list[str]) -> None:
    """Делает категорию `default` точкой отсчёта, не меняя ни одного прогноза.

    Backfitting оставляет поправки категорий «висящими»: `a[услуга]` получается средним по всем категориям,
    а `b[услуга, default]` — небольшим отрицательным сдвигом. Прогнозы от этого верные, но параметры
    нечитаемые: `exp(mu + a)` — не базовая длительность услуги, а средняя по смеси категорий её дня.

    Сдвигаем на `b[услуга, default]`: он уходит в `a[услуга]` и вычитается из всех категорий услуги.
    Сумма слагаемых для любого наблюдения не меняется, зато теперь:

        exp(mu + a[услуга])          — длительность обычного клиента,
        exp(b[услуга, категория])    — во сколько раз дольше эта категория.

    Именно в таком виде параметры показываются руководителю и попадают в отчёт.
    """
    for service in world_services:
        shift = params.b.get(f"{service}|{REFERENCE_CATEGORY}")
        if shift is None:
            continue
        params.a[service] = params.a.get(service, 0.0) + shift
        for key in list(params.b):
            if key.startswith(f"{service}|"):
                params.b[key] -= shift


def _sum_terms(coefficients: dict[str, dict[str, float]], groups: dict[str, list[str]],
               terms, index: int) -> float:
    return sum(coefficients[term][groups[term][index]] for term in terms)


def _weighted_mean(values: list[float], weights: list[float]) -> float:
    total = sum(weights)
    return sum(v * w for v, w in zip(values, weights)) / total if total else 0.0


def _count(keys: list[str]) -> dict[str, int]:
    out: dict[str, int] = defaultdict(int)
    for key in keys:
        out[key] += 1
    return out


def _sigma_by_service(train, logs, weights, mu, coefficients, groups, terms):
    """Разброс остатков по услуге: он задаёт p90 и ширину интервала ETA."""
    totals: dict[str, float] = defaultdict(float)
    totals_weight: dict[str, float] = defaultdict(float)
    all_total = 0.0
    all_weight = 0.0
    for i, o in enumerate(train):
        residual = logs[i] - mu - _sum_terms(coefficients, groups, terms, i)
        totals[o.service_code] += residual ** 2 * weights[i]
        totals_weight[o.service_code] += weights[i]
        all_total += residual ** 2 * weights[i]
        all_weight += weights[i]
    sigma = {code: max(SIGMA_FLOOR, math.sqrt(totals[code] / totals_weight[code]))
             for code in totals if totals_weight[code] > 0}
    default = max(SIGMA_FLOOR, math.sqrt(all_total / all_weight)) if all_weight else 0.35
    return sigma, default


def _version(day: date | None = None, ordinal: int = 1) -> str:
    return f"dm-{(day or datetime.now(timezone.utc).date()).isoformat()}-{ordinal:02d}"
