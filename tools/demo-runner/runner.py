#!/usr/bin/env python3
"""demo-runner: пульт демо-сценариев (план финала, D5). Только стандартная библиотека.

Запускает те же `scripts/demo/run.sh`, что и человек в терминале, и отдаёт их вывод построчно. Наружу не публикуется:
вкладка «Сценарии» в /demo ходит сюда через ноду (`/api/v1/admin/demo/runner/**`, вход руководителя), а нода
предъявляет общий токен. У контейнера есть сокет Docker — он нужен хаос-сценариям (обрыв связи) и заливке данных,
поэтому сервис включается только на демо-стенде (compose-профиль `demo`).

GET  /health               без токена
GET  /scenarios            сценарии из scripts/demo/scenarios (CODE, TITLE, DESC) и служебные команды run.sh
POST /runs                 {"code": "link-cut", "args": {"fast": true}} — 201; 409, если прогон уже идёт
GET  /runs/current         состояние текущего или последнего прогона (без строк)
POST /runs/current/stop    остановить прогон и вернуть стенд в порядок (связь отделений, режим notify-mock)
GET  /runs/current/stream  SSE: snapshot (состояние и все строки), затем line / state / heartbeat
"""
import hmac
import json
import os
import re
import signal
import subprocess
import threading
import urllib.request
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

PORT = int(os.environ.get("PORT", "8099"))
REPO = Path(os.environ.get("REPO", "/repo"))
TOKEN = os.environ.get("DEMO_RUNNER_TOKEN", "")
RUN_SH = REPO / "scripts" / "demo" / "run.sh"
SCENARIOS = REPO / "scripts" / "demo" / "scenarios"
MAX_LINES = 5000
HEARTBEAT_SEC = 10
STOP_GRACE_SEC = 15

# Служебные команды run.sh, которые полезны с пульта; сценарии читаются из файлов.
TOOLS = [
    {"code": "doctor", "title": "Проверка стенда", "kind": "tool",
     "desc": "Проверяет узлы, центр, демо-режим и данные перед показом. Ничего не меняет."},
    {"code": "normal", "title": "Вернуть обычный режим", "kind": "tool",
     "desc": "Возвращает стенд в обычный режим после сценария pension-ab."},
]
CHAOS = {"link-cut", "notify-down"}
ANSI = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")
HHMM = re.compile(r"^([01]\d|2[0-3]):[0-5]\d$")


def now():
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def meta(path, name):
    for line in path.read_text(encoding="utf-8").splitlines():
        m = re.match(r'^%s="?([^"]*)"?' % name, line)
        if m:
            return m.group(1)
    return ""


def scenarios():
    items = []
    for f in sorted(SCENARIOS.glob("[0-9]*.sh")):
        code = meta(f, "CODE")
        if code:
            items.append({"code": code, "title": meta(f, "TITLE"), "desc": meta(f, "DESC"),
                          "kind": "chaos" if code in CHAOS else "scenario", "range": code == "pension-ab"})
    return items + TOOLS


def classify(text):
    """Вид строки вывода run.sh: по нему пульт считает проверки и выделяет шаги."""
    s = text.strip()
    if s.startswith("✔"):
        return "pass"
    if s.startswith("✘"):
        return "fail"
    if re.match(r"^\d+\. ", s):
        return "step"
    if s.startswith("==") or s.startswith("####"):
        return "title"
    if s.startswith("Смотрите:"):
        return "watch"
    if s.startswith("· "):
        return "fact"
    return "text"


class BadRequest(Exception):
    pass


def argv_for(code, args):
    """Аргументы run.sh из тела запроса: только известные ключи и проверенные значения — в shell ничего не попадает."""
    known = {s["code"]: s for s in scenarios()}
    if code not in known:
        raise BadRequest("Неизвестный сценарий: %s" % code)
    argv = ["bash", str(RUN_SH), code]
    if known[code]["kind"] == "tool":
        return argv
    if not isinstance(args, dict):
        raise BadRequest("args: ожидается объект")

    def number(key, low, high, integer=False):
        value = args[key]
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not low <= value <= high:
            raise BadRequest("%s: число от %s до %s" % (key, low, high))
        return str(int(value)) if integer else ("%g" % value)

    if args.get("fast") is True:
        argv.append("--fast")
    if args.get("pace") is not None:
        argv += ["--pace", number("pace", 0, 10)]
    if args.get("seed") is not None:
        argv += ["--seed", number("seed", 0, 2 ** 31 - 1, integer=True)]
    if args.get("rate") is not None:
        argv += ["--rate", number("rate", 1, 3600)]
    for key in ("from", "to"):
        if args.get(key) is not None:
            if not known[code]["range"] or not isinstance(args[key], str) or not HHMM.match(args[key]):
                raise BadRequest("%s: время ЧЧ:ММ, только для pension-ab" % key)
            argv += ["--" + key, args[key]]
    return argv


class Run:
    def __init__(self, run_id, code, argv):
        self.id = run_id
        self.code = code
        self.argv = argv[2:]
        self.state = "running"          # running | passed | failed | stopped
        self.exit_code = None
        self.started_at = now()
        self.finished_at = None
        self.passed = 0
        self.failed = 0
        self.lines = []
        self.dropped = 0
        self.stop_requested = False
        self.process = None

    def view(self):
        return {"id": self.id, "code": self.code, "args": self.argv[1:], "state": self.state, "exit_code": self.exit_code,
                "started_at": self.started_at, "finished_at": self.finished_at, "pass": self.passed, "fail": self.failed,
                "lines_total": self.dropped + len(self.lines)}


class Runner:
    """Один прогон за раз: сценарии меняют общий стенд, два одновременно испортили бы друг другу ход событий."""

    def __init__(self):
        self.cond = threading.Condition()
        self.current = None
        self.seq = 0

    def start(self, code, argv):
        with self.cond:
            if self.current and self.current.state == "running":
                return None
            self.seq += 1
            run = Run(self.seq, code, argv)
            self.current = run
            self.cond.notify_all()
        env = dict(os.environ, PYTHONUNBUFFERED="1", TERM="dumb")
        try:
            run.process = subprocess.Popen(argv, cwd=str(REPO), env=env, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                           stderr=subprocess.STDOUT, start_new_session=True)
        except OSError as e:
            self.add(run, "Не удалось запустить run.sh: %s" % e, "fail")
            self.finish(run, 127)
            return run
        threading.Thread(target=self.pump, args=(run,), daemon=True).start()
        return run

    def add(self, run, text, kind=None):
        with self.cond:
            kind = kind or classify(text)
            if kind == "pass":
                run.passed += 1
            elif kind == "fail":
                run.failed += 1
            run.lines.append({"n": run.dropped + len(run.lines) + 1, "kind": kind, "text": text})
            if len(run.lines) > MAX_LINES:
                del run.lines[0]
                run.dropped += 1
            self.cond.notify_all()

    def pump(self, run):
        for raw in run.process.stdout:
            text = ANSI.sub("", raw.decode("utf-8", "replace")).rstrip("\r\n")
            if text.strip():
                self.add(run, text)
        code = run.process.wait()
        if run.stop_requested:
            self.cleanup(run)
        self.finish(run, code)

    def finish(self, run, code):
        with self.cond:
            run.exit_code = code
            run.finished_at = now()
            run.state = "stopped" if run.stop_requested else "passed" if code == 0 and run.failed == 0 else "failed"
            self.cond.notify_all()

    def stop(self):
        with self.cond:
            run = self.current
            if not run or run.state != "running" or run.stop_requested:
                return run
            run.stop_requested = True
        self.add(run, "Прогон остановлен с пульта — возвращаем стенд в порядок…", "note")
        threading.Thread(target=self.terminate, args=(run,), daemon=True).start()
        return run

    @staticmethod
    def terminate(run):
        # TERM всей группе: run.sh по trap EXIT возвращает демо-часы; кто не вышел за STOP_GRACE_SEC — KILL.
        try:
            os.killpg(run.process.pid, signal.SIGTERM)
            run.process.wait(STOP_GRACE_SEC)
        except subprocess.TimeoutExpired:
            try:
                os.killpg(run.process.pid, signal.SIGKILL)
            except OSError:
                pass
        except OSError:
            pass

    def cleanup(self, run):
        """Сценарий оборвали посреди хаоса: связь отделений и notify-mock возвращаем сами, не дожидаясь человека."""
        cut = REPO / "scripts" / "chaos" / "cut-branch-link.sh"
        for branch in (os.environ.get("BRANCH", "b01"), os.environ.get("BRANCH2", "b02")):
            try:
                out = subprocess.run(["bash", str(cut), branch, "restore"], cwd=str(REPO), capture_output=True, timeout=30)
                self.add(run, out.stdout.decode("utf-8", "replace").strip() or "связь %s: без изменений" % branch, "note")
            except (OSError, subprocess.SubprocessError) as e:
                self.add(run, "Не удалось восстановить связь %s: %s" % (branch, e), "fail")
        notify = os.environ.get("NOTIFY")
        if notify:
            try:
                req = urllib.request.Request(notify + "/chaos", data=b'{"mode":"ok"}', method="POST",
                                             headers={"Content-Type": "application/json"})
                urllib.request.urlopen(req, timeout=5).close()
                self.add(run, "notify-mock: режим ok", "note")
            except OSError as e:
                self.add(run, "notify-mock не ответил: %s" % e, "note")


runner = Runner()


class Handler(BaseHTTPRequestHandler):
    server_version = "demo-runner/1"
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        print("%s %s" % (now(), fmt % args), flush=True)

    def reply(self, status, body):
        data = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8" if status < 400 else "application/problem+json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def problem(self, status, code, title, detail=""):
        self.reply(status, {"type": "about:blank", "status": status, "code": code, "title": title, "detail": detail})

    def authorized(self):
        given = self.headers.get("X-Runner-Token") or ""
        if TOKEN and hmac.compare_digest(given.encode(), TOKEN.encode()):
            return True
        self.problem(401, "UNAUTHENTICATED", "Нужен токен пульта", "Заголовок X-Runner-Token не совпадает с DEMO_RUNNER_TOKEN.")
        return False

    def body(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""
        try:
            return json.loads(raw) if raw else {}
        except ValueError:
            return None

    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/health":
            return self.reply(200, {"status": "UP", "token_set": bool(TOKEN), "repo": RUN_SH.is_file()})
        if not self.authorized():
            return None
        if path == "/scenarios":
            return self.reply(200, {"scenarios": scenarios()})
        if path == "/runs/current":
            run = runner.current
            return self.reply(200, {"run": run.view() if run else None})
        if path == "/runs/current/stream":
            return self.stream()
        return self.problem(404, "NOT_FOUND", "Ресурс не найден")

    def do_POST(self):
        path = urlparse(self.path).path
        if not self.authorized():
            return None
        if path == "/runs":
            body = self.body()
            if not isinstance(body, dict) or not isinstance(body.get("code"), str):
                return self.problem(400, "VALIDATION_ERROR", "Некорректный запрос", 'Ожидается {"code": "...", "args": {...}}')
            try:
                argv = argv_for(body["code"], body.get("args") or {})
            except BadRequest as e:
                return self.problem(400, "VALIDATION_ERROR", "Некорректный запрос", str(e))
            run = runner.start(body["code"], argv)
            if run is None:
                return self.problem(409, "RUN_IN_PROGRESS", "Сценарий уже идёт",
                                    "Одновременно идёт один прогон: дождитесь конца или остановите его.")
            return self.reply(201, {"run": run.view()})
        if path == "/runs/current/stop":
            run = runner.stop()
            return self.reply(200, {"run": run.view() if run else None})
        return self.problem(404, "NOT_FOUND", "Ресурс не найден")

    def event(self, name, data, event_id=None):
        chunk = "event: %s\n" % name
        if event_id is not None:
            chunk += "id: %s\n" % event_id
        chunk += "data: %s\n\n" % json.dumps(data, ensure_ascii=False)
        self.wfile.write(chunk.encode())
        self.wfile.flush()

    @staticmethod
    def pending(run_id, sent, state):
        """Есть ли что отправить подписчику: другой прогон, новые строки или смена состояния."""
        run = runner.current
        if (run.id if run else None) != run_id:
            return True
        return bool(run) and (run.dropped + len(run.lines) != sent or run.state != state)

    def stream(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True
        run_id, sent, state = -1, 0, None                 # -1: первый проход всегда отдаёт snapshot
        try:
            while True:
                with runner.cond:
                    if not self.pending(run_id, sent, state):
                        runner.cond.wait(HEARTBEAT_SEC)
                    run = runner.current
                    fresh = (run.id if run else None) != run_id
                    view = run.view() if run else None
                    total = view["lines_total"] if view else 0
                    lines = [] if not run else list(run.lines) if fresh else [l for l in run.lines if l["n"] > sent]
                if fresh:
                    run_id = run.id if run else None
                    self.event("snapshot", {"run": view, "lines": lines})
                elif lines or (view and view["state"] != state):
                    for line in lines:
                        self.event("line", line, line["n"])
                    self.event("state", view)
                else:
                    self.event("heartbeat", {})
                sent, state = total, view["state"] if view else None
        except (BrokenPipeError, ConnectionResetError, OSError):
            return None


if __name__ == "__main__":
    if not TOKEN:
        raise SystemExit("Задайте DEMO_RUNNER_TOKEN: без токена пульт не принимает запросы")
    print("%s demo-runner на :%s, репозиторий %s" % (now(), PORT, REPO), flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
