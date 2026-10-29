# esuo-ml — service-duration model (offline)

Training happens here, not on the branch node. Its output is the **`model` section of the central configuration**,
which reaches branches the usual way (`CONFIG` → `BRANCH_INBOX`, see the [LLD](../docs/architecture/lld.md#7-service-duration-model)).

A branch therefore never depends on ML availability: the coefficients are already in its database
(`branch_config.model`), and this tool only publishes a new version from time to time. Without a model the node falls
back to the `handling_stats` cascade and then to `default-service-duration-sec`.

## Setup

No runtime dependencies — Python 3.11+ standard library only. `uv` is used for running and tests.

```bash
cd python-ai-service
uv sync                      # installs pytest (dev) only

uv run esuo-ml generate      # reference data, history, scenario traces, readable holdout
uv run esuo-ml fit           # train → build/model-params.json
uv run esuo-ml report        # model report → ../reports/ml-report.md
uv run pytest
```

Without `uv`: `PYTHONPATH=src python3 -m esuo_ml.cli fit`.

## Commands

| Command | Does |
|---|---|
| `generate` | From the world in `worlds/`: `seed.sql` (services, windows, employees), `history.sql` (6 weeks), `holdout.csv`, `planning.json` (config section), `scenario-*.json` (demo traces) into `infrastructure/demo/scenarios/` |
| `fit` | Backfitting with shrinkage, holdout evaluation against three baselines, version acceptance. Writes the `model` section. **Exits with 1 if the version is rejected** |
| `report` | Truth recovery, effect on window planning, accuracy against baselines and the oracle bound, interval calibration |
| `fixtures` | Reference fixtures for the Java `DurationModelTest` |
| `predict` | A single prediction from given parameters, for debugging |

Useful options: `--world`, `--seed`, `--days`, `--use-window`, `--from history.csv` (train on exported real history
instead of synthetic data), `--baseline` (previous parameters for acceptance), `--flow-bias pension=1.3` (how far a
scenario's actual flow deviates from its profile).

## Publishing a model

```bash
uv run esuo-ml fit --out build/model-params.json        # 0 — accepted, 1 — rejected
TOKEN=$(curl -s -X POST http://localhost:8081/api/v1/auth/login -H 'Content-Type: application/json' \
     -d "{\"login\":\"$CENTER_ADMIN_LOGIN\",\"password\":\"$CENTER_ADMIN_PASSWORD\"}" | jq -r .access_token)
curl -X PUT http://localhost:8081/api/v1/config/global \
     -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
     -d @build/model-params.json                        # or /config/branches/{b} for a single branch
```

The center assigns a config version and publishes it; each node applies the section, stores it in
`branch_config.model` and emits `model.trained`. Predictions are used when `scoring-strategy: heuristic`.

## The world is a file

`worlds/standard.json` describes the whole synthetic world: services, windows, employees with ids, true multipliers,
hour effect, noise and flow profiles. Reference data, history, traces and the `planning` section are all derived from
it, so ids are consistent by construction.

The trainer **does not read** the world file — it only sees generated observations, so "the model recovered the truth"
is a real check.

## Layout

```
worlds/standard.json     ground truth of durations and flow profiles
src/esuo_ml/
  world.py               world loading, true service duration
  generate.py            day simulation, observations
  emit.py                seed.sql, history.sql, traces, holdout, planning section
  fit.py                 backfitting, shrinkage, holdout, version acceptance
  predict.py             applying parameters — the executable spec of the `model` section
  baselines.py           constant, per-service mean, handling_stats cascade
  report.py              model report
  cli.py                 command line
tests/                   acceptance criteria, determinism, fixture parity
```

`predict.py` and the Java implementation compute the same thing; `tests/test_fixtures.py` and `DurationModelTest`
check both against shared `holdout.csv` and `model-params.json`.
