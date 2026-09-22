# ЕСУО — электронная система управления очередью

Центр + отделения. Каждое отделение (нода) — автономная ячейка очереди: Spring Boot + PostgreSQL + NATS leaf.
Центр — NATS hub + `center-app` (публичный шлюз, реестр сети, аналитика). Связь только через NATS leaf,
соединение инициирует отделение.

## Запуск на одной машине (центр + b01 + b02)

```bash
cp .env.example .env          # задать DB_PASSWORD, JWT_SECRET (openssl rand -base64 32), LEAF_PASSWORD, MP_WEBHOOK_SECRET
docker compose up -d --build
./scripts/smoke/day1.sh       # выдача талонов, идемпотентность, ошибки
./scripts/smoke/day3.sh       # вызов, 2 окна, закрытие окна, перенаправление, центр, RPC, 503
./scripts/smoke/day4-center.sh  # конфигурация из центра, пользователи, запись из МП с callback
./scripts/chaos/notify-down.sh  # проверка №8: отказ уведомлений, circuit breaker, DLQ (~5 мин)
```

| Что | Адрес |
|---|---|
| Нода b01 / b02 | http://localhost:8080 / http://localhost:8090 |
| Центр | http://localhost:8081 (`/network`, `/api/v1/network/overview`, `/api/v1/config`) |
| Swagger UI | http://localhost:8080/swagger-ui.html (нода), http://localhost:8081/swagger-ui.html (центр) |
| notify-mock | http://localhost:8095 (`/chaos`, `/messages`) |
| mp-simulator | http://localhost:8096 (`POST /send`, `/callbacks`) |
| NATS hub мониторинг | http://localhost:8222/leafz, `/jsz?streams=true` |

Демо-пользователи ноды: `operator`, `operator2` (пароль `DEMO_OPERATOR_PASSWORD`), `admin` (`DEMO_ADMIN_PASSWORD`).

Разрыв связи отделения с центром: `./scripts/chaos/cut-branch-link.sh b01` / `... b01 restore`.

## Стенд на нескольких машинах

| Машина | Команда |
|---|---|
| Центр (LAN или VPS) | `docker compose -f deploy/center/docker-compose.yml --env-file deploy/center/.env up -d --build` |
| Отделение | `docker compose -f deploy/branch/docker-compose.yml --env-file deploy/branch/.env up -d --build` |

Отделению нужны только `BRANCH_ID`, `CENTER_HOST` и пароль leaf (`deploy/branch/.env.example`).
В центре для нового отделения ничего не настраивается: первый heartbeat регистрирует его в реестре
и подключает его события к `CENTER_EVT`. Наружу у центра открыты порты 7422 (leaf) и 8081 (API).

## Бэкап баз перед демо

```
./scripts/backup/dump.sh          # дамп всех поднятых баз в ./backups/*.sql.gz (вне docker-томов)
./scripts/backup/dump.sh b01      # дамп только одной (center | b01 | b02)
./scripts/backup/restore.sh ./backups/b01-esuo-<...>.sql.gz esuo-postgres-b01
```

Не автоматика (без крона/WAL/репликации) — просто подстраховка на время хакатона: снять дамп
перед репетицией/показом, восстановить, если сценарий что-то сломал.

## Структура

```
java-backend/
  shared-contracts/   honey.road.contracts — ошибки, конверт события, subjects NATS, RPC, DTO
  node-app/           honey.road.node      — очередь, API, SSE, outbox, RPC-обработчики, heartbeat
  center-app/         honey.road.center    — шлюз HTTP→RPC, реестр сети, ingest, SSE для интернета
frontend/             client, board, operator, admin, network — одна сборка, раздаётся нодой и центром
tools/                load-generator, notify-mock, mp-simulator
docs/                 openapi/ (выгрузка springdoc), demo/results.md
infrastructure/       NATS-конфиги (параметры из env), queue-defaults.yml
deploy/               compose для центра и отделения по отдельности
scripts/              smoke-проверки, chaos, openapi/export.sh
```

## Заметки A для B (день 1; пакеты с тех пор — honey.road.node.*, DTO — shared-contracts/dto)


- Команды ядра: `IssueTicket.execute`, `IssueTicket.fromMobileAppointment`, `CancelTicket.execute`.
- Чтение: `TicketQueries`; DTO: `common-dto/TicketView` и `EventEnvelope`.
- Запрос кандидата: `TicketRepository.lockHead`; обязателен активный контекст транзакции. Он не назначает клиента окну.
- `ticket_events` и `outbox` пишутся синхронно в транзакции команды; публиковать `pending` должен будущий воркер B.
- Внутренний вход МП принимает `externalId`, повторяет исходный ответ и не открыт наружу.
- Для проигрывания новых доменных ошибок следует использовать `code` из `DomainException` и коды дизайн-документа.