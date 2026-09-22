"""ЕСУО: офлайн-инструменты модели длительности обслуживания (`ml-model.md`).

Обучение живёт здесь, а не на ноде. Результат — секция `model` центральной конфигурации, которая
доезжает до отделения обычным путём (`CONFIG` → `BRANCH_INBOX`). Поэтому отделение не зависит
от доступности ML: коэффициенты уже лежат в его базе, а инструменты обучения лишь время от времени
публикуют новую версию.

Порядок работы:

    uv run esuo-ml generate --world worlds/standard.json --out ../infrastructure/demo/scenarios
    uv run esuo-ml fit      --world worlds/standard.json --out build/model-params.json
    uv run esuo-ml report   --world worlds/standard.json --out ../docs/demo/ml-report.md
"""

from .baselines import CascadeBaseline, ConstantBaseline, ServiceMeanBaseline, mae
from .fit import FitOptions, FitResult, fit
from .generate import Observation, simulate_day, simulate_period
from .predict import ModelParams, Prediction
from .world import World, load_world

__all__ = [
    "CascadeBaseline", "ConstantBaseline", "ServiceMeanBaseline", "mae",
    "FitOptions", "FitResult", "fit",
    "Observation", "simulate_day", "simulate_period",
    "ModelParams", "Prediction",
    "World", "load_world",
]
