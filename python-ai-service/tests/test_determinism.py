"""Детерминизм и приёмка версии.

Демо-сценарии обязаны повторяться: критерий готовности из `load-planning.md` 7.2 — три прогона подряд
дают один и тот же ход событий. Значит генератор при одном зерне обязан давать побайтово одно и то же.
"""

from __future__ import annotations

from datetime import date

import pytest

from esuo_ml import FitOptions, ModelParams, fit, load_world, simulate_day, simulate_period

WORLD = "worlds/standard.json"
END_DAY = date(2026, 9, 20)          # конец периода истории; фиксирован, чтобы тест не зависел от «сегодня»
WORKDAY = date(2026, 9, 16)          # среда: рабочий день профиля `normal`, не пенсионный


@pytest.fixture(scope="module")
def world():
    return load_world(WORLD)


def test_workday_constant_is_actually_a_working_day(world):
    """Страховка от вакуумных тестов: на закрытом дне проверки ниже проходили бы на пустых списках."""
    assert WORKDAY.weekday() == 2
    assert simulate_day(world, WORKDAY, seed=42), "WORKDAY должен давать обслуживания"


def test_same_seed_gives_the_same_day(world):
    first = simulate_day(world, WORKDAY, seed=42)
    second = simulate_day(world, WORKDAY, seed=42)
    assert [o.csv_row() for o in first] == [o.csv_row() for o in second]


def test_different_seed_gives_a_different_day(world):
    first = simulate_day(world, WORKDAY, seed=42)
    second = simulate_day(world, WORKDAY, seed=43)
    assert [o.csv_row() for o in first] != [o.csv_row() for o in second]


def test_same_seed_gives_the_same_model(world):
    observations = simulate_period(world, END_DAY, 28, seed=42)
    first = fit(observations, FitOptions(), version="dm-a").params.to_section()
    second = fit(observations, FitOptions(), version="dm-a").params.to_section()
    first.pop("trained_at")
    second.pop("trained_at")
    assert first == second


def test_closed_day_has_no_clients(world):
    """Воскресенье в профилях помечено `closed`: обслуживаний быть не должно."""
    sunday = date(2026, 9, 20)
    assert sunday.weekday() == 6
    assert simulate_day(world, sunday, seed=42) == []


def test_pension_day_brings_more_clients_than_a_usual_day(world):
    """Пенсионный день: поток ×1,8 по профилю. Иначе сценарий «выплаты» нечем показывать."""
    pension = simulate_day(world, date(2026, 9, 3), seed=42)          # 3-е число
    usual = simulate_day(world, date(2026, 9, 9), seed=42)            # обычная среда
    assert len(pension) > len(usual)


def test_pension_day_shifts_the_mix_towards_finance(world):
    """В пенсионный день больше финансов и больше пенсионеров — от этого зависит перегрузка в сценарии."""
    pension = simulate_day(world, date(2026, 9, 3), seed=42)
    usual = simulate_day(world, date(2026, 9, 9), seed=42)
    assert _share(pension, "finance") > _share(usual, "finance")
    assert _pensioner_share(pension) > _pensioner_share(usual)


def _share(observations, service_code: str) -> float:
    return sum(1 for o in observations if o.service_code == service_code) / len(observations)


def _pensioner_share(observations) -> float:
    return sum(1 for o in observations if o.client_category == "pensioner") / len(observations)


# ---------------------------------------------------------------------- приёмка версии


def test_worse_version_is_rejected(world):
    """ml-model.md 4.3: версия хуже прежней больше чем на 5% не принимается и не уходит в центр."""
    observations = simulate_period(world, END_DAY, 28, seed=42)
    good = fit(observations, FitOptions(), version="dm-good")
    assert good.accepted

    # Прежняя версия с нарочито хорошей метрикой: новая на её фоне обязана быть отклонена.
    pretend_better = ModelParams(version="dm-prev", metrics={"mae_sec": good.params.metrics["mae_sec"] / 2})
    checked = fit(observations, FitOptions(), baseline=pretend_better, version="dm-new")
    assert not checked.accepted
    assert checked.status == "rejected"
    assert checked.params.enabled is False, "отклонённая версия не должна быть включена"


def test_equal_version_is_accepted(world):
    """Та же метрика — в допуске 5%, версия принимается: обучение раз в сутки не должно ломаться на шуме."""
    observations = simulate_period(world, END_DAY, 28, seed=42)
    first = fit(observations, FitOptions(), version="dm-1")
    second = fit(observations, FitOptions(), baseline=first.params, version="dm-2")
    assert second.accepted


def test_too_little_data_is_not_a_model(world):
    """Мало наблюдений — честный статус, а не модель на трёх талонах."""
    observations = simulate_period(world, END_DAY, 1, seed=42)
    result = fit(observations, FitOptions(min_observations=10_000), version="dm-small")
    assert result.status == "insufficient_data"
    assert not result.accepted
