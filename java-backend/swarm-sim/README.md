# swarm-sim

Load generator for the center. One process simulates up to hundreds of thousands of branches and publishes exactly
what a real node publishes, in the same formats and through the same NATS topology.

## Behaviour

- Branches `s{region}-{number}` (e.g. `s1-00042`) across Russian time zones, of different size (×0.4…×2.5 of the
  average flow), with 1–6 windows.
- Visitor flow follows a daily curve with morning ramp-up, lunch and evening peaks. Weekdays 08:00–20:00, Saturday
  09:00–18:00 (×0.7), Sunday closed.
- Full ticket lifecycle as on a node: `ticket.created → called → serving → completed`, about 2 % cancellations and
  3 % no-shows, `window.opened/closed`. With `--notifications`, also `notification.requested`.
- Events are `EventEnvelope`s published to JetStream with `Nats-Msg-Id = event_id` (like `OutboxWorker`). Heartbeats
  every 10 s over core NATS, with the `sys` block and `events_via`.
- The swarm subscribes to `center.config.branch.>` and reports the config version it received in its heartbeats, so
  the center does not keep re-publishing configuration.

## Topology

```
swarm ──▶ nats-region-1..4 (JetStream domains region-1..4, stream REGION_EVT) ──▶ hub ──▶ center
```

The center sees `events_via: "region-N"` in heartbeats and attaches that region's `REGION_EVT` to `CENTER_EVT` once
per region. See the [LLD, regional tier](../../docs/architecture/lld.md#24-regional-tier).

## Running

```bash
docker compose --profile swarm up -d                      # four regional hubs next to the local environment
cd java-backend && ./mvnw -pl swarm-sim -am package -DskipTests
java -jar swarm-sim/target/swarm-sim-0.0.1-SNAPSHOT.jar --branches 40000 --duration 10m
```

As a container (report goes to `reports/load/`; region addresses and the report folder are preset, arguments are
appended):

```bash
docker compose --profile swarm-run run --rm swarm-sim --branches 40000 --duration 10m
```

On a separate host use [`deploy/loadtest`](../../deploy/loadtest/compose.yaml) with `HUB_HOST` pointing at the center.

**Reset the regions before a new run** — their streams live in the container layer and keep events from earlier runs:
`docker compose --profile swarm up -d --force-recreate nats-region-1 nats-region-2 nats-region-3 nats-region-4`.

| Option | Default | Meaning |
|---|---|---|
| `--branches N` | 1000 | Simulated branches |
| `--regions K` | 4 | Regional hubs; branch `i` goes to region `i % K + 1` |
| `--nats URL,…` | `nats://localhost:4231…4234` | Regional hub addresses |
| `--clients N` | 150 | Visitors per day at an average branch |
| `--peak X` | 1 | Peak multiplier (3 ≈ pension day) |
| `--speed X` | 1 | Time acceleration: events ×X, heartbeats stay real-time |
| `--duration D` | 10m | `30s`, `10m`, `1h`; `0` — until Ctrl+C |
| `--start HH:MM` | now | Simulated Moscow start time |
| `--date YYYY-MM-DD` | today | Simulated date; on Sunday branches are closed and produce no events |
| `--seed N` | 42 | Model seed: same seed — same tickets (event ids differ per run) |
| `--notifications` | off | Emit `notification.requested`; the center delivers them to the notification endpoint |
| `--report PATH` | `swarm-report-<time>.json` | JSON report: sent, acknowledged, errors by type |

Every 5 s the swarm prints heartbeats/s, events/s, unacknowledged publishes and the action queue length. Losses are
checked by comparing `events_acked_total` from the report with the center's
`SELECT count(*) FROM events WHERE branch_id LIKE 's%'`.

For 250 000 branches run with `-Xmx4g` (`SWARM_HEAP=4g` in containers).
