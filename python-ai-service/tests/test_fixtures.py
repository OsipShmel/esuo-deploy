"""Эталон для Java-теста не должен незаметно разъехаться.

`DurationModelTest` на ноде читает `holdout.csv` и `model-params.json` из тестовых ресурсов и сверяет
прогноз Java с прогнозом обучателя. Если обучатель изменится, а эталон не пересоберут, Java-тест начнёт
падать по непонятной причине. Этот тест ловит расхождение здесь и подсказывает команду пересборки.
"""

from __future__ import annotations

import csv
import json
from pathlib import Path

import pytest

from esuo_ml.cli import FIXTURES_DIR, build_fixtures
from esuo_ml.predict import ModelParams

REGENERATE = "uv run esuo-ml fixtures"


@pytest.fixture(scope="module")
def built():
    return build_fixtures()


@pytest.fixture(scope="module")
def directory():
    return Path(FIXTURES_DIR)


def test_fixture_files_exist(directory):
    assert (directory / "holdout.csv").exists(), f"нет эталонной выборки; соберите: {REGENERATE}"
    assert (directory / "model-params.json").exists(), f"нет эталонных параметров; соберите: {REGENERATE}"


def test_committed_params_match_the_trainer(built, directory):
    _, _, section = built
    stored = json.loads((directory / "model-params.json").read_text(encoding="utf-8"))["model"]
    assert stored == section, f"параметры разъехались с обучателем; пересоберите: {REGENERATE}"


def test_committed_holdout_matches_the_generator(built, directory):
    _, rows, _ = built
    with (directory / "holdout.csv").open(encoding="utf-8", newline="") as handle:
        stored = list(csv.reader(handle))
    expected = [list(rows[0].CSV_HEADER)] + [o.csv_row() for o in rows]
    assert stored == expected, f"выборка разъехалась с генератором; пересоберите: {REGENERATE}"


def test_every_fixture_row_gets_a_model_prediction(built, directory):
    """Java-тест сверяет прогнозы построчно, поэтому в эталоне не должно быть строк без прогноза."""
    _, rows, section = built
    params = ModelParams.from_section(section)
    for observation in rows:
        prediction = params.predict(observation.service_code, observation.client_category,
                                    observation.employee_id, observation.hour, observation.window_id)
        assert prediction is not None, f"нет прогноза для {observation.service_code}"
        assert prediction.p50_sec > 0


def test_fixture_is_stable_across_runs():
    """Дважды собранный эталон совпадает: иначе Java-тест падал бы через день."""
    first = build_fixtures()[2]
    second = build_fixtures()[2]
    assert first == second
