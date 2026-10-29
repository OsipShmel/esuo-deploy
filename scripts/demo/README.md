# Demo scenarios

Scripted business days on the local environment (`compose.yaml`) or staging. These are **not tests**: a scenario
plays a plausible day so that what happens is visible on the screens (board, operator, manager, client, network
overview) and repeatable. Each step is narrated in the terminal with what to look at. Regression coverage lives in the
test suites and `scripts/smoke`.

## Running

```bash
docker compose up -d --build              # needs .env with DEMO_DATA=true and DEMO_MODE=true
./scripts/demo/run.sh doctor              # environment check, changes nothing
./scripts/demo/run.sh list                # all scenarios
./scripts/demo/run.sh normal-day          # one scenario (PACE=1.2 s between steps)
./scripts/demo/run.sh normal-day --fast   # no pauses
./scripts/demo/run.sh repeat normal-day 3 # three runs, compares the event signatures
./scripts/demo/run.sh all [--with-chaos]
./scripts/demo/run.sh normal              # return the environment to normal mode after pension-ab
```

Requires `curl`, `jq`, `python3`; `analytics` also needs Java 25, and `link-cut` / `notify-down` need Docker.
Passwords come from `.env` (`DEMO_OPERATOR_PASSWORD`, `DEMO_ADMIN_PASSWORD`, `CENTER_ADMIN_PASSWORD`), addresses from
`NODE`, `NODE2`, `CENTER`, `MP`, `NOTIFY` (defaults match the local environment).

With `DEMO_MODE=true` scenarios do not depend on the time of day: `three-sources` sets the clock to 10:05,
`normal-day` and `pension-day` run at ×60 and ×180 so the queue ages and overdue tickets and recommendations appear.
The clock returns to real time after a scenario.

| Code | Shows | Watch |
|---|---|---|
| `normal-day` | Visitor flow, three windows on two nodes | board, operator, `/network` |
| `three-sources` | Booking via the center, QR, walk-in — one queue; call reason from the journal | operator, client |
| `mp` | Mobile-app webhook → ticket → callback; replay without duplicates; rejection | center: mobile-app requests |
| `disputes` | Graceful/force window close, redirect, no-show after recalls, requeue to tail | board, operator |
| `link-cut` | Link outage mid-day, autonomous operation, event catch-up | `/network`, board |
| `notify-down` | Notification provider outage: breaker opens, DLQ grows, recovery, DLQ retry (~4 min) | `/network` → Notifications |
| `monitoring` | Branch monitoring: error in Grafana within seconds, logs from the outage arrive with their timestamps, full logs on request, metrics history, restart rule (needs `--profile monitoring`) | Grafana, `/network` → Anomalies |
| `health` | Branch PC faults: CPU starvation, stuck DB lock (branch stays online, "queue data not updating", snapshot shows the lock), `kill -9` during an outage (needs `--profile monitoring`) | `/network` → node row and anomalies |
| `analytics` | 14 days of history → manager reports are populated | manager → Analytics |
| `pension-day` | Peak: 45 % pensioners, growing queue, overdue tickets | manager, operator |
| `pension-ab` | The same pension-day trace on b01 (no model, no history) and b02 (with both): shift by each branch's own forecast, mobile-app bookings at 10:00 (b02 answers `BOOKING_LIMITED`) and 15:00. Prepares the environment itself (`ab-prepare.sh`) | `/network`, both boards, manager → Load |

## Scenario console

```bash
docker compose --profile demo up -d --build   # starts demo-runner next to the environment
```

The "Scenarios" tab on `/demo` (manager login) lists scenarios with Run / Stop and a live step log with ✔/✘ counters.
It runs the same `run.sh`.

- One run at a time; a second start is rejected.
- Stop interrupts the run and restores the environment (branch links, `notify-mock` mode).
- Needs `DEMO_MODE=true` on the nodes. The console is not published: the browser goes through a node, and the node
  presents `DEMO_RUNNER_TOKEN` (defaults to `JWT_SECRET`).
- `demo-runner` holds the Docker socket, so it only exists in the `demo` profile and in staging.

## Speed

`--rate N` (or `CLOCK_RATE=N`) sets the demo clock for scenarios with a working day; `--pace S` sets the pause between
steps.

```bash
./scripts/demo/run.sh pension-ab --rate 120
./scripts/demo/run.sh pension-ab --rate 60 --from 09:00 --to 11:00
./scripts/demo/run.sh normal-day --rate 90 --pace 0.5
CLOCK_RATE=120 ./scripts/demo/run.sh repeat pension-ab 3
```

Defaults: `normal-day` and `pension-ab` ×60, `pension-day` ×180. `link-cut` and `notify-down` run on real time.

## Repeatability

The event sequence (arrival, call by window, command) names visitors by labels `c01…` rather than ticket numbers and
has no timestamps; its hash is the run's signature. The visitor trace is deterministic for a given `SEED` (default 42)
and branches are reset before a run. `repeat` runs a scenario N times and compares signatures. Compare runs of the same
mode only: fast and paced runs age the queue differently, so decisions may differ.

## Output

Written to `reports/` (not tracked):

- `reports/demo/runs.md` — one line per run: time, commit, scenario, checks, signature;
- `reports/demo/runs/*.json` — full run: facts, checks, event sequence;
- `reports/demo/repeatability.md` — `repeat` verdicts;
- `reports/demo/ab-report.md` — the latest `pension-ab` report;
- `reports/ml-report.md` — duration model report (`cd python-ai-service && uv run esuo-ml report`).

## Several machines

`NODE=http://<branch-ip>:8080 CENTER=http://<center-ip>:8081 ./scripts/demo/run.sh three-sources`. For `link-cut`
set `CUT_CMD` to a command that accepts `cut` or `restore` (for example, stopping and starting the branch's
`nats-leaf` over ssh). `NODE2` is required by `normal-day` and `pension-day`.

For `pension-ab` across machines, prepare the data on the branch host and run on the center host:

```bash
# branch host
NODE=http://localhost:8082 NODE2=http://localhost:8083 CENTER=http://<center-ip>:8081 \
PG_B01=esuo-branch-b01-postgres-1 PG_B02=esuo-branch-b02-postgres-1 ./scripts/demo/ab-prepare.sh
# center host
AB_SKIP_PREPARE=1 NODE=http://<branch-ip>:8082 NODE2=http://<branch-ip>:8083 ./scripts/demo/run.sh pension-ab
```

## Not covered

`pension-day` shows the overload itself (growing queue, overdue tickets, the open-window recommendation); the system's
response to it — load level, booking closure, honest wait — is shown by `pension-ab`. Late appointments
(`convert_to_qr` / `reject`) are covered by tests, not scenarios.
