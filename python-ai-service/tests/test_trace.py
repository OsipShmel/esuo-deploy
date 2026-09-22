"""Трасса демо-сценария: самодостаточность и совпадение смены с симуляцией.

Проигрыватель сценариев (разработчик A) читает только трассу. Поэтому проверяем не форму файла ради
формы, а ровно то, от чего он зависит: по одной трассе можно восстановить длительность каждого
обслуживания, не заглядывая ни в `worlds/*.json`, ни в обученную модель.
"""

from __future__ import annotations

from datetime import date

import pytest

from esuo_ml import load_world, simulate_day
from esuo_ml.emit import trace
from esuo_ml.generate import profile_for

WORLD = "worlds/standard.json"
WORKDAY = date(2026, 9, 16)          # среда: рабочий день профиля `normal`
SEED = 42


@pytest.fixture(scope="module")
def world():
    return load_world(WORLD)


@pytest.fixture(scope="module")
def observations(world):
    rows = simulate_day(world, WORKDAY, seed=SEED)
    assert rows, "день должен давать обслуживания, иначе проверки ниже пустые"
    return rows


@pytest.fixture(scope="module")
def payload(world, observations):
    return trace(world, WORKDAY, "normal", SEED, 1.0, observations).to_json()


def median_from_trace(payload: dict, service_code: str, category: str,
                      employee_id: str, hour: int, window_id: str) -> float:
    """Медианная длительность ровно по формуле из трассы: отсутствующий ключ — 1.0."""
    truth = payload["duration_truth"]
    return (truth["base"][service_code]
            * truth["category"].get(service_code, {}).get(category, 1.0)
            * truth["employee"].get(employee_id, 1.0)
            * truth["employee_service"].get(employee_id, {}).get(service_code, 1.0)
            * truth["hour"].get(f"{hour:02d}", 1.0)
            * truth["window"].get(window_id, 1.0))


def test_trace_reproduces_every_true_duration(payload, world, observations):
    """Главное свойство: трассы достаточно, чтобы получить ту же длительность, что дал мир."""
    for o in observations:
        expected = world.median_duration_sec(o.service_code, o.client_category,
                                             o.employee_id, o.hour, o.window_id)
        actual = median_from_trace(payload, o.service_code, o.client_category,
                                   o.employee_id, o.hour, o.window_id)
        assert actual == pytest.approx(expected), f"{o.service_code}/{o.client_category}"


def test_trace_shift_matches_simulation(payload, observations):
    """Смена в трассе — та же, что отработала в симуляции.

    Сторожит неявную связь `day_shifts` с `simulate_day`: обе берут `assign_shifts` первым
    потребителем одинаково засеянного `Random`. Если это разъедется, пары окно → сотрудник
    в наблюдениях перестанут совпадать с трассой, и тест это поймает.
    """
    from_trace = {w["window_id"]: w["employee_id"] for step in payload["shift"] for w in step["windows"]}
    assert from_trace, "смена не должна быть пустой"
    for o in observations:
        assert from_trace.get(o.window_id) == o.employee_id, f"окно {o.window_id}"


def test_shift_follows_planned_windows(payload, world):
    """Смена — ступенчатая функция профиля: часы и число окон берутся из `planned_windows`."""
    _, profile = profile_for(world, WORKDAY)
    planned = {f"{int(hour):02d}:00": count for hour, count in profile["planned_windows"].items()}
    assert [step["from"] for step in payload["shift"]] == sorted(planned)
    for step in payload["shift"]:
        assert len(step["windows"]) == min(len(world.windows), planned[step["from"]])
        assert len({w["window_id"] for w in step["windows"]}) == len(step["windows"])


def test_arrivals_carry_noise_not_duration(payload):
    """Готовой длительности в трассе нет намеренно: она зависит от состава смены прогона."""
    assert payload["arrivals"], "трасса не должна быть пустой"
    for arrival in payload["arrivals"]:
        assert set(arrival) == {"at", "source", "service_code", "client_category", "noise"}
        assert arrival["noise"] > 0


def test_trace_is_self_describing(payload):
    """Проигрыватель не должен догадываться о часовом поясе и границах дня."""
    for field in ("branch_id", "timezone", "opening_time", "closing_time", "profile", "seed", "flow_bias"):
        assert payload[field] not in (None, ""), field
    assert payload["duration_truth"]["sigma"] > 0
    assert "median_sec" in payload["duration_truth"]["formula"]
