"""Применение модели: те же формулы, что на ноде (ml-model.md, разделы 3.2 и 5.1).

Этот модуль — исполняемая спецификация формата секции `model`. Java-реализация на ноде повторяет его
один в один, и тест `DurationModelTest` сверяет их на общем файле `holdout.csv`.

Почему нужны `counts`: нулевые поправки в JSON не пишутся (секция едет в каждом сообщении конфигурации),
поэтому «ключа нет» само по себе не отличает «группа известна, но эффекта нет» от «группа незнакома».
`counts` перечисляет группы, которые обучатель видел, и это отличие восстанавливает. От него зависят
`basis` и `samples` в журнале вызовов.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import Any

# Квантиль 90% стандартного нормального распределения: p90 = exp(m + Z90 * sigma).
Z90 = 1.2816

BASIS_MODEL = "model"
BASIS_MODEL_PARTIAL = "model_partial"

SIGMA_FLOOR = 0.05


@dataclass(frozen=True)
class Prediction:
    mean_sec: float
    p50_sec: float
    p90_sec: float
    basis: str
    model_version: str
    samples: int


@dataclass
class ModelParams:
    """Секция `model` центральной конфигурации (ml-model.md, 4.4)."""

    version: str
    enabled: bool = True
    use_window: bool = False
    mu: float = 0.0
    a: dict[str, float] = field(default_factory=dict)            # услуга
    b: dict[str, float] = field(default_factory=dict)            # "услуга|категория"
    e: dict[str, float] = field(default_factory=dict)            # сотрудник
    g: dict[str, float] = field(default_factory=dict)            # "сотрудник|услуга"
    h: dict[str, float] = field(default_factory=dict)            # час, "14"
    w: dict[str, float] = field(default_factory=dict)            # окно
    sigma: dict[str, float] = field(default_factory=dict)        # по услуге
    sigma_default: float = 0.35
    counts: dict[str, dict[str, int]] = field(default_factory=dict)
    trained_at: str | None = None
    train_from: str | None = None
    train_to: str | None = None
    status: str = "active"
    metrics: dict[str, Any] = field(default_factory=dict)

    # ------------------------------------------------------------------ JSON

    def to_section(self) -> dict[str, Any]:
        """Готовая секция для публикации в центре. Пустые словари не пишем."""
        section: dict[str, Any] = {"version": self.version, "enabled": self.enabled,
                                   "status": self.status, "use_window": self.use_window,
                                   "mu": round(self.mu, 6)}
        for key in ("trained_at", "train_from", "train_to"):
            value = getattr(self, key)
            if value:
                section[key] = value
        for key in ("a", "b", "e", "g", "h", "w"):
            values = {k: round(v, 6) for k, v in sorted(getattr(self, key).items()) if abs(v) >= 1e-4}
            if values:
                section[key] = values
        section["sigma"] = {k: round(v, 4) for k, v in sorted(self.sigma.items())}
        section["sigma_default"] = round(self.sigma_default, 4)
        if self.counts:
            section["counts"] = {k: dict(sorted(v.items())) for k, v in sorted(self.counts.items())}
        if self.metrics:
            section["metrics"] = self.metrics
        return section

    @classmethod
    def from_section(cls, section: dict[str, Any]) -> ModelParams:
        return cls(
            version=section["version"],
            enabled=bool(section.get("enabled", True)),
            use_window=bool(section.get("use_window", False)),
            mu=float(section.get("mu", 0.0)),
            a={k: float(v) for k, v in section.get("a", {}).items()},
            b={k: float(v) for k, v in section.get("b", {}).items()},
            e={k: float(v) for k, v in section.get("e", {}).items()},
            g={k: float(v) for k, v in section.get("g", {}).items()},
            h={str(k): float(v) for k, v in section.get("h", {}).items()},
            w={k: float(v) for k, v in section.get("w", {}).items()},
            sigma={k: float(v) for k, v in section.get("sigma", {}).items()},
            sigma_default=float(section.get("sigma_default", 0.35)),
            counts={k: {kk: int(vv) for kk, vv in v.items()} for k, v in section.get("counts", {}).items()},
            trained_at=section.get("trained_at"),
            train_from=section.get("train_from"),
            train_to=section.get("train_to"),
            status=section.get("status", "active"),
            metrics=section.get("metrics", {}),
        )

    # ------------------------------------------------------------------ прогноз

    def known(self, section: str, key: str) -> bool:
        return key in self.counts.get(section, {})

    def samples(self, section: str, key: str) -> int:
        return self.counts.get(section, {}).get(key, 0)

    def predict(self, service_code: str, category: str, employee_id: str | None,
                hour: int, window_id: str | None = None) -> Prediction | None:
        """`None` — модель не может дать прогноз, вызывающий уходит на запасную цепочку (5.3)."""
        if not self.enabled or not self.known("a", service_code):
            return None

        pair_b = f"{service_code}|{category}"
        pair_g = f"{employee_id}|{service_code}" if employee_id else None
        hour_key = str(hour)

        m = self.mu + self.a.get(service_code, 0.0)
        complete = True
        narrowest = self.samples("a", service_code)

        if self.known("b", pair_b):
            m += self.b.get(pair_b, 0.0)
        elif category != "default":
            complete = False                     # незнакомая категория: поправка 0

        if employee_id is not None:
            if self.known("e", employee_id):
                m += self.e.get(employee_id, 0.0)
                narrowest = self.samples("e", employee_id)
            else:
                complete = False                 # новый сотрудник: работает «как все»
            if pair_g and self.known("g", pair_g):
                m += self.g.get(pair_g, 0.0)
                narrowest = self.samples("g", pair_g)

        if self.known("h", hour_key):
            m += self.h.get(hour_key, 0.0)

        if self.use_window and window_id:
            if self.known("w", window_id):
                m += self.w.get(window_id, 0.0)
            else:
                complete = False

        sigma = max(SIGMA_FLOOR, self.sigma.get(service_code, self.sigma_default))
        return Prediction(
            mean_sec=math.exp(m + sigma ** 2 / 2),
            p50_sec=math.exp(m),
            p90_sec=math.exp(m + Z90 * sigma),
            basis=BASIS_MODEL if complete else BASIS_MODEL_PARTIAL,
            model_version=self.version,
            samples=narrowest,
        )
