# Deployment

| Directory | Stack | Runs on |
|---|---|---|
| [`center/`](center) | `center-app`, PostgreSQL, NATS hub; observability with `--profile monitoring` | The center host (data center or VPS) |
| [`branch/`](branch) | `node-app`, PostgreSQL, NATS leaf; log shipper with `--profile monitoring` | Every branch PC |
| [`loadtest/`](loadtest) | Four regional NATS hubs and `swarm-sim` | A load-generator host |
| [`staging/`](staging/README.md) | Overlays that put the center, two branches, the load test and the scenario console on one host | The staging host |

The root [`compose.yaml`](../compose.yaml) is the local development environment; it reuses these stacks via
`extends` and adds integration mocks and fault-injection plumbing. Do not run it on a server.

## Images

Application services reference `${IMAGE_REGISTRY:-esuo}/<name>:${IMAGE_TAG:-latest}`:

| Image | Built from |
|---|---|
| `center-app` | `java-backend/Dockerfile`, `MODULE=center-app` (includes the frontend bundle) |
| `node-app` | `java-backend/Dockerfile`, `MODULE=node-app` (includes the frontend bundle) |
| `branch-log-shipper` | `infrastructure/vector/Dockerfile` (Vector with the branch config baked in) |
| `swarm-sim` | `java-backend/Dockerfile`, `MODULE=swarm-sim` |

With a registry, set `IMAGE_REGISTRY` and `IMAGE_TAG` in the stack's env file and use `docker compose pull`. Without
one, `docker compose up -d --build` builds the images from the checkout on the target host.

## Network

```
                 internet / WAN
 branch PC  ──── outbound TCP 7422 ────▶  center: NATS hub (LEAF_BIND, all interfaces)
 visitors   ──── HTTPS ───────────────▶  reverse proxy ──▶ center-app (CENTER_BIND, loopback)
 hall LAN   ──── HTTP ────────────────▶  branch PC: node-app (NODE_BIND:NODE_PORT)
```

- The center publishes `7422/tcp` for leaf connections. It is NATS, not HTTP: forward it as plain TCP, do not
  terminate TLS on it.
- The center API listens on loopback by default; put a TLS-terminating reverse proxy in front of it. Server-Sent
  Events are used for live screens (paths ending in `/stream`): disable response buffering and allow long reads
  (nginx: `proxy_buffering off; proxy_read_timeout 3600s;`), pass `Host` and `X-Forwarded-Proto`.
- A branch opens no inbound ports to the internet. `node-app` is reachable on the branch LAN for the board, operator
  PCs and visitors on the branch Wi-Fi.
- Databases, NATS client ports, Loki, Prometheus and the management port `9081` listen on loopback only.

## Center

```bash
cp deploy/center/.env.example deploy/center/.env     # secrets, LEAF_ADVERTISE, CENTER_PUBLIC_URL, integrations
docker compose -f deploy/center/compose.yaml --env-file deploy/center/.env --profile monitoring up -d
docker compose -f deploy/center/compose.yaml --env-file deploy/center/.env ps
```

Required: `DB_PASSWORD`, `JWT_SECRET`, `LEAF_PASSWORD`, `MP_WEBHOOK_SECRET`, `CENTER_PUBLIC_URL`, `NOTIFY_URL`,
`MP_CALLBACK_URL`. Set `CENTER_ADMIN_PASSWORD` to enable the network operator account, and `GRAFANA_ADMIN_PASSWORD`
and `GRAFANA_DB_PASSWORD` when running the observability profile.

`LEAF_ADVERTISE` is the `host:port` that branches use as `CENTER_HOST`. Per-branch leaf accounts are supported
(`LEAF_B01_PASSWORD`, …, see `infrastructure/nats/hub.conf`); otherwise branches share the `leaf` account.

Check the hub's leaf connections:

```bash
curl -s localhost:8222/leafz | jq '[.leafs[].name]'
```

## Branch

On each branch PC:

```bash
cp deploy/branch/.env.example deploy/branch/.env     # BRANCH_ID, CENTER_HOST, LEAF_PASSWORD, local secrets
docker compose -f deploy/branch/compose.yaml --env-file deploy/branch/.env --profile monitoring up -d
```

Nothing is configured at the center: within a few seconds of the first heartbeat the branch appears on
`/network`, receives its configuration and starts streaming events, metrics and important logs.

In production keep `DEMO_DATA=false` and `DEMO_MODE=false`, and set `QR_SECRET`. Several branches on one host need
one env file each with distinct `BRANCH_ID`, `NODE_PORT`, `DB_PORT`, `LEAF_CLIENT_PORT` and `LEAF_MONITOR_PORT`; each
runs as its own project `esuo-branch-<id>`.

## Load test

```bash
cp deploy/loadtest/.env.example deploy/loadtest/.env          # HUB_HOST, the center's LEAF_PASSWORD
docker compose -f deploy/loadtest/compose.yaml --env-file deploy/loadtest/.env up -d --force-recreate
docker compose -f deploy/loadtest/compose.yaml --env-file deploy/loadtest/.env --profile run \
  run --rm swarm-sim --branches 40000 --duration 10m
```

`--force-recreate` empties the regional streams before a run. Reports are written to `reports/load/`. On a Sunday add
`--date <weekday>`: the simulated flow model is closed on Sundays.

## Sizing

| Component | Memory |
|---|---|
| Branch: `node-app` (`NODE_HEAP=1g`) + PostgreSQL + leaf + shipper | ~1.5 GB |
| Center: `center-app` (`CENTER_HEAP`) | heap + ~1 GB |
| Center PostgreSQL (`CENTER_DB_SHARED_BUFFERS`) | buffers + ~0.5 GB |
| NATS hub (`HUB_MEMORY`) | 2 GB limit |
| Observability: Loki, Grafana (512 MB each), Prometheus (1 GB), exporters, shipper | ~2.3 GB |
| Regional hub | 1 GB limit each |
| `swarm-sim` (`SWARM_HEAP`) | 2 GB for 40 000 branches, 4 GB for 250 000 |

For a network of hundreds of thousands of branches: `CENTER_HEAP=4g`, `CENTER_DB_SHARED_BUFFERS` at about a quarter of
the RAM given to the center, `HUB_MEMORY=4g`, 8+ vCPU. Disk: branch metrics history up to `METRICS_MAX_SIZE`,
Prometheus up to `PROMETHEUS_MAX_SIZE`, logs for 7 days.

## Upgrades

```bash
docker compose -f deploy/center/compose.yaml --env-file deploy/center/.env pull     # or: up -d --build
docker compose -f deploy/center/compose.yaml --env-file deploy/center/.env --profile monitoring up -d
```

Compose recreates only changed services. Branches and the center can be upgraded independently: branches buffer
events while the center is down, and the center keeps serving other branches while one is upgraded. Database
migrations (Flyway) run on application start.

Data lives in named volumes and survives container recreation; `down -v` deletes it. PostgreSQL keeps the password
it was initialised with: changing `DB_PASSWORD` against an existing volume locks the application out.

## Backups

```bash
./scripts/backup/dump.sh                                          # center, b01, b02 dumps into ./backups
./scripts/backup/restore.sh ./backups/<dump>.sql.gz <db-container>
```

The scripts address the container names of the local and staging environments (`esuo-postgres-*`); on a production
host run `pg_dump` in the stack's database container (`docker compose … exec postgres-center pg_dump …`). These are
logical dumps; there is no WAL archiving or replica yet.
