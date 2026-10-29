# Documentation

| Document | Contents |
|---|---|
| [High-Level Design](architecture/hld.md) | Goals, principles, system context, containers, key flows, data ownership, quality attributes, deployment view, stack, trade-offs |
| [Low-Level Design](architecture/lld.md) | Code layout, NATS subjects and streams, delivery guarantees, data model, queue algorithm, ETA, duration model, load planning, center internals, monitoring, security, configuration, failure handling, testing |
| [OpenAPI](openapi) | REST API of the branch node (`node.yaml`) and the center (`center.yaml`); regenerate with `scripts/openapi/export.sh` |
| [Deployment](../deploy/README.md) | Production center and branch stacks, load test, sizing, upgrades, backups |
| [Staging](../deploy/staging/README.md) | Single-host staging environment behind a reverse proxy |

Component documentation:

| Component | README |
|---|---|
| Duration model (offline training) | [`python-ai-service`](../python-ai-service/README.md) |
| Branch swarm simulator | [`java-backend/swarm-sim`](../java-backend/swarm-sim/README.md) |
| Demo scenarios | [`scripts/demo`](../scripts/demo/README.md) |
| Scenario traces and seed data | [`infrastructure/demo/scenarios`](../infrastructure/demo/scenarios/README.md) |
| Frontend | [`frontend`](../frontend/README.md) |
