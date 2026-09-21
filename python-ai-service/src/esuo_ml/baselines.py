"""Базовые линии для сравнения (ml-model.md, 7.1). Всё, что уже умеет система без модели.

Сравнивать модель нужно не с нулём, а с тем, что работает сейчас. Три линии:

1. константа `default-service-duration-sec` = 300 с — поведение ноды до первой статистики;
2. среднее по услуге — то, что берёт `uniform` для ETA;
3. каскад `handling_stats` — то, что берёт `heuristic` без модели (дизайн-док 16.2).

Каскад воспроизведён здесь ровно по `HandlingStatsRepository.forecast`, включая порог `min_samples`
и порядок уровней. Если поведение ноды изменится, менять надо оба места.
"""

from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass

from .generate import Observation

DEFAULT_SERVICE_DURATION_SEC = 300.0
HEURISTIC_MIN_SAMPLES = 5

LEVEL_EMPLOYEE_SERVICE_CATEGORY = "employee_service_category"
LEVEL_EMPLOYEE_SERVICE = "employee_service"
LEVEL_SERVICE_CATEGORY = "service_category"
LEVEL_SERVICE = "service"
LEVEL_FALLBACK = "fallback"


@dataclass
class _Bucket:
    total: float = 0.0
    count: int = 0

    def add(self, value: float) -> None:
        self.total += value
        self.count += 1

    @property
    def mean(self) -> float:
        return self.total / self.count if self.count else 0.0


class ServiceMeanBaseline:
    """Среднее по услуге, как его считает `HandlingStatsRepository.averageForService`."""

    def __init__(self, observations: list[Observation], min_samples: int = HEURISTIC_MIN_SAMPLES):
        self.min_samples = min_samples
        self._by_service: dict[str, _Bucket] = defaultdict(_Bucket)
        for o in observations:
            self._by_service[o.service_code].add(o.duration_sec)

    def predict(self, observation: Observation) -> float:
        bucket = self._by_service.get(observation.service_code)
        if bucket and bucket.count >= self.min_samples:
            return bucket.mean
        return DEFAULT_SERVICE_DURATION_SEC


class CascadeBaseline:
    """Каскад `handling_stats` из пяти уровней (дизайн-док 16.2)."""

    def __init__(self, observations: list[Observation], min_samples: int = HEURISTIC_MIN_SAMPLES):
        self.min_samples = min_samples
        self._levels: dict[str, dict[str, _Bucket]] = {
            LEVEL_EMPLOYEE_SERVICE_CATEGORY: defaultdict(_Bucket),
            LEVEL_EMPLOYEE_SERVICE: defaultdict(_Bucket),
            LEVEL_SERVICE_CATEGORY: defaultdict(_Bucket),
            LEVEL_SERVICE: defaultdict(_Bucket),
        }
        for o in observations:
            for level, key in self._keys(o):
                self._levels[level][key].add(o.duration_sec)

    @staticmethod
    def _keys(o: Observation) -> list[tuple[str, str]]:
        return [
            (LEVEL_EMPLOYEE_SERVICE_CATEGORY, f"{o.employee_id}|{o.service_code}|{o.client_category}"),
            (LEVEL_EMPLOYEE_SERVICE, f"{o.employee_id}|{o.service_code}"),
            (LEVEL_SERVICE_CATEGORY, f"{o.service_code}|{o.client_category}"),
            (LEVEL_SERVICE, o.service_code),
        ]

    def forecast(self, observation: Observation) -> tuple[float, str, int]:
        for level, key in self._keys(observation):
            bucket = self._levels[level].get(key)
            if bucket and bucket.count >= self.min_samples:
                return bucket.mean, level, bucket.count
        return DEFAULT_SERVICE_DURATION_SEC, LEVEL_FALLBACK, 0

    def predict(self, observation: Observation) -> float:
        return self.forecast(observation)[0]


class ConstantBaseline:
    """Константа из конфигурации: `default-service-duration-sec`."""

    def __init__(self, seconds: float = DEFAULT_SERVICE_DURATION_SEC):
        self.seconds = seconds

    def predict(self, observation: Observation) -> float:
        return self.seconds


def mae(observations: list[Observation], predict) -> float:
    if not observations:
        return 0.0
    return sum(abs(predict(o) - o.duration_sec) for o in observations) / len(observations)
