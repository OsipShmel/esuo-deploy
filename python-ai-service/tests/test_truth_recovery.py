"""Критерии готовности модели (ml-model.md, 7.3) как обычные проверки.

Смысл этих тестов — не «код не падает», а «модель делает то, что обещано в документе». Поэтому здесь
явные числа из 7.3, а не «примерно похоже»: если порог перестанет выполняться, это решение команды,
а не повод подкрутить тест.
"""

from __future__ import annotations

import math
from datetime import date

import pytest

from esuo_ml import FitOptions, fit, load_world, simulate_period
from esuo_ml.baselines import CascadeBaseline, ServiceMeanBaseline, mae

WORLD = "worlds/standard.json"
SEED = 42
DAYS = 42
END_DAY = date(2026, 9, 20)          # фиксированная дата: тест не должен зависеть от «сегодня»


@pytest.fixture(scope="module")
def world():
    return load_world(WORLD)


@pytest.fixture(scope="module")
def observations(world):
    return simulate_period(world, END_DAY, DAYS, SEED)


@pytest.fixture(scope="module")
def result(observations):
    return fit(observations, FitOptions(), version="dm-test")


@pytest.fixture(scope="module")
def result_with_window(observations):
    return fit(observations, FitOptions(use_window=True), version="dm-test-window")


# ---------------------------------------------------------------------- 7.3


def test_mae_is_at_least_15_percent_below_service_mean(result):
    """Главный критерий: модель точнее среднего по услуге минимум на 15%."""
    gain = result.params.metrics["gain_over_service_mean"]
    assert gain >= 0.15, f"выигрыш к среднему по услуге {gain:.1%}, нужно не меньше 15%"


def test_employee_multipliers_are_recovered(world, result):
    """Множители сотрудников восстановлены с ошибкой не больше 0,1."""
    for employee in world.employees:
        estimated = math.exp(result.params.e.get(employee.id, 0.0))
        error = abs(estimated - employee.multiplier)
        assert error <= 0.1, (f"{employee.name}: истинный ×{employee.multiplier}, "
                              f"оценённый ×{estimated:.3f}, расхождение {error:.3f}")


def test_window_effect_is_not_invented(world, result_with_window):
    """Эффекта окна в мире нет; модель не должна его найти: |w| < 0,05."""
    coefficients = result_with_window.params.w
    assert coefficients, "поправка на окно должна обучаться при use_window=True"
    for window in world.windows:
        value = coefficients.get(window.id, 0.0)
        assert abs(value) < 0.05, f"{window.name}: w = {value:+.4f}, модель выдумала эффект окна"


def test_p90_coverage_is_honest(result):
    """Покрытие p90 в пределах 0,85–0,95: интервал не занижен и не раздут."""
    coverage = result.params.metrics["p90_coverage"]
    assert 0.85 <= coverage <= 0.95, f"покрытие p90 = {coverage}, ожидается 0,85–0,95"


# ---------------------------------------------------------------------- свойства модели


def test_category_multipliers_are_readable(world, result):
    """`b` читается прямо как множитель категории: точка отсчёта — обычный клиент."""
    params = result.params
    for service in world.services:
        assert abs(params.b.get(f"{service.code}|default", 0.0)) < 1e-9, \
            "поправка обычного клиента должна быть нулём после привязки к точке отсчёта"
        for category in world.categories:
            if category == "default":
                continue
            true_value = world.category_multipliers[service.code][category]
            estimated = math.exp(params.b.get(f"{service.code}|{category}", 0.0))
            assert abs(estimated - true_value) <= 0.15, (
                f"{service.code}/{category}: истинный ×{true_value}, оценённый ×{estimated:.3f}")


def test_service_base_durations_are_recovered(world, result):
    """`exp(mu + a)` — длительность обычного клиента у среднего сотрудника, в пределах 15%."""
    mean_employee = sum(e.multiplier for e in world.employees) / len(world.employees)
    for service in world.services:
        estimated = math.exp(result.params.mu + result.params.a.get(service.code, 0.0))
        expected = service.base_sec * mean_employee
        assert abs(estimated / expected - 1) <= 0.15, (
            f"{service.code}: ожидалось около {expected:.0f} с, получено {estimated:.0f} с")


def test_model_is_close_to_the_oracle(world, result):
    """Оракул знает правду и ошибается только на шуме. Модель не должна отставать больше чем на 10%."""
    evaluation = result.holdout or result.train

    def oracle(o):
        return world.median_duration_sec(o.service_code, o.client_category, o.employee_id,
                                        o.hour, o.window_id)

    def model(o):
        prediction = result.params.predict(o.service_code, o.client_category, o.employee_id,
                                          o.hour, o.window_id)
        return prediction.p50_sec

    oracle_mae = mae(evaluation, oracle)
    model_mae = mae(evaluation, model)
    assert model_mae <= oracle_mae * 1.10, (
        f"MAE модели {model_mae:.1f} с против предела {oracle_mae:.1f} с")


def test_model_beats_baselines(result):
    """Модель не хуже каскада и заметно лучше константы — обе линии считаются при обучении."""
    metrics = result.params.metrics
    assert metrics["mae_sec"] <= metrics["baseline_cascade_mae_sec"]
    assert metrics["mae_sec"] < metrics["baseline_service_mae_sec"]
    assert metrics["mae_sec"] < metrics["baseline_const_mae_sec"]


# ---------------------------------------------------------------------- сжатие


def test_shrinkage_pulls_small_groups_towards_zero():
    """Смысл сжатия: у группы с одним наблюдением поправка почти не берётся, у большой — почти целиком."""
    k = 10.0
    assert 1 / (1 + k) == pytest.approx(0.0909, abs=1e-4)
    assert 10 / (10 + k) == pytest.approx(0.5)
    assert 100 / (100 + k) == pytest.approx(0.9091, abs=1e-4)


def test_new_employee_is_served_as_average(world, result):
    """Незнакомый сотрудник: поправка нулевая, прогноз «как у всех», основа model_partial."""
    service = world.services[0].code
    prediction = result.params.predict(service, "default", "00000000-0000-4000-8000-ffffffffffff", 11)
    assert prediction is not None
    assert prediction.basis == "model_partial"
    average = result.params.predict(service, "default", None, 11)
    assert prediction.p50_sec == pytest.approx(average.p50_sec)


def test_unknown_service_falls_back(result):
    """Незнакомая услуга: модель отказывается, вызывающий уходит на каскад (ml-model.md 5.3)."""
    assert result.params.predict("unknown-service", "default", None, 11) is None


def test_disabled_model_predicts_nothing(result):
    """`enabled: false` — мгновенный откат на запасную цепочку без смены scoring-strategy."""
    params = result.params
    params.enabled = False
    try:
        assert params.predict(params_service(params), "default", None, 11) is None
    finally:
        params.enabled = True


def params_service(params) -> str:
    return next(iter(params.counts["a"]))
