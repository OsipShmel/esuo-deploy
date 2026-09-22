"""Отчёт для жюри собирается и показывает то, ради чего он написан: прогноз лучше константы 300 с."""

from __future__ import annotations

import re
from datetime import date

from esuo_ml import load_world, simulate_period
from esuo_ml.report import write_report

WORLD = "worlds/standard.json"


def test_report_headline_favours_forecast(tmp_path):
    world = load_world(WORLD)
    observations = simulate_period(world, date(2026, 9, 20), 42, 42)
    out = tmp_path / "ml-report.md"
    write_report(world, observations, out, seed=42)
    text = out.read_text(encoding="utf-8")

    assert text.startswith("# Прогноз длительности обслуживания")
    row = next(line for line in text.splitlines() if line.startswith("| Среднее ожидание клиента"))
    without, with_forecast = (float(x.replace("*", "").replace(",", ".")) for x in re.findall(r"\|\s*([\d*,.]+)\s*", row)[:2])
    assert with_forecast * 3 < without, row
    assert "❌" not in text, "критерий готовности модели не выполнен"
