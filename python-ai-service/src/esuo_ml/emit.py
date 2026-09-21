"""Выгрузка: справочники, история, трассы, читаемая выборка и секции конфигурации.

Все файлы делаются из одного мира, поэтому идентификаторы в них совпадают по построению: сотрудник
из `seed.sql` — тот же, что в `model-params.json` и в трассе сценария. Ничего сводить руками не нужно.

SQL повторяет форму, проверенную `tools/load-generator` (режим `history`): те же колонки, `ON CONFLICT
DO NOTHING` и события только для талонов, которые действительно вставились.
"""

from __future__ import annotations

import csv
import json
import uuid
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from pathlib import Path

from .generate import Observation
from .world import World

# Целевые времена ожидания из infrastructure/config/queue-defaults.yml (дизайн-док 6.9).
# Нужны только для правдоподобного `due_at` в истории: на длительность обслуживания они не влияют.
TARGET_WAIT_MIN = {
    "qr": {"default": 20, "pensioner": 15, "svo": 5},
    "walkin": {"default": 30, "pensioner": 20, "svo": 5},
}

NAMESPACE = uuid.UUID("00000000-0000-4000-8000-0000000000ff")


def _uuid(*parts: object) -> str:
    return str(uuid.uuid5(NAMESPACE, ":".join(str(p) for p in parts)))


def _ts(value: datetime) -> str:
    return value.astimezone(tz=None).isoformat() if value.tzinfo is None else value.isoformat()


# ---------------------------------------------------------------------- справочники


def seed_sql(world: World) -> str:
    """Услуги, окна и сотрудники мира. Дополняет демо-каталог ноды, не конфликтуя с ним."""
    lines = [f"-- Справочники мира {world.code!r}: {world.name}", "BEGIN;"]
    lines.append(
        f"INSERT INTO branch(id,name,timezone) VALUES ('{world.branch_id}','Отделение {world.branch_id}',"
        f"'{world.timezone}') ON CONFLICT (id) DO NOTHING;")
    for s in world.services:
        lines.append(
            "INSERT INTO services(id,branch_id,code,name,number_prefix,slot_duration_min,slot_capacity) "
            f"VALUES ('{s.id}','{world.branch_id}','{s.code}','{s.name}','{s.prefix}',15,1) "
            "ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, number_prefix=EXCLUDED.number_prefix;")
    for e in world.employees:
        lines.append(
            f"INSERT INTO employees(id,branch_id,full_name) VALUES ('{e.id}','{world.branch_id}','{e.name}') "
            "ON CONFLICT (id) DO UPDATE SET full_name=EXCLUDED.full_name;")
    for w in world.windows:
        lines.append(
            f"INSERT INTO windows(id,branch_id,name,status) VALUES ('{w.id}','{world.branch_id}','{w.name}',"
            "'closed') ON CONFLICT (id) DO NOTHING;")
        for s in world.services:
            lines.append("INSERT INTO window_services(window_id,service_id) "
                         f"VALUES ('{w.id}','{s.id}') ON CONFLICT DO NOTHING;")
    lines.append("COMMIT;")
    return "\n".join(lines) + "\n"


# ---------------------------------------------------------------------- история


HANDLING_STATS_REBUILD = """
-- handling_stats пересобирается из обслуженных талонов, поэтому скрипт можно применять повторно.
DELETE FROM handling_stats;
INSERT INTO handling_stats(service_id, employee_id, window_id, client_category, avg_duration_sec, sample_count, updated_at)
SELECT service_id, employee_id, NULL, client_category,
       avg(EXTRACT(EPOCH FROM (completed_at - COALESCE(serving_started_at, called_at))))::double precision, count(*), now()
  FROM tickets WHERE status = 'done' AND completed_at > COALESCE(serving_started_at, called_at) AND employee_id IS NOT NULL
 GROUP BY service_id, employee_id, client_category;
INSERT INTO handling_stats(service_id, employee_id, window_id, client_category, avg_duration_sec, sample_count, updated_at)
SELECT service_id, NULL, NULL, client_category,
       avg(EXTRACT(EPOCH FROM (completed_at - COALESCE(serving_started_at, called_at))))::double precision, count(*), now()
  FROM tickets WHERE status = 'done' AND completed_at > COALESCE(serving_started_at, called_at)
 GROUP BY service_id, client_category;
"""


def history_sql(world: World, observations: list[Observation], seed: int) -> str:
    service_by_code = {s.code: s for s in world.services}
    numbers: dict[tuple[date, str], int] = {}
    lines = [f"-- Синтетическая история мира {world.code!r}: {len(observations)} обслуживаний, seed {seed}",
             "BEGIN;"]

    for index, o in enumerate(observations):
        service = service_by_code[o.service_code]
        key = (o.day, o.service_code)
        numbers[key] = numbers.get(key, 0) + 1
        display = f"{service.prefix}-{numbers[key]:03d}"
        ticket_id = _uuid("ticket", seed, o.day, index)
        token = _uuid("token", ticket_id)
        wait = TARGET_WAIT_MIN[o.source].get(o.client_category, 20)
        due = o.arrived_at + timedelta(minutes=wait)
        completed = o.started_at + timedelta(seconds=o.duration_sec)
        called = o.started_at - timedelta(seconds=5)
        lines.append(
            "INSERT INTO tickets(id, branch_id, public_token, display_number, service_id, source, client_category,"
            " status, config_version, issued_at, issued_day, eligible_from, due_at, window_id, employee_id,"
            " called_at, call_count, serving_started_at, completed_at, close_reason, updated_at, version) VALUES ("
            f"'{ticket_id}','{world.branch_id}','{token}','{display}','{service.id}','{o.source}',"
            f"'{o.client_category}','done',1,'{_ts(o.arrived_at)}','{o.day}','{_ts(o.arrived_at)}','{_ts(due)}',"
            f"'{o.window_id}','{o.employee_id}','{_ts(called)}',1,'{_ts(o.started_at)}','{_ts(completed)}',"
            f"'completed','{_ts(completed)}',3) ON CONFLICT DO NOTHING;")
        lines.append(_event(ticket_id, "ticket.created", None, "waiting", "client", o.arrived_at, 0))
        lines.append(_event(ticket_id, "ticket.called", "waiting", "called", "operator", called, 1))
        lines.append(_event(ticket_id, "ticket.serving", "called", "serving", "operator", o.started_at, 2))
        lines.append(_event(ticket_id, "ticket.completed", "serving", "done", "operator", completed, 3))

    lines.append(HANDLING_STATS_REBUILD.strip())
    lines.append("COMMIT;")
    return "\n".join(lines) + "\n"


def _event(ticket_id: str, event_type: str, from_status: str | None, to_status: str,
           actor: str, at: datetime, step: int) -> str:
    event_id = _uuid("event", ticket_id, step)
    from_sql = "NULL::text" if from_status is None else f"'{from_status}'"
    return ("INSERT INTO ticket_events(id, ticket_id, event_type, from_status, to_status, actor_type, payload,"
            f" occurred_at) SELECT '{event_id}'::uuid,'{ticket_id}'::uuid,'{event_type}',{from_sql},"
            f"'{to_status}','{actor}','{{\"synthetic\": true}}'::jsonb,'{_ts(at)}'::timestamptz"
            f" WHERE EXISTS (SELECT 1 FROM tickets WHERE id='{ticket_id}') ON CONFLICT DO NOTHING;")


# ---------------------------------------------------------------------- читаемая выборка


def holdout_csv(observations: list[Observation], path: Path, limit: int = 200) -> int:
    """Небольшая выборка, которую можно открыть глазами и которой пользуются тесты по обе стороны."""
    rows = observations[-limit:] if limit else observations
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(Observation.CSV_HEADER)
        for o in rows:
            writer.writerow(o.csv_row())
    return len(rows)


def read_observations_csv(path: Path) -> list[Observation]:
    with Path(path).open(encoding="utf-8", newline="") as handle:
        return [Observation.from_csv_row(row) for row in csv.DictReader(handle)]


# ---------------------------------------------------------------------- трассы сценариев


@dataclass
class Trace:
    scenario: str
    world: str
    seed: int
    date: str
    profile: str
    flow_bias: float
    arrivals: list[dict]

    def to_json(self) -> dict:
        return {
            "scenario": self.scenario, "world": self.world, "seed": self.seed, "date": self.date,
            "profile": self.profile, "flow_bias": self.flow_bias,
            "_comment": "Трасса демо-сценария (load-planning.md 7.1): моменты прихода и заранее вытянутый "
                        "шум длительности. Одинаковый seed даёт одинаковый прогон.",
            "arrivals": self.arrivals,
        }


def trace(world: World, day: date, scenario: str, seed: int, flow_bias: float,
          observations: list[Observation]) -> Trace:
    """Трасса: что подаёт `DemoScenarioRunner` — приходы и истинная длительность каждого обслуживания."""
    from .generate import profile_for

    profile_code, _ = profile_for(world, day)
    # `noise`, а не готовая длительность: она зависит от того, кто будет обслуживать, а это решается
    # уже во время прогона. Проигрыватель считает длительность как медиана(услуга, категория,
    # сотрудник окна, час) × noise — поэтому одну трассу можно проиграть с разными сменами.
    arrivals = [{
        "at": _ts(o.arrived_at),
        "source": o.source,
        "service_code": o.service_code,
        "client_category": o.client_category,
        "noise": round(o.noise, 4),
    } for o in observations]
    return Trace(scenario, world.code, seed, day.isoformat(), profile_code, flow_bias, arrivals)


# ---------------------------------------------------------------------- секции конфигурации


def planning_section(world: World, today: date | None = None, horizon_days: int = 45) -> dict:
    """Секция `planning` для публикации в центре: тот же источник, что у истории и трасс.

    `pension_days_of_month` — параметр генератора, а не конфигурации: центр знает не «3, 13 и 23 числа»,
    а конкретные даты. Поэтому здесь они раскрываются в `calendar` на `horizon_days` вперёд и назад,
    иначе в опубликованной конфигурации календарь был бы пустым и пенсионный день не сработал бы.
    """
    planning = {k: v for k, v in world.planning.items() if not k.startswith("_")}
    pension_days = planning.pop("pension_days_of_month", ())
    if pension_days and "pension" in planning.get("profiles", {}):
        today = today or date.today()
        existing = {row["date"] for row in planning.get("calendar", ())}
        calendar = list(planning.get("calendar", ()))
        for offset in range(-horizon_days, horizon_days + 1):
            day = today + timedelta(days=offset)
            if day.day in pension_days and day.isoformat() not in existing:
                calendar.append({"date": day.isoformat(), "profile": "pension"})
        planning["calendar"] = sorted(calendar, key=lambda row: row["date"])
    return {"planning": planning}


def write_json(path: Path, payload: dict) -> None:
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
