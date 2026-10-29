#!/usr/bin/env python3
"""Демо A/B: одно и то же утро пенсионных выплат на двух отделениях (load-planning.md 7.2).

b01 — «без прогноза»: ни модели, ни истории, прогноз упирается в константу 300 с.
b02 — «с прогнозом»: история и опубликованная модель длительности.

Обе ноды получают одну и ту же трассу (scenario-pension.json: на 30 % больше клиентов, чем в профиле).
Разницу создаёт одно правило: руководитель утром ставит смену по прогнозу своего отделения
(GET /admin/load → работа часа → ⌈работа / (60 × utilization_target)⌉ окон, но не больше, чем окон есть).
Длительность обслуживания проигрыватель берёт из «правды мира» трассы (duration_truth × noise), а не из модели:
иначе демо доказывало бы само себя.

Только стандартная библиотека; стенд готовит ab-prepare.sh. Запуск — через ./scripts/demo/run.sh pension-ab.
"""
from __future__ import annotations

import argparse
import json
import math
import os
import sys
import threading
import time
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone

try:
    from zoneinfo import ZoneInfo
except ImportError:                                   # python < 3.9
    ZoneInfo = None

# ---------------------------------------------------------------------------------------------- вывод

TTY = sys.stdout.isatty()


def c(code: str, text: str) -> str:
    return f"\033[{code}m{text}\033[0m" if TTY else text


LEVEL_RU = {"normal": "норма", "high": "высокая", "overload": "перегрузка", None: "—"}
LEVEL_COLOR = {"normal": "32", "high": "33", "overload": "1;31"}
CAUSE_RU = {
    "flow_above_forecast": "клиентов больше прогноза",
    "slow_service": "обслуживание медленнее обычного",
    "few_windows": "окон меньше, чем нужно",
    "booking_peak": "много записей на этот час",
    "overdue_backlog": "накопились просроченные",
}
PRINT_LOCK = threading.Lock()


def say(text: str = "") -> None:
    with PRINT_LOCK:
        print(text, flush=True)


def level_txt(level) -> str:
    return c(LEVEL_COLOR.get(level, "0"), LEVEL_RU.get(level, str(level)))


# ---------------------------------------------------------------------------------------------- HTTP


def http(method: str, url: str, body=None, token: str | None = None, headers: dict | None = None,
         timeout: float = 10.0):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw) if raw else None
        except ValueError:
            return e.code, None
    except (urllib.error.URLError, OSError) as e:
        return 0, {"error": str(e)}


def iso(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_instant(text: str) -> datetime:
    return datetime.fromisoformat(text.replace("Z", "+00:00"))


def zone(name: str):
    if ZoneInfo is not None:
        try:
            return ZoneInfo(name)
        except Exception:
            pass
    return timezone(timedelta(hours=3))               # Europe/Moscow без перехода на летнее время


# ---------------------------------------------------------------------------------------------- время


class VirtualClock:
    """Демо-время проигрывателя: то же правило, что у DemoClock ноды (якорь + прошедшее × скорость)."""

    def __init__(self, start: datetime, rate: float):
        self.start, self.rate, self.real0 = start, rate, time.monotonic()

    def now(self) -> datetime:
        return self.start + timedelta(seconds=(time.monotonic() - self.real0) * self.rate)


# ---------------------------------------------------------------------------------------------- правда мира


class Truth:
    """duration_truth трассы: median = base × category × employee × employee_service × hour × window."""

    def __init__(self, trace: dict):
        t = trace["duration_truth"]
        self.t = t
        self.windows = [w for w in t["window"].keys()]
        # Смена трассы называет сотрудников только тех окон, что открыты по плану профиля (три).
        # Остальные окна рассаживаем тем же правилом, что генератор (generate.assign_shifts):
        # окно i получает сотрудника i по кругу из списка «сначала смена, потом остальные».
        shift = trace["shift"][0]["windows"] if trace.get("shift") else []
        ordered = [w["employee_id"] for w in shift]
        ordered += sorted(e for e in t["employee"].keys() if e not in ordered)
        self.employee = {}
        for i, wid in enumerate(self.windows):
            known = next((w["employee_id"] for w in shift if w["window_id"] == wid), None)
            self.employee[wid] = known or ordered[i % len(ordered)]

    def seconds(self, service: str, category: str, window_id: str, hour: int, noise: float) -> float:
        t = self.t
        e = self.employee.get(window_id)
        median = (t["base"].get(service, 300.0)
                  * t["category"].get(service, {}).get(category, 1.0)
                  * t["employee"].get(e, 1.0)
                  * t["employee_service"].get(e, {}).get(service, 1.0)
                  * t["hour"].get(f"{hour:02d}", 1.0)
                  * t["window"].get(window_id, 1.0))
        return max(20.0, median * noise)


# ---------------------------------------------------------------------------------------------- отделение


class Node:
    def __init__(self, code: str, url: str, args, run_id: str):
        self.code, self.url, self.args, self.run_id = code, url.rstrip("/"), args, run_id
        self.op = self.adm = None
        self.tz = None
        self.services = {}                     # code → id
        self.service_code = {}                 # id → code
        self.plan = {}                         # час → окон
        self.forecast = []
        self.basis = None
        self.limits = []
        self.bookings = {}                     # "10:00" → confirmed/rejected:REASON/нет ответа
        # прогон
        self.meta = {}                         # ticket_id → dict(svc, cat, noise, issued)
        self.open = []                         # открытые окна, по порядку
        self.busy = {}                         # окно → (ticket_id, finish_virtual)
        self.retry_at = {}                     # окно → real time, когда снова пробовать call-next
        self.waits = []                        # ожидание обслуженных, демо-минуты
        self.issued = 0
        self.served = 0
        self.max_queue = 0
        self.samples = []                      # (время, уровень, clear, ratio, открыто, очередь, причины)
        self.levels = []                       # смена уровней: (время, уровень)
        self.errors = 0
        self.last_error = ""
        self.error_kinds = []                  # первые разные ошибки — в факты прогона, для разбора

    # ---------------------------------------------------------------- доступ

    def login(self):
        for role, login, pw in (("op", "operator", self.args.operator_password),
                                ("adm", "admin", self.args.admin_password)):
            st, body = http("POST", self.url + "/api/v1/auth/login", {"login": login, "password": pw})
            if st != 200 or not body or not body.get("access_token"):
                raise SystemExit(f"{self.code}: не удалось войти как {login} (HTTP {st}). Пароли — в .env")
            setattr(self, role, body["access_token"])
        st, br = http("GET", self.url + "/api/v1/public/branch")
        if st != 200:
            raise SystemExit(f"{self.code}: GET /public/branch → HTTP {st}")
        self.tz = zone(br.get("timezone") or "Europe/Moscow")
        for s in br.get("services", []):
            self.services[s["code"]] = s["id"]
            self.service_code[s["id"]] = s["code"]
        st, clock = http("GET", self.url + "/api/v1/public/demo/clock")
        if st != 200 or not clock or not clock.get("enabled"):
            raise SystemExit(f"{self.code}: демо-часы выключены — DEMO_MODE=true в .env и пересоздать ноды")

    def call(self, method, path, body=None, admin=False, headers=None):
        st, res = http(method, self.url + path, body, self.adm if admin else self.op, headers)
        return st, res

    def set_clock(self, now: datetime, rate: float) -> bool:
        st, _ = self.call("PUT", "/api/v1/admin/demo/clock", {"now": iso(now), "rate": rate}, admin=True)
        return st == 200

    # ---------------------------------------------------------------- утро: прогноз и смена

    def read_plan(self, utilization: float, max_windows: int):
        st, load = self.call("GET", "/api/v1/admin/load", admin=True)
        if st != 200 or not load:
            raise SystemExit(f"{self.code}: GET /admin/load → HTTP {st}")
        self.forecast = load.get("forecast") or []
        self.basis = (load.get("duration_model") or {}).get("basis")
        if not self.forecast:
            raise SystemExit(f"{self.code}: прогноз пуст — секция planning не опубликована? (ab-prepare.sh)")
        for h in self.forecast:
            hour = parse_instant(h["hour_start"]).astimezone(self.tz).hour
            need = math.ceil(float(h["work_min"]) / (60.0 * utilization) - 1e-9)
            self.plan[hour] = max(1, min(max_windows, need))

    def work_at(self, hour: int):
        for h in self.forecast:
            if parse_instant(h["hour_start"]).astimezone(self.tz).hour == hour:
                return h
        return None

    def read_limits(self, center: str, date: str):
        st, body = http("GET", f"{center}/api/v1/branches/{self.code}/booking-limits?date={date}")
        if st == 200 and isinstance(body, list):
            self.limits = body
        else:                                              # центр недоступен — спросим ноду напрямую
            st, body = http("GET", f"{self.url}/api/v1/public/booking-limits?date={date}")
            self.limits = body if st == 200 and isinstance(body, list) else []

    def free_slots(self, center: str, service_id: str, date: str, hour: int) -> int:
        st, body = http("GET", f"{center}/api/v1/branches/{self.code}/slots?service_id={service_id}&date={date}")
        if st != 200 or not isinstance(body, list):
            return -1
        return sum(1 for s in body if parse_instant(s["slot_start"]).astimezone(self.tz).hour == hour)

    # ---------------------------------------------------------------- прогон

    def issue(self, i: int, a: dict, now: datetime):
        svc = self.services.get(a["service_code"])
        if svc is None:
            return
        cat = a.get("client_category", "default")
        key = f"ab-{self.run_id}-{self.code}-{i}"
        if a.get("source") == "walkin" and self.open:
            win = self.open[i % len(self.open)]
            st, t = self.call("POST", f"/api/v1/windows/{win}/walkin", {"service_id": svc, "client_category": cat},
                              headers={"Idempotency-Key": key})
        else:
            st, t = self.call("POST", "/api/v1/public/qr-tickets",
                              {"qr_code": f"demo:{self.code}", "service_id": svc, "device_id": key,
                               "client_category": cat}, headers={"Idempotency-Key": key})
        if st in (200, 201) and t:
            self.meta[t["ticket_id"]] = {"svc": a["service_code"], "cat": cat,
                                         "noise": float(a.get("noise", 1.0)), "issued": now}
            self.issued += 1
        else:
            self.fail(f"выдача талона {i}: HTTP {st} {str(t)[:120]}")

    def staff(self, target: int, windows: list):
        all_services = list(self.services.values())
        for w in windows[:target]:
            if w not in self.open:
                st, body = self.call("POST", f"/api/v1/windows/{w}/open", {"service_ids": all_services})
                if st == 200:
                    self.open.append(w)
                else:
                    self.fail(f"открытие окна: HTTP {st} {str(body)[:120]}")
        # лишние окна закрываем, когда в них никого нет (окно — это человек, клиента не бросаем)
        for w in list(self.open):
            if windows.index(w) >= target and w not in self.busy:
                st, _ = self.call("POST", f"/api/v1/windows/{w}/close", {"mode": "graceful"})
                if st == 200:
                    self.open.remove(w)

    def work_windows(self, now: datetime, truth: Truth, target: int):
        for w in list(self.open):
            if w in self.busy:
                tid, finish = self.busy[w]
                if now < finish:
                    continue
                st, _ = self.call("POST", f"/api/v1/tickets/{tid}/complete")
                if st != 200:
                    self.fail(f"завершение: HTTP {st}")
                del self.busy[w]
                self.served += 1
            if truth.windows.index(w) >= target:
                continue                                   # окно уходит по смене: новых клиентов не берёт
            if time.monotonic() < self.retry_at.get(w, 0):
                continue
            st, t = self.call("POST", f"/api/v1/windows/{w}/call-next")
            if st != 200 or not t:
                self.retry_at[w] = time.monotonic() + 0.25          # очередь пуста для этого окна
                continue
            tid = t["ticket_id"]
            m = self.meta.get(tid) or {"svc": self.service_code.get((t.get("service") or {}).get("id"), "parcel"),
                                       "cat": t.get("client_category") or "default", "noise": 1.0, "issued": now}
            self.meta[tid] = m
            self.waits.append((now - m["issued"]).total_seconds() / 60.0)
            st, _ = self.call("POST", f"/api/v1/tickets/{tid}/start")
            if st != 200:
                self.fail(f"начало обслуживания: HTTP {st}")
            dur = truth.seconds(m["svc"], m["cat"], w, now.hour, m["noise"])
            self.busy[w] = (tid, now + timedelta(seconds=dur))

    def queue_len(self) -> int:
        return self.issued - len(self.waits)

    def sample(self, now: datetime):
        st, load = self.call("GET", "/api/v1/admin/load", admin=True)
        if st != 200 or not load:
            return None
        level = load.get("level")
        causes = [x.get("code") for x in load.get("causes") or []]
        row = (now, level, load.get("clear_time_min"), load.get("ratio_now"), len(self.open), self.queue_len(), causes)
        self.samples.append(row)
        if not self.levels or self.levels[-1][1] != level:
            prev = self.levels[-1][1] if self.levels else None
            self.levels.append((now, level))
            if prev is not None:
                why = ", ".join(CAUSE_RU.get(x, x) for x in causes) or "показатели вернулись в норму"
                say(f"   {c('1', self.code)} {now.astimezone(self.tz):%H:%M}  уровень {level_txt(prev)} → "
                    f"{level_txt(level)}  {c('2', '(' + why + ')')}")
        return row

    def fail(self, text: str):
        self.errors += 1
        self.last_error = text
        if len(self.error_kinds) < 3 and text[:60] not in [k[:60] for k in self.error_kinds]:
            self.error_kinds.append(text)

    # ---------------------------------------------------------------- поток отделения

    def run(self, clock: VirtualClock, arrivals: list, truth: Truth, end: datetime, stop: threading.Event,
            sample_every: timedelta):
        idx = 0
        next_sample = clock.now()
        while not stop.is_set():
            now = clock.now()
            if now >= end:
                break
            target = self.plan.get(now.astimezone(self.tz).hour, 0)
            self.staff(target, truth.windows)
            while idx < len(arrivals) and arrivals[idx][0] <= now:
                self.issue(idx + 1, arrivals[idx][1], now)
                idx += 1
            self.work_windows(now, truth, target)
            self.max_queue = max(self.max_queue, self.queue_len())
            if now >= next_sample:
                self.sample(now)
                next_sample = now + sample_every
            time.sleep(self.args.tick)

    # ---------------------------------------------------------------- итоги

    def hourly_levels(self, end: datetime) -> str:
        """Ход уровня для подписи прогона: худший уровень каждого часа (load-planning.md 7.2).

        Минута смены уровня плавает между прогонами на такт проигрывателя, а худший уровень часа — нет:
        по нему три прогона подряд сравниваются честно, без ложных расхождений на дрожании таймингов.
        """
        rank = {"normal": 0, "high": 1, "overload": 2}
        worst = {}
        for i, (at, lv) in enumerate(self.levels):
            until = self.levels[i + 1][0] if i + 1 < len(self.levels) else end
            h = at.astimezone(self.tz).replace(minute=0, second=0, microsecond=0)
            while h < until:
                key = h.hour
                if rank.get(lv, -1) > rank.get(worst.get(key), -1):
                    worst[key] = lv
                h += timedelta(hours=1)
        return ",".join(f"{h}:{worst[h]}" for h in sorted(worst))

    def minutes_at(self, level: str, end: datetime) -> int:
        total = 0.0
        for i, (at, lv) in enumerate(self.levels):
            until = self.levels[i + 1][0] if i + 1 < len(self.levels) else end
            if lv == level:
                total += (until - at).total_seconds() / 60.0
        return round(total)


# ---------------------------------------------------------------------------------------------- МП


def mp_book(mp: str, branch: str, slot: datetime, appointment_id: str) -> str:
    st, _ = http("POST", mp + "/send", {"branch_id": branch, "service_code": "finance", "slot_start": iso(slot),
                                         "client_category": "pensioner", "mp_appointment_id": appointment_id})
    if st not in (200, 201, 202):
        return f"не отправлено (HTTP {st})"
    return "sent"


def mp_result(mp: str, appointment_id: str) -> str | None:
    st, body = http("GET", f"{mp}/callbacks/{appointment_id}")
    if st != 200 or not body:
        return None
    b = body.get("body") or {}
    if b.get("result") == "confirmed":
        return "confirmed"
    return "rejected:" + str(b.get("reason") or "?")


# ---------------------------------------------------------------------------------------------- сценарий


def load_arrivals(trace: dict, day, tz, start: datetime, end: datetime) -> list:
    out = []
    for a in trace["arrivals"]:
        t = datetime.fromisoformat(a["at"])
        local = datetime(day.year, day.month, day.day, t.hour, t.minute, t.second, t.microsecond, tzinfo=tz)
        if start <= local < end:
            out.append((local, a))
    out.sort(key=lambda x: x[0])
    return out


def hhmm(text: str):
    h, m = text.split(":")
    return int(h), int(m)


def main() -> int:
    root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    sc = os.path.join(root, "infrastructure", "demo", "scenarios")
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--trace", default=os.path.join(sc, "scenario-pension.json"))
    p.add_argument("--planning", default=os.path.join(sc, "planning.json"))
    p.add_argument("--node", default=os.environ.get("NODE", "http://localhost:8080"))
    p.add_argument("--node2", default=os.environ.get("NODE2", "http://localhost:8090"))
    p.add_argument("--branch", default=os.environ.get("BRANCH", "b01"))
    p.add_argument("--branch2", default=os.environ.get("BRANCH2", "b02"))
    p.add_argument("--center", default=os.environ.get("CENTER", "http://localhost:8081"))
    p.add_argument("--mp", default=os.environ.get("MP", "http://localhost:8096"))
    p.add_argument("--rate", type=float, default=float(os.environ.get("CLOCK_RATE") or 60),
                   help="скорость демо-часов: 60 — минута за секунду (по умолчанию), 180 — три минуты за секунду")
    p.add_argument("--start", default=os.environ.get("AB_START", "08:50"), help="начало показа по часам отделения")
    p.add_argument("--end", default=os.environ.get("AB_END", "12:30"), help="конец показа")
    p.add_argument("--pace", type=float, default=float(os.environ.get("PACE") or 1.2),
                   help="пауза между утренними кадрами, с (FAST=1 — без пауз)")
    p.add_argument("--tick", type=float, default=0.05, help="шаг цикла проигрывателя, с реального времени")
    p.add_argument("--no-mp", action="store_true", help="не показывать запись из МП")
    p.add_argument("--leave-clock", action="store_true",
                   help="не останавливать демо-часы в конце (по умолчанию ставятся на конец показа со скоростью ×1)")
    p.add_argument("--events", default=None)
    p.add_argument("--checks", default=None)
    p.add_argument("--facts", default=None)
    p.add_argument("--report", default=os.path.join(root, "reports", "demo", "ab-report.md"))
    p.add_argument("--operator-password", default=os.environ.get("DEMO_OPERATOR_PASSWORD") or "operator123")
    p.add_argument("--admin-password", default=os.environ.get("DEMO_ADMIN_PASSWORD") or "admin123")
    args = p.parse_args()
    fast = os.environ.get("FAST") == "1"

    def pause(k=1.0):
        if not fast:
            time.sleep(args.pace * k)

    trace = json.load(open(args.trace, encoding="utf-8"))
    planning = json.load(open(args.planning, encoding="utf-8"))["planning"]
    utilization = float(planning.get("utilization_target", 0.85))
    truth = Truth(trace)
    run_id = str(int(time.time()))

    a = Node(args.branch, args.node, args, run_id)
    b = Node(args.branch2, args.node2, args, run_id)
    nodes = (a, b)
    for n in nodes:
        n.login()
    tz = a.tz
    today = datetime.now(tz).date()
    sh, sm = hhmm(args.start)
    eh, em = hhmm(args.end)
    start = datetime(today.year, today.month, today.day, sh, sm, tzinfo=tz)
    end = datetime(today.year, today.month, today.day, eh, em, tzinfo=tz)
    arrivals = load_arrivals(trace, today, tz, start, end)

    events, checks, facts = [], [], []

    def check(name, ok, detail=""):
        checks.append(("PASS " if ok else "FAIL ") + name + ("" if ok else f" ({detail})"))
        mark = c("32", "✔") if ok else c("31", "✘")
        say(f"   {mark} {name}" + ("" if ok else c("31", f" — {detail}")))

    def fact(k, v):
        facts.append(f"{k}={v}")

    # ------------------------------------------------------------ утро
    for n in nodes:
        n.set_clock(start, 1.0)                        # утро идёт в реальном темпе: успеваем показать и записать
    say(c("1;36", f"\n1. Утро {today:%d.%m}, {args.start}. Одна и та же трасса дня на двух отделениях"))
    say(c("2", f"   Трасса: {len(arrivals)} клиентов с {args.start} до {args.end}, на 30 % больше, чем в профиле «пенсионные выплаты»."))
    say(c("2", f"   {a.code}: ни модели, ни истории — прогноз на константе 300 с.   {b.code}: шесть недель истории и модель."))
    pause(2)

    for n in nodes:
        n.read_plan(utilization, len(truth.windows))
    hours = [h for h in sorted(set(a.plan) | set(b.plan)) if start.hour <= h <= end.hour]
    say(c("1;36", "\n2. Прогноз дня и смена: сколько окон руководитель ставит по прогнозу своего отделения"))
    say(c("2", "   Смотрите: админка → «Нагрузка», таблица «работа и мощность по часам»"))
    head = "   час         " + "".join(f"{h:>6}" for h in hours)
    say(head)
    for n in nodes:
        basis = "модель" if n.basis == "model" else "300 с"
        works = "".join(f"{round(float((n.work_at(h) or {}).get('work_min', 0))):>6}" for h in hours)
        say(f"   {n.code} работа, мин{works}   ({basis})")
    for n in nodes:
        say(f"   {c('1', n.code)} окон       " + "".join(f"{n.plan.get(h, 0):>6}" for h in hours))
    wh = {n.code: sum(n.plan.get(h, 0) for h in hours) for n in nodes}
    fact(f"окно-часов в смене {a.code}", wh[a.code])
    fact(f"окно-часов в смене {b.code}", wh[b.code])
    for n in nodes:
        events.append(f"plan|{n.code}|" + ",".join(f"{h}:{n.plan.get(h, 0)}" for h in hours))
    say(c("2", f"   Константа 300 с занижает работу: {a.code} ставит {wh[a.code]} окно-часов, {b.code} — {wh[b.code]}."))
    pause(3)

    date = today.isoformat()
    say(c("1;36", "\n3. Запись на сегодня: где система закрыла запись заранее"))
    say(c("2", "   Смотрите: экран клиента → «Записаться»; ответ центра GET /branches/{b}/booking-limits"))
    for n in nodes:
        n.read_limits(args.center, date)
        closed = [x for x in n.limits if float(x.get("quota", 1)) <= 0]
        limited = [x for x in n.limits if 0 < float(x.get("quota", 1)) < 1]

        def span(rows):
            return ", ".join(f"{parse_instant(x['hour_start']).astimezone(tz):%H}:00" for x in rows) or "—"
        say(f"   {c('1', n.code)}: закрыта на {span(closed)};  ограничена наполовину на {span(limited)}")
        events.append(f"limits|{n.code}|closed={span(closed)}|half={span(limited)}")
    fin = a.services.get("finance")
    if fin:
        s1, s2 = a.free_slots(args.center, fin, date, 10), b.free_slots(args.center, b.services["finance"], date, 10)
        say(f"   свободных слотов «платежи» на 10:00:  {a.code} — {s1},  {b.code} — {s2}")
    pause(2)

    if not args.no_mp:
        say(c("1;36", "\n4. Мобильное приложение записывает пенсионера на 10:00 и на 15:00 в оба отделения"))
        say(c("2", "   Смотрите: центр /network → «МП»; mp-simulator /callbacks"))
        st, _ = http("GET", args.mp + "/health")
        if st != 200:
            check("mp-simulator отвечает", False, f"HTTP {st} на {args.mp}/health")
        else:
            ids = {}
            for n in nodes:
                for hh in (10, 15):
                    slot = datetime(today.year, today.month, today.day, hh, 0, tzinfo=tz)
                    aid = f"ab-{run_id}-{n.code}-{hh}"
                    ids[(n.code, hh)] = aid
                    sent = mp_book(args.mp, n.code, slot, aid)
                    if sent != "sent":
                        n.bookings[f"{hh}:00"] = sent
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                for (code, hh), aid in ids.items():
                    n = a if code == a.code else b
                    if f"{hh}:00" not in n.bookings:
                        r = mp_result(args.mp, aid)
                        if r:
                            n.bookings[f"{hh}:00"] = r
                if all(f"{hh}:00" in (a if code == a.code else b).bookings for (code, hh) in ids):
                    break
                time.sleep(0.5)
            for n in nodes:
                for hh in (10, 15):
                    r = n.bookings.setdefault(f"{hh}:00", "нет ответа")
                    txt = c("32", "подтверждена") if r == "confirmed" else c("31", r.replace("rejected:", "отказ "))
                    say(f"   {c('1', n.code)} {hh}:00 → {txt}")
                    events.append(f"booking|{n.code}|{hh}:00|{r}")
            check(f"{a.code}: запись на 10:00 прошла — без прогноза отделение не видит перегрузки",
                  a.bookings.get("10:00") == "confirmed", a.bookings.get("10:00", ""))
            check(f"{b.code}: запись на 10:00 отклонена BOOKING_LIMITED — час перегружен по прогнозу",
                  b.bookings.get("10:00") == "rejected:BOOKING_LIMITED", b.bookings.get("10:00", ""))
            check("запись на 15:00 подтверждена в обоих отделениях — спокойный час открыт",
                  a.bookings.get("15:00") == "confirmed" and b.bookings.get("15:00") == "confirmed",
                  f"{a.bookings.get('15:00')}, {b.bookings.get('15:00')}")
        pause(2)

    # ------------------------------------------------------------ день
    say(c("1;36", f"\n5. Рабочий день: {args.start}–{args.end}, демо-часы ×{args.rate:g} "
                  f"(≈{(end - start).total_seconds() / args.rate / 60:.1f} мин)"))
    say(c("2", "   Смотрите: центр /network (плитки рядом), табло :8080/board и :8090/board, админка → «Нагрузка», пульт /demo"))
    clock = VirtualClock(start, args.rate)
    for n in nodes:
        n.set_clock(clock.now(), args.rate)
    stop = threading.Event()
    sample_every = timedelta(minutes=2)
    threads = [threading.Thread(target=n.run, args=(clock, arrivals, truth, end, stop, sample_every), daemon=True)
               for n in nodes]
    for t in threads:
        t.start()

    def row(n: Node):
        s = n.samples[-1] if n.samples else None
        lvl = level_txt(s[1]) if s else "—"
        clear = f"{s[2]:.0f} мин" if s and s[2] is not None else "—"
        return f"{c('1', n.code)} окон {len(n.open)} · очередь {n.queue_len():>3} · {lvl:<22} разгрузка {clear:>7}"

    next_print = start
    try:
        while any(t.is_alive() for t in threads):
            now = clock.now()
            if now >= next_print:
                say(f"   {now.astimezone(tz):%H:%M} │ {row(a)} │ {row(b)}")
                next_print += timedelta(minutes=15)
            time.sleep(0.1)
    except KeyboardInterrupt:
        stop.set()
        say(c("33", "\n   Остановлено вручную."))
    for t in threads:
        t.join(timeout=5)
    for n in nodes:
        n.sample(min(clock.now(), end))
    if not args.leave_clock:
        for n in nodes:
            n.set_clock(end, 1.0)                     # экраны остаются «в конце показа», время почти стоит

    # ------------------------------------------------------------ итоги
    say(c("1;36", f"\n6. Итоги к {args.end}"))

    def stats(n: Node):
        waits = sorted(n.waits)
        avg = sum(waits) / len(waits) if waits else 0.0
        p90 = waits[int(0.9 * (len(waits) - 1))] if waits else 0.0
        return {
            "served": n.served, "called": len(waits), "issued": n.issued, "left": n.queue_len(),
            "avg": avg, "p90": p90, "max": waits[-1] if waits else 0.0, "max_queue": n.max_queue,
            "overload_min": n.minutes_at("overload", end),
            "levels": " → ".join(LEVEL_RU.get(lv, lv) for _, lv in n.levels),
            "errors": n.errors,
        }

    sa, sb = stats(a), stats(b)
    rows = [
        ("Окно-часов в смене (по прогнозу)", wh[a.code], wh[b.code]),
        ("Клиентов пришло", sa["issued"], sb["issued"]),
        ("Вызвано к окну", sa["called"], sb["called"]),
        ("Осталось в очереди к концу", sa["left"], sb["left"]),
        ("Пик очереди", sa["max_queue"], sb["max_queue"]),
        ("Среднее ожидание, мин", f"{sa['avg']:.0f}", f"{sb['avg']:.0f}"),
        ("90 % клиентов ждали не дольше, мин", f"{sa['p90']:.0f}", f"{sb['p90']:.0f}"),
        ("Максимальное ожидание, мин", f"{sa['max']:.0f}", f"{sb['max']:.0f}"),
    ]
    if not args.no_mp:
        def booking_ru(r):
            return "подтверждена" if r == "confirmed" else ("отказ " + r.split(":", 1)[1] if r.startswith("rejected:") else r)
        rows.append(("Запись из МП на 10:00", booking_ru(a.bookings.get("10:00", "—")),
                     booking_ru(b.bookings.get("10:00", "—"))))
    w0 = max(len(r[0]) for r in rows)
    say(f"   {'':<{w0}}  {a.code + ' без прогноза':>18}  {b.code + ' с прогнозом':>18}")
    for name, x, y in rows:
        say(f"   {name:<{w0}}  {str(x):>18}  {str(y):>18}")
    say(c("2", f"   Ход уровня {a.code}: {sa['levels']}"))
    say(c("2", f"   Ход уровня {b.code}: {sb['levels']}"))

    for n, s in ((a, sa), (b, sb)):
        fact(f"среднее ожидание {n.code}, мин", f"{s['avg']:.1f}")
        fact(f"макс. ожидание {n.code}, мин", f"{s['max']:.1f}")
        fact(f"пик очереди {n.code}", s["max_queue"])
        fact(f"осталось в очереди {n.code}", s["left"])
        events.append(f"levels|{n.code}|" + n.hourly_levels(end))

    check(f"прогноз {b.code} ставит больше окон, чем константа {a.code}", wh[b.code] > wh[a.code],
          f"{wh[b.code]} против {wh[a.code]}")
    check(f"клиенты {b.code} ждут меньше, чем {a.code}", sb["avg"] < sa["avg"], f"{sb['avg']:.0f} против {sa['avg']:.0f} мин")
    check(f"к концу показа очередь {b.code} короче", sb["left"] < sa["left"], f"{sb['left']} против {sa['left']}")
    for n, s in ((a, sa), (b, sb)):
        if s["errors"]:
            say(c("33", f"   {n.code}: {s['errors']} ошибок API, последняя: {n.last_error}"))
            fact(f"ошибки API проигрывателя {n.code}", f"{s['errors']}: " + " | ".join(n.error_kinds))
    check("проигрыватель отработал без ошибок API", sa["errors"] + sb["errors"] == 0,
          f"{sa['errors'] + sb['errors']} ошибок")

    write_report(args, today, a, b, sa, sb, rows, hours, wh)
    say(c("2", f"   Отчёт: {os.path.relpath(args.report, root)}"))

    for path, lines in ((args.events, events), (args.checks, checks), (args.facts, facts)):
        if path:
            with open(path, "a", encoding="utf-8") as f:
                for line in lines:
                    f.write(line + "\n")
    return 0 if all(x.startswith("PASS") for x in checks) else 1


def write_report(args, today, a: Node, b: Node, sa: dict, sb: dict, rows: list, hours: list, wh: dict):
    tz = a.tz
    lines = [
        "# Демо A/B: прогноз против его отсутствия",
        "",
        f"Прогон {datetime.now(timezone.utc):%Y-%m-%d %H:%M} UTC, трасса «пенсионные выплаты» "
        f"(на 30 % больше клиентов, чем в профиле), показ {args.start}–{args.end}, демо-часы ×{args.rate:g}. "
        "Создаётся `./scripts/demo/run.sh pension-ab`.",
        "",
        f"- **{a.code} — без прогноза:** ни модели, ни истории; работа часа считается по константе 300 с.",
        f"- **{b.code} — с прогнозом:** шесть недель истории и опубликованная модель длительности.",
        "- Одна и та же трасса клиентов; длительность обслуживания — из «правды мира», одинаковая для обоих.",
        "- Единственное правило разницы: руководитель утром ставит смену по прогнозу своего отделения.",
        "",
        "## Итог",
        "",
        f"| | {a.code}, без прогноза | {b.code}, с прогнозом |",
        "|---|---:|---:|",
    ]
    for name, x, y in rows:
        lines.append(f"| {name} | {x} | {y} |")
    lines += ["", "## Смена по прогнозу, окон по часам", "",
              "| Час | " + " | ".join(f"{h:02d}" for h in hours) + " |",
              "|---|" + "---:|" * len(hours)]
    for n in (a, b):
        lines.append(f"| {n.code} | " + " | ".join(str(n.plan.get(h, 0)) for h in hours) + " |")
    lines += ["", "## Как шёл день (каждые 30 минут)", "",
              f"| Время | {a.code}: окон / очередь / уровень | {b.code}: окон / очередь / уровень |",
              "|---|---|---|"]

    def at(n: Node, moment):
        best = None
        for s in n.samples:
            if s[0] <= moment:
                best = s
        if not best:
            return "—"
        return f"{best[4]} / {best[5]} / {LEVEL_RU.get(best[1], best[1])}"
    t0 = a.samples[0][0] if a.samples else None
    if t0:
        m = t0.astimezone(tz).replace(second=0, microsecond=0)
        m = m.replace(minute=0 if m.minute < 30 else 30) + timedelta(minutes=30)
        last = a.samples[-1][0]
        while m <= last:
            if not at(a, m).startswith("0 /") or not at(b, m).startswith("0 /"):   # до открытия окон — не строка
                lines.append(f"| {m:%H:%M} | {at(a, m)} | {at(b, m)} |")
            m += timedelta(minutes=30)
    lines += ["", f"Ход уровня {a.code}: {sa['levels']}.  ", f"Ход уровня {b.code}: {sb['levels']}.", ""]
    os.makedirs(os.path.dirname(args.report), exist_ok=True)
    with open(args.report, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))


if __name__ == "__main__":
    sys.exit(main())
