# Staging

One host runs five compose projects behind an external reverse proxy: the center (with observability), branch b01,
branch b02, the load-test regions and the scenario console. Branches on other machines can join through
`DOMAIN:7422`.

The projects are the production stacks from `deploy/center`, `deploy/branch` and `deploy/loadtest`; the files in
this directory only add what staging needs: the integration mocks, container names matching the local environment,
the shared network `esuo-wan`, per-branch link proxies for fault injection and the scenario console. As a result
`scripts/demo` and `scripts/chaos` run on the staging host with the same commands as on a laptop.

| Project | Files | Contents |
|---|---|---|
| `esuo-center` | `deploy/center/compose.yaml` + `center.override.yaml` | DB, NATS hub, center, notification and mobile-app mocks; Loki, Grafana, Prometheus, exporters, log shipper |
| `esuo-branch-b01` | `deploy/branch/compose.yaml` + `branch.override.yaml` + `b01.env` | DB, leaf, node, log shipper, link proxy to the hub |
| `esuo-branch-b02` | same + `b02.env` | same |
| `esuo-loadtest` | `deploy/loadtest/compose.yaml` + `loadtest.override.yaml` | Four regional hubs; `swarm-sim` on demand |
| `esuo-demo` | `demo-console.yaml` | `demo-runner` for the "Scenarios" tab on a branch's `/demo` |

## 1. Reverse proxy

TLS and certificates are handled by your proxy; services listen on host ports. Hostnames derive from `DOMAIN` in `.env`.

| External address | Host port | Service |
|---|---|---|
| `https://DOMAIN` | `:8081` | center-app: client, `/network`, API, `/swagger-ui.html` |
| `https://grafana.DOMAIN` | `:3000` | Grafana (login `admin` / `GRAFANA_ADMIN_PASSWORD`) |
| `https://prometheus.DOMAIN` | `:9090` | Prometheus — it has no login of its own, protect it with basic auth on the proxy |
| `https://b01.DOMAIN` | `:8080` | Branch b01: client `/`, board `/board`, operator `/operator`, manager `/admin`, demo `/demo` |
| `https://b02.DOMAIN` | `:8090` | Branch b02 |
| `DOMAIN:7422` (TCP) | `:7422` | NATS hub leaf connections — plain TCP passthrough, no TLS termination |

Proxy requirements: SSE support (`proxy_buffering off`, `proxy_read_timeout 3600s`), `Host` and `X-Forwarded-Proto`
forwarded, every subdomain proxied to the root of its port (no path prefixes). With the proxy on the same host keep
`*_BIND=127.0.0.1`; with the proxy elsewhere use `0.0.0.0` and a firewall that only admits the proxy.

## 2. First start

Requires Docker with Compose v2.24+ and `git`; the scripts also need `bash`, `curl`, `jq`, `python3`.

```bash
git clone <repository> esuo && cd esuo
cp deploy/staging/.env.example .env                    # DOMAIN and every secret
cp deploy/staging/b01.env.example deploy/staging/b01.env
cp deploy/staging/b02.env.example deploy/staging/b02.env
docker network create esuo-wan                         # once per host
```

Shell aliases keep the commands short (all commands run from the repository root):

```bash
alias esuo-center='docker compose -f deploy/center/compose.yaml -f deploy/staging/center.override.yaml --env-file .env --profile monitoring'
alias esuo-b01='docker compose -f deploy/branch/compose.yaml -f deploy/staging/branch.override.yaml --env-file .env --env-file deploy/staging/b01.env --profile monitoring'
alias esuo-b02='docker compose -f deploy/branch/compose.yaml -f deploy/staging/branch.override.yaml --env-file .env --env-file deploy/staging/b02.env --profile monitoring'
alias esuo-loadtest='docker compose -f deploy/loadtest/compose.yaml -f deploy/staging/loadtest.override.yaml --env-file .env'
alias esuo-demo='docker compose -f deploy/staging/demo-console.yaml --env-file .env'
```

Start in this order — center, branches, console, load-test regions:

```bash
esuo-center up -d --build
esuo-b01 up -d --build
esuo-b02 up -d --build
esuo-demo up -d --build
esuo-loadtest up -d
./scripts/demo/run.sh doctor
```

Do not run the root `compose.yaml` on the staging host: it reads the same `.env` and its container names collide.

## 3. Updating

```bash
git pull
esuo-center up -d --build          # rebuilds and recreates only what changed
esuo-b01 up -d --build
esuo-b02 up -d --build
esuo-demo up -d --build
esuo-loadtest up -d
```

One service: `esuo-center up -d --build center-app`, `esuo-b01 up -d --build node-app`. Restart without rebuilding:
`esuo-b01 restart node-app`.

## 4. Scenarios and fault injection

On the staging host, from the repository root:

```bash
./scripts/demo/run.sh list
./scripts/demo/run.sh link-cut --fast
./scripts/chaos/cut-branch-link.sh b01         # restore | status
./scripts/chaos/cpu-starve.sh b01 60
```

From a browser: `https://b01.DOMAIN/demo` → sign in as manager → "Scenarios". Scenarios cannot be run from another
machine against staging: they need the host's Docker (link cuts, data loading, branch databases).

Do not chain all scenarios with `run.sh all` while someone is watching: an appointment left by one scenario can land
in the next one's queue on the demo clock and fail its checks.

## 5. Load test

```bash
esuo-loadtest up -d --force-recreate                # empty the regional streams before a run
esuo-loadtest --profile run run --rm swarm-sim --branches 40000 --duration 10m --date 2026-10-05 --start 12:00
```

The report goes to `reports/load/`. Losses: compare `events_acked_total` from the report with
`docker exec esuo-postgres-center psql -U esuo -d esuo_center -c "select count(*) from events where branch_id like 's%'"`.
For 250 000 branches set `SWARM_HEAP=4g`. Simulated branches appear on `https://DOMAIN/network`; center load is on
the center dashboard in Grafana.

## 6. A branch on another machine

The machine only needs outbound TCP to `DOMAIN:7422`. Use a branch id other than `b01`/`b02`.

```bash
cp deploy/branch/.env.example deploy/branch/.env
#   BRANCH_ID=b03, CENTER_HOST=<DOMAIN>, LEAF_PASSWORD=<LEAF_PASSWORD from the staging .env>,
#   DB_PASSWORD and JWT_SECRET of its own, DEMO_DATA=true, DEMO_MODE=true, QR_PUBLIC_BASE_URL=https://<DOMAIN>
docker compose -f deploy/branch/compose.yaml --env-file deploy/branch/.env --profile monitoring up -d --build
```

Within half a minute the branch shows up on `https://DOMAIN/network`; its important logs appear in Grafana.

## 7. Sizing

Without the load test about 14 GB of RAM; with 250 000 simulated branches about 24 GB. Recommended: 32 GB and 8+ vCPU
for full-scale load tests, 16 GB for staging with up to 40 000 simulated branches.
