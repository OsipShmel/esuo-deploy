#!/usr/bin/env python3
"""mp-simulator: Post of Russia mobile app stand-in (design doc 4.6, 13.2). Standard library only.

Server (default):
  POST /send       {branch_id, service_code, slot_start | in_minutes, client_category?, client_ref?, mp_appointment_id?}
                   signs and sends a webhook to the center, returns {request, status, response}
  POST /callback   center callback; signature is verified (401 on mismatch); behaviour by chaos mode
  GET  /callbacks  received callbacks, newest first; GET /callbacks/<mp_appointment_id> the last one for an id
  GET|POST /chaos  callback mode: ok | error | slow | timeout | reject
  GET  /health
CLI:
  python simulator.py send --branch b01 --service parcel --in-minutes 90 [--id mp-1] [--category pensioner]
"""
import argparse
import hashlib
import hmac
import json
import os
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from collections import deque
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

MODES = ("ok", "error", "slow", "timeout", "reject")
PORT = int(os.environ.get("PORT", "8091"))
CENTER_URL = os.environ.get("CENTER_URL", "http://localhost:8081").rstrip("/")
SECRET = os.environ.get("MP_WEBHOOK_SECRET", "")
TOLERANCE_SEC = 300

state = {"mode": os.environ.get("MODE", "ok")}
callbacks = deque(maxlen=1000)
lock = threading.Lock()


def iso(dt):
    return dt.astimezone(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def sign(ts, body):
    return "sha256=" + hmac.new(SECRET.encode(), ts.encode() + b"." + body, hashlib.sha256).hexdigest()


def send_webhook(req):
    """Builds, signs and posts one webhook. Returns (request, http status, parsed response)."""
    if "slot_start" not in req or not req["slot_start"]:
        minutes = int(req.get("in_minutes", 60))
        slot = datetime.now(timezone.utc) + timedelta(minutes=minutes)
        slot = slot.replace(minute=(slot.minute // 15) * 15, second=0, microsecond=0)   # slots are 15 min
        req["slot_start"] = iso(slot)
    payload = {
        "mp_appointment_id": req.get("mp_appointment_id") or "mp-" + uuid.uuid4().hex[:12],
        "branch_id": req["branch_id"],
        "service_code": req["service_code"],
        "slot_start": req["slot_start"],
        "client_category": req.get("client_category", "default"),
        "client_ref": req.get("client_ref") or "mp-client-" + uuid.uuid4().hex[:8],
    }
    body = json.dumps(payload, ensure_ascii=False).encode()
    ts = req.get("timestamp") or str(int(time.time()))
    signature = req.get("signature") or sign(ts, body)
    http_req = urllib.request.Request(CENTER_URL + "/api/v1/webhooks/mp/appointments", data=body, method="POST",
                                      headers={"Content-Type": "application/json", "X-Timestamp": ts,
                                               "X-Signature": signature})
    try:
        with urllib.request.urlopen(http_req, timeout=10) as r:
            return payload, r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return payload, e.code, json.loads(raw)
        except ValueError:
            return payload, e.code, {"raw": raw.decode(errors="replace")}
    except (urllib.error.URLError, OSError) as e:
        return payload, 0, {"error": str(e)}


class Handler(BaseHTTPRequestHandler):
    server_version = "mp-simulator/1"

    def log_message(self, fmt, *args):
        print("%s %s" % (iso(datetime.now(timezone.utc)), fmt % args), flush=True)

    def reply(self, status, body):
        data = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def raw(self):
        length = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(length) if length else b""

    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/health":
            return self.reply(200, {"status": "UP", "mode": state["mode"], "center": CENTER_URL, "secret": bool(SECRET)})
        if path == "/chaos":
            return self.reply(200, {"mode": state["mode"], "modes": MODES})
        if path == "/callbacks":
            with lock:
                return self.reply(200, list(callbacks)[::-1])
        if path.startswith("/callbacks/"):
            key = path.split("/", 2)[2]
            with lock:
                found = [c for c in callbacks if c["body"].get("mp_appointment_id") == key]
            return self.reply(200, found[-1]) if found else self.reply(404, {"error": "no callback yet"})
        self.reply(404, {"error": "not found"})

    def do_POST(self):
        path = urlparse(self.path).path
        raw = self.raw()
        if path == "/chaos":
            mode = (json.loads(raw or b"{}") or {}).get("mode")
            if mode not in MODES:
                return self.reply(400, {"error": "mode: " + "|".join(MODES)})
            state["mode"] = mode
            return self.reply(200, {"mode": mode})
        if path == "/send":
            try:
                req = json.loads(raw or b"{}")
                req["branch_id"], req["service_code"]
            except (ValueError, KeyError):
                return self.reply(400, {"error": "branch_id and service_code are required"})
            payload, status, response = send_webhook(req)
            return self.reply(200, {"request": payload, "status": status, "response": response})
        if path != "/callback":
            return self.reply(404, {"error": "not found"})

        ts = self.headers.get("X-Timestamp", "")
        valid = ts.isdigit() and abs(time.time() - int(ts)) <= TOLERANCE_SEC \
            and hmac.compare_digest(sign(ts, raw), self.headers.get("X-Signature", ""))
        if not valid:
            return self.reply(401, {"error": "bad signature"})
        mode = state["mode"]
        if mode == "error":
            return self.reply(503, {"error": "mp unavailable (chaos)"})
        if mode == "reject":
            return self.reply(400, {"error": "rejected (chaos)"})
        if mode in ("slow", "timeout"):
            time.sleep(3 if mode == "slow" else 30)
        body = json.loads(raw or b"{}")
        with lock:
            callbacks.append({"received_at": iso(datetime.now(timezone.utc)), "body": body})
        print("callback %s %s %s" % (body.get("mp_appointment_id"), body.get("result"),
                                     (body.get("ticket") or {}).get("status_url") or body.get("reason")), flush=True)
        self.reply(200, {"accepted": True})


def cli():
    p = argparse.ArgumentParser(description="mp-simulator")
    sub = p.add_subparsers(dest="cmd")
    s = sub.add_parser("send", help="send one signed webhook to the center")
    s.add_argument("--branch", required=True)
    s.add_argument("--service", required=True, help="service code, e.g. parcel")
    s.add_argument("--in-minutes", type=int, default=60, help="slot start from now (negative = past slot)")
    s.add_argument("--slot", help="exact slot_start, ISO-8601 UTC")
    s.add_argument("--id", help="mp_appointment_id (default random)")
    s.add_argument("--category", default="default")
    args = p.parse_args()
    if args.cmd != "send":
        print("mp-simulator on :%d, center=%s, secret=%s" % (PORT, CENTER_URL, "set" if SECRET else "EMPTY"), flush=True)
        ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
        return
    payload, status, response = send_webhook({"branch_id": args.branch, "service_code": args.service,
                                              "in_minutes": args.in_minutes, "slot_start": args.slot,
                                              "mp_appointment_id": args.id, "client_category": args.category})
    print(json.dumps({"request": payload, "status": status, "response": response}, ensure_ascii=False, indent=2))
    sys.exit(0 if 200 <= status < 300 else 1)


if __name__ == "__main__":
    cli()
