# ESUO

Queue management for a network of post offices.

Each **branch** runs its own queue on a local node (Spring Boot + PostgreSQL + a NATS leaf node) and keeps working
when the link to the center is down; events are buffered on the branch and delivered once the link is back. The
**center** is the public gateway for internet visitors, the network overview, central configuration, notification
delivery and the mobile-app integration.

A branch also plans its load: a service-duration model forecasts how much work each hour brings, the branch recommends
how many windows to staff, closes booking for hours it cannot serve and shows visitors a realistic wait.

- Architecture: [High-Level Design](docs/architecture/hld.md) · [Low-Level Design](docs/architecture/lld.md)
- API: [`docs/openapi`](docs/openapi) (Swagger UI is served by every service at `/swagger-ui.html`)
- Deployment: [`deploy/README.md`](deploy/README.md)

## Repository layout

```
java-backend/
  shared-contracts/   contracts shared by node and center: errors, events, NATS subjects, RPC, DTOs
  node-app/           branch node: queue, API, SSE, outbox, load planning, duration model
  center-app/         center: HTTP→RPC gateway, network overview, configuration, notifications, mobile app, monitoring
  swarm-sim/          simulator of many branches for load-testing the center
frontend/             client, board, operator, manager, network and demo screens — one Vite build
python-ai-service/    offline training of the duration model, synthetic data, scenario traces
tools/                notify-mock, mp-simulator (integration stand-ins), demo-runner, load-generator
infrastructure/       NATS, Loki, Prometheus, Grafana and Vector configs; queue, planning and monitoring defaults
deploy/               production stacks (center, branch), load test, staging
scripts/              demo scenarios, smoke tests, fault injection, backups, OpenAPI export
compose.yaml          local development environment
```

Stack: Java 25, Spring Boot 4, PostgreSQL 16, NATS 2.15 (JetStream, leaf nodes), React 18 + TypeScript, Python 3.11.

## Local development

Requires Docker with Compose v2.24+, plus `curl`, `jq` and `python3` for the scripts.

```bash
cp .env.example .env              # fill in the secrets (any values work locally)
docker compose up -d --build      # center + branches b01 and b02 + integration mocks
./scripts/demo/run.sh doctor      # environment health check
```

The nodes take 30–60 s to start on the first run.

| Screen | URL |
|---|---|
| Network overview (center) | http://localhost:8081/network — login `CENTER_ADMIN_LOGIN` / `CENTER_ADMIN_PASSWORD` |
| Client | http://localhost:8080/ (b01), http://localhost:8090/ (b02) |
| Board | http://localhost:8080/board |
| Operator / manager | `/operator`, `/admin` on a branch port — `operator` / `admin` with the `DEMO_*_PASSWORD` values |
| Demo console | http://localhost:8080/demo |
| Swagger UI | http://localhost:8080/swagger-ui.html (node), http://localhost:8081/swagger-ui.html (center) |
| Grafana | http://localhost:3000 (with `--profile monitoring`) |

Each branch is isolated in its own Docker network and reaches the center only through a link proxy, so a link outage
can be reproduced with `./scripts/chaos/cut-branch-link.sh b01`.

### Compose profiles

| Profile | Adds |
|---|---|
| *(none)* | Center, NATS hub, two branches, notification and mobile-app mocks |
| `monitoring` | Loki, Grafana, Prometheus, exporters, log shippers on both branches |
| `swarm` | Four regional NATS hubs for load tests (with observability) |
| `swarm-run` | `swarm-sim` as a container |
| `demo` | Scenario console behind the "Scenarios" tab on `/demo` (mounts the Docker socket — local and staging only) |

### Configuration

The full list is in [`.env.example`](.env.example) and the [LLD](docs/architecture/lld.md#13-configuration-reference).

| Variable | Purpose |
|---|---|
| `DB_PASSWORD`, `JWT_SECRET`, `LEAF_PASSWORD`, `MP_WEBHOOK_SECRET` | Required secrets |
| `CENTER_ADMIN_LOGIN`, `CENTER_ADMIN_PASSWORD` | Network operator account; empty password closes the private API |
| `QR_SECRET` | Signing key for hall QR codes; empty allows the static code `demo:<branch>` with demo data only |
| `DEMO_DATA` | Seed reference data and the `operator` / `admin` users |
| `DEMO_MODE` | Accelerated demo clock and demo-day reset (used by `scripts/demo`) |

### Building and testing

```bash
cd java-backend && ./mvnw verify                    # all modules; integration tests use Testcontainers
cd frontend && npm ci && npm run build && npm run e2e
cd python-ai-service && uv run pytest
python3 tools/demo-runner/test_runner.py
```

The Docker image (`java-backend/Dockerfile`) builds the frontend and embeds it into the node and center JARs.

### Scripts

| Path | Purpose |
|---|---|
| [`scripts/demo`](scripts/demo/README.md) | Scripted business-day scenarios on the local environment: three ticket sources, mobile app, disputes, link cut, notification outage, monitoring, load planning |
| `scripts/smoke` | Smoke checks against a running environment |
| `scripts/chaos` | Fault injection: link cut, CPU starvation, DB lock/freeze, process kill, notification outage |
| `scripts/backup` | Logical dumps and restore of the databases |
| `scripts/openapi` | Export OpenAPI specs from running services |

Output of the scripts, `swarm-sim` and `esuo-ml report` goes to `reports/` (not tracked).

## Load testing

```bash
docker compose --profile swarm up -d
cd java-backend && ./mvnw -pl swarm-sim -am package -DskipTests && cd ..
java -Xmx4g -jar java-backend/swarm-sim/target/swarm-sim-0.0.1-SNAPSHOT.jar --branches 250000 --duration 8m --start 12:00
```

See [`java-backend/swarm-sim/README.md`](java-backend/swarm-sim/README.md). For large runs set `CENTER_HEAP=4g` and
`CENTER_DB_SHARED_BUFFERS` to about a quarter of the available memory.

## Deployment

Production stacks for the center and for a branch PC, the load-test stack and the staging layout are described in
[`deploy/README.md`](deploy/README.md).
