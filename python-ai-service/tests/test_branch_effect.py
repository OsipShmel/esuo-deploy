"""Сквозные свойства: что прогноз даёт отделению.

Эти тесты закрепляют не «модель хорошая», а два структурных факта, которые легко потерять при правках
и которые меняют выводы на защите:

1. под нагрузкой score почти не срабатывает, поэтому источник прогноза не влияет на ожидание;
2. на обещание клиенту (ETA) прогноз влияет сильно, и там `uniform` проигрывает в разы.

Если первое перестанет выполняться — значит кто-то поменял правило выбора, и это надо заметить.
"""

from __future__ import annotations

from datetime import date

import pytest

from esuo_ml import FitOptions, fit, load_world, simulate_period
from esuo_ml.baselines import CascadeBaseline
from esuo_ml.benchmark import (cascade_predictor, eta_error_min, model_predictor, run_branch,
                               uniform_predictor)

WORLD = "worlds/standard.json"
END_DAY = date(2026, 9, 20)
PENSION_DAY = date(2026, 9, 3)
SEED = 42


@pytest.fixture(scope="module")
def world():
    return load_world(WORLD)


@pytest.fixture(scope="module")
def history(world):
    return simulate_period(world, END_DAY, 42, SEED)


@pytest.fixture(scope="module")
def predictors(world, history):
    params = fit(history, FitOptions(), version="dm-branch").params
    cascade = CascadeBaseline(history)
    return {
        "uniform": uniform_predictor(world),
        "cascade": cascade_predictor(cascade),
        "model": model_predictor(params, cascade),
    }


def test_score_rule_fires_rarely_under_load(world, predictors):
    """Под нагрузкой просрочены почти все, и score выключается. Это объясняет равенство рук."""
    result = run_branch(world, PENSION_DAY, SEED, predictors["model"], 1.3, "model")
    assert result.served > 100
    assert result.score_share < 0.05, (
        f"score сработал в {result.score_share:.1%} решений — правило выбора изменилось, "
        "выводы раздела «Что это даёт отделению» надо пересчитать")
    assert result.reasons["overdue_edf"] > result.reasons.get("edf", 0)


def test_prediction_source_does_not_change_waiting(world, predictors):
    """Каскад и модель дают одинаковый зал: важен порядок кандидатов, а он совпадает."""
    results = {name: run_branch(world, PENSION_DAY, SEED, predict, 1.3, name)
               for name, predict in predictors.items()}
    cascade, model = results["cascade"], results["model"]
    assert abs(cascade.avg_wait_sec - model.avg_wait_sec) < 60, \
        "каскад и модель разошлись по ожиданию — проверьте, не изменился ли порядок кандидатов"
    assert cascade.served == model.served


def test_prediction_source_changes_the_promise_to_the_client(world, predictors):
    """А вот обещание клиенту зависит от прогноза сильно: там складываются величины."""
    errors = {}
    for name, predict in predictors.items():
        result = run_branch(world, PENSION_DAY, SEED, predict, 1.3, name)
        errors[name], _ = eta_error_min(world, result.clients, predict, result.shift, result.active)
    assert errors["uniform"] > errors["cascade"] * 2, (
        f"ожидали, что uniform ошибается в обещании кратно сильнее: {errors}")
    assert errors["model"] <= errors["cascade"] * 1.2


def test_model_uses_mean_for_sums_not_median(world, history):
    """ml-model.md 3.2: для сумм работы берётся средняя, иначе обещание смещено на exp(sigma^2/2)."""
    params = fit(history, FitOptions(), version="dm-point").params
    cascade = CascadeBaseline(history)
    service = world.services[0].code
    employee = world.employees[0].id
    as_mean = model_predictor(params, cascade, "mean")(service, "default", employee, 11)
    as_p50 = model_predictor(params, cascade, "p50")(service, "default", employee, 11)
    assert as_mean > as_p50, "средняя лог-нормального всегда больше медианы"
    assert as_mean / as_p50 == pytest.approx(1.06, abs=0.03)


def test_deadline_guarantee_costs_throughput(world, predictors):
    """Цена гарантии ожидания: SJF быстрее в среднем, но с длинным хвостом. Обе части важны."""
    fair = run_branch(world, PENSION_DAY, SEED, predictors["model"], 1.3, "edf", mode="slack")
    greedy = run_branch(world, PENSION_DAY, SEED, predictors["model"], 1.3, "sjf", mode="sjf")
    assert greedy.avg_wait_sec < fair.avg_wait_sec * 0.85, "SJF должен заметно выигрывать в среднем"
    assert greedy.p90_wait_sec > fair.p90_wait_sec, "и заметно проигрывать в хвосте"


def test_slow_employee_gets_fewer_clients_by_itself(world, predictors):
    """Вытягивающая схема: окно берёт клиента, когда освободилось, поэтому медленный берёт меньше.

    Это свойство архитектуры, а не модели: балансировать нагрузку на сотрудников отдельно не нужно.
    """
    result = run_branch(world, PENSION_DAY, SEED, predictors["model"], 1.3, "model")
    by_speed = sorted(result.per_employee.items(),
                      key=lambda item: world.employee(item[0]).multiplier)
    fastest, slowest = by_speed[0], by_speed[-1]
    if world.employee(fastest[0]).multiplier < world.employee(slowest[0]).multiplier:
        assert fastest[1] >= slowest[1], (
            "быстрый сотрудник должен обслужить не меньше медленного: "
            f"{fastest[1]} против {slowest[1]}")


# ---------------------------------------------------------------------- планирование нагрузки


def test_flat_default_underestimates_required_windows(world, history, predictors):
    """`default-service-duration-sec` = 300 с занижает потребность в окнах: это главный эффект прогноза.

    300 с — это примерно про выдачу посылки; в пенсионный день в смеси много финансов и пенсионеров,
    и настоящая средняя почти вдвое больше. Недооценка мощности прямо переходит в недобор смены.
    """
    from esuo_ml.benchmark import forecast_work_min, needed_windows, _shift

    shift = _shift(world, PENSION_DAY, SEED)
    target = float(world.planning["utilization_target"])

    def oracle(service_code, category, employee_id, hour):
        return world.mean_duration_sec(service_code, category, employee_id, hour, world.windows[0].id)

    truth = sum(needed_windows(forecast_work_min(world, PENSION_DAY, oracle, 1.3, shift), target).values())
    flat = sum(needed_windows(forecast_work_min(world, PENSION_DAY, predictors["uniform"], 1.3, shift),
                              target).values())
    assert flat < truth * 0.75, f"ожидали заметную недооценку: {flat} окно-часов против {truth}"


def test_forecast_driven_staffing_collapses_waiting(world, history, predictors):
    """Штатное расписание по прогнозу против расписания по константе: ожидание отличается в разы."""
    from esuo_ml.benchmark import forecast_work_min, needed_windows, run_branch, _shift

    shift = _shift(world, PENSION_DAY, SEED)
    target = float(world.planning["utilization_target"])
    waits = {}
    for name in ("uniform", "model"):
        plan = needed_windows(forecast_work_min(world, PENSION_DAY, predictors[name], 1.3, shift), target)
        result = run_branch(world, PENSION_DAY, SEED, predictors["model"], 1.3, name, staffing=plan)
        waits[name] = result.avg_wait_sec
    assert waits["uniform"] > waits["model"] * 3, (
        f"эффект планирования должен быть кратным, а не процентным: {waits}")


def test_planning_gain_is_not_free(world, predictors):
    """Честная оговорка: выигрыш стоит окно-часов. Прогноз не создаёт мощность, а говорит, сколько нужно."""
    from esuo_ml.benchmark import forecast_work_min, needed_windows, _shift

    shift = _shift(world, PENSION_DAY, SEED)
    target = float(world.planning["utilization_target"])
    flat = sum(needed_windows(forecast_work_min(world, PENSION_DAY, predictors["uniform"], 1.3, shift),
                              target).values())
    model = sum(needed_windows(forecast_work_min(world, PENSION_DAY, predictors["model"], 1.3, shift),
                               target).values())
    assert model > flat, "план по модели требует больше смены — это цена короткой очереди"
