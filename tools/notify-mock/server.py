#!/usr/bin/env python3
"""notify-mock: demo notification provider (design doc 11.1, check #8). Standard library only.

POST /notify           accept a notification (Idempotency-Key header = dedup_key); behaviour depends on the chaos mode
GET  /chaos            current mode
POST /chaos            {"mode": "ok|error|slow|timeout|reject"} or ?mode=...; "slow" waits SLOW_SEC, "timeout" TIMEOUT_SEC
GET  /messages         accepted notifications, newest first (?limit=100); DELETE /messages clears them
GET  /health
"""
import json
import os
import threading
import time
from collections import deque
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

MODES = ("ok", "error", "slow", "timeout", "reject")
PORT = int(os.environ.get("PORT", "8090"))
SLOW_SEC = float(os.environ.get("SLOW_SEC", "3"))
TIMEOUT_SEC = float(os.environ.get("TIMEOUT_SEC", "30"))

state = {"mode": os.environ.get("MODE", "ok"), "received": 0, "accepted": 0}
messages = deque(maxlen=1000)
seen = {}
lock = threading.Lock()


def now():
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


class Handler(BaseHTTPRequestHandler):
    server_version = "notify-mock/1"

    def log_message(self, fmt, *args):
        print("%s %s" % (now(), fmt % args), flush=True)

    def reply(self, status, body):
        data = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def body(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""
        try:
            return json.loads(raw) if raw else {}
        except ValueError:
            return None

    def do_GET(self):
        url = urlparse(self.path)
        if url.path == "/health":
            return self.reply(200, {"status": "UP", "mode": state["mode"]})
        if url.path == "/chaos":
            return self.reply(200, {"mode": state["mode"], "modes": MODES})
        if url.path == "/messages":
            limit = int(parse_qs(url.query).get("limit", ["100"])[0])
            with lock:
                items = list(messages)[-limit:][::-1]
                return self.reply(200, {"received": state["received"], "accepted": state["accepted"],
                                        "unique": len(seen), "items": items})
        self.reply(404, {"error": "not found"})

    def do_DELETE(self):
        if urlparse(self.path).path == "/messages":
            with lock:
                messages.clear(); seen.clear()
                state["received"] = state["accepted"] = 0
            return self.reply(200, {"cleared": True})
        self.reply(404, {"error": "not found"})

    def do_POST(self):
        url = urlparse(self.path)
        if url.path == "/chaos":
            body = self.body() or {}
            mode = parse_qs(url.query).get("mode", [body.get("mode")])[0]
            if mode not in MODES:
                return self.reply(400, {"error": "mode: " + "|".join(MODES)})
            state["mode"] = mode
            print("%s chaos mode -> %s" % (now(), mode), flush=True)
            return self.reply(200, {"mode": mode})
        if url.path != "/notify":
            return self.reply(404, {"error": "not found"})

        body = self.body()
        mode = state["mode"]
        with lock:
            state["received"] += 1
        if mode == "error":
            return self.reply(503, {"error": "provider unavailable (chaos)"})
        if mode == "reject":
            return self.reply(400, {"error": "rejected (chaos)"})
        if mode == "timeout":
            time.sleep(TIMEOUT_SEC)
        elif mode == "slow":
            time.sleep(SLOW_SEC)
        if body is None:
            return self.reply(400, {"error": "invalid json"})
        key = self.headers.get("Idempotency-Key") or body.get("dedup_key") or ""
        with lock:
            state["accepted"] += 1
            seen[key] = seen.get(key, 0) + 1
            messages.append({"received_at": now(), "idempotency_key": key, "deliveries": seen[key], "message": body})
        self.reply(200, {"id": "demo-%d" % state["accepted"], "duplicate": seen[key] > 1})


if __name__ == "__main__":
    print("notify-mock on :%d, mode=%s" % (PORT, state["mode"]), flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
