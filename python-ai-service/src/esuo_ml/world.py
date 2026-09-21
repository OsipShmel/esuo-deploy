"""Мир: правда о длительностях обслуживания (ml-model.md, раздел 6).

Мир — это файл, а не константа в коде. Файл описывает услуги, окна, сотрудников с их идентификаторами,
истинные множители и шум; из него же берутся справочники отделения и профили потока. Поэтому миров может
быть сколько нужно (другое число сотрудников, другие профили), и ничто не захардкожено.

Этот модуль умеет две вещи: прочитать файл мира и посчитать истинную длительность обслуживания.
Обучатель (`fit.py`) сюда не заглядывает — он видит только наблюдения.
"""

from __future__ import annotations

import json
import math
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any


@dataclass(frozen=True)
class Service:
    id: str
    code: str
    name: str
    prefix: str
    base_sec: float


@dataclass(frozen=True)
class Employee:
    id: str
    name: str
    multiplier: float
    per_service: dict[str, float] = field(default_factory=dict)


@dataclass(frozen=True)
class Window:
    id: str
    name: str
    multiplier: float


@dataclass(frozen=True)
class World:
    code: str
    name: str
    timezone: str
    branch_id: str
    services: tuple[Service, ...]
    categories: tuple[str, ...]
    category_multipliers: dict[str, dict[str, float]]
    employees: tuple[Employee, ...]
    windows: tuple[Window, ...]
    hour_multipliers: dict[int, float]
    sigma: float
    planning: dict[str, Any]

    # ------------------------------------------------------------------ поиск

    def service(self, code: str) -> Service:
        for s in self.services:
            if s.code == code:
                return s
        raise KeyError(f"услуга {code!r} не описана в мире {self.code!r}")

    def employee(self, employee_id: str) -> Employee:
        for e in self.employees:
            if e.id == employee_id:
                return e
        raise KeyError(f"сотрудник {employee_id!r} не описан в мире {self.code!r}")

    def window(self, window_id: str) -> Window:
        for w in self.windows:
            if w.id == window_id:
                return w
        raise KeyError(f"окно {window_id!r} не описано в мире {self.code!r}")

    @property
    def service_codes(self) -> tuple[str, ...]:
        return tuple(s.code for s in self.services)

    @property
    def pension_days(self) -> tuple[int, ...]:
        return tuple(self.planning.get("pension_days_of_month", ()))

    # ------------------------------------------------------------------ правда

    def true_multipliers(self, service_code: str, category: str, employee_id: str,
                         hour: int, window_id: str) -> dict[str, float]:
        """Все множители по отдельности — для отчёта «восстановила ли модель правду»."""
        employee = self.employee(employee_id)
        return {
            "base": self.service(service_code).base_sec,
            "category": self.category_multipliers.get(service_code, {}).get(category, 1.0),
            "employee": employee.multiplier,
            "employee_service": employee.per_service.get(service_code, 1.0),
            "hour": self.hour_multipliers.get(hour, 1.0),
            "window": self.window(window_id).multiplier,
        }

    def median_duration_sec(self, service_code: str, category: str, employee_id: str,
                            hour: int, window_id: str) -> float:
        """Медианная (без шума) длительность: произведение всех множителей.

        Шум лог-нормальный, поэтому это именно медиана; средняя больше в exp(sigma^2 / 2) раз.
        """
        m = self.true_multipliers(service_code, category, employee_id, hour, window_id)
        return (m["base"] * m["category"] * m["employee"]
                * m["employee_service"] * m["hour"] * m["window"])

    def mean_duration_sec(self, service_code: str, category: str, employee_id: str,
                          hour: int, window_id: str) -> float:
        return self.median_duration_sec(service_code, category, employee_id, hour, window_id) \
            * math.exp(self.sigma ** 2 / 2)


def load_world(path: str | Path) -> World:
    raw = json.loads(Path(path).read_text(encoding="utf-8"))
    return World(
        code=raw["code"],
        name=raw["name"],
        timezone=raw.get("timezone", "Europe/Moscow"),
        branch_id=raw.get("branch_id", "b01"),
        services=tuple(Service(s["id"], s["code"], s["name"], s["prefix"], float(s["base_sec"]))
                       for s in raw["services"]),
        categories=tuple(raw["categories"]),
        category_multipliers={k: {kk: float(vv) for kk, vv in v.items()}
                              for k, v in raw["category_multipliers"].items()},
        employees=tuple(Employee(e["id"], e["name"], float(e["multiplier"]),
                                 {k: float(v) for k, v in e.get("per_service", {}).items()})
                        for e in raw["employees"]),
        windows=tuple(Window(w["id"], w["name"], float(w["multiplier"])) for w in raw["windows"]),
        hour_multipliers={int(h): float(m) for h, m in raw.get("hour_multipliers", {}).items()},
        sigma=float(raw["sigma"]),
        planning=raw.get("planning", {}),
    )
