# Для разработчика B: что изменилось в ноде и что нужно знать

Полные форматы — в `docs/ESUO-design-doc-additions.md` (разделы 1–11а). Здесь — то, что нужно знать, чтобы писать центр,
`mp`, `notify`, `network` и постановки для агентов, ничего не гадая. Все контракты ниже реализованы в `node-app` и
покрыты автотестами. Транспорт `BRANCH_INBOX` дополнительно прогнан на стенде (`scripts/smoke/day4.sh`).

## 0. Что не менялось (можно не проверять)

NATS-конфиги, compose-файлы, стримы и домены, source-цепочки, outbox-воркер, RPC-конверт и прежние RPC-операции,
`EventEnvelope`, `Subjects`, `TokenHash`, прежние DTO. `Dockerfile` собирает с `-DskipTests`.

## 1. Центр публикует конфигурацию и пользователей отделения

Чего пока нет в `center-app`: модуля `config`. Формат сообщения — `honey.road.contracts.inbox.ConfigMessage` (`shared-contracts`).

- Subject: `center.config.global` и `center.config.branch.{b}`; заголовок `Nats-Msg-Id` — уникальный на сообщение (дедупликация в узле по нему).
- Тело (snake_case): `version`, `scope`, `queue` (полный или **частичный**, накладывается на действующий), `bounds`, `employees[]`, `users[]`.
- **Версия должна быть строго больше версии, которую узел уже применил.** После этого релиза у свежего узла `config_version = 4`,
  поэтому первая версия центра — **5 и выше**. Равная или меньшая — «устаревшая», узел пропускает её молча, без ошибки и без события.
- `users[].password_hash` — bcrypt; пароль в открытом виде по NATS не передавать. Роли: `OPERATOR`, `BRANCH_ADMIN`.
  Отсутствующие в сообщении пользователи и сотрудники не удаляются, отключение — `active=false`.
- Применение атомарно: невалидное сообщение (например, `slack_min < 0`, неизвестная роль) отклоняется целиком и не переотправляется.
- Ответ узла — событие `config.applied` в `CENTER_EVT`: `data.central_version`, `data.effective_version`, `data.scope`, `data.dropped_overrides`.
  Ждать применения можно по нему, а не по таймеру.
- Границы `bounds` (`{"slack_min": {"min":0,"max":15}}`) определяют, что руководитель отделения вправе менять локально. Без `bounds` локально менять нельзя ничего.

Быстрая проверка руками (hub на `localhost:4222`):
```bash
nats --server nats://localhost:4222 pub center.config.branch.b01 -H Nats-Msg-Id:cfg-100 \
  '{"version":100,"scope":"branch","queue":{"slack_min":7},"bounds":{"slack_min":{"min":0,"max":15}}}'
```

## 2. Запись из МП (модуль `mp`, вебхук, callback)

Чего пока нет: вебхука `POST /webhooks/mp/appointments`, публикации в `MP_INBOX`, `mp-simulator`, callback.

- Публикация: subject `center.mp.inbox.branch.{b}`, **`Nats-Msg-Id = mp_appointment_id`** (дедупликация и в стриме, и в узле).
- Тело — `honey.road.contracts.inbox.MpAppointmentMessage`: `mp_appointment_id`, `service_code`, `slot_start` (UTC), `client_category`, `client_ref`.
  Услуга — **по коду** (`services.code`, в демо: `parcel`, `send`, `finance`), не по UUID. Каталог кодов приходит в heartbeat (`services[].code`).
- Результат приходит из отделения событием в `CENTER_EVT` (`branch.{b}.evt.mp.appointment.confirmed|rejected`):
  - `confirmed`: `mp_appointment_id`, `ticket_id`, `display_number`, `token_hash`, `status`, `service_code`, `slot_start`, `client_ref`;
  - `rejected`: `mp_appointment_id`, `reason` (`SLOT_EXPIRED`, `SLOT_UNAVAILABLE`, `SERVICE_NOT_FOUND`, `VALIDATION_ERROR`, `IDEMPOTENCY_CONFLICT`), `detail`, тот же набор полей записи.
- **Токен в событиях нет** (и не будет: `EventEnvelope` отклоняет `public_token`). Для ссылки на статус в callback:
  по `confirmed` вызвать RPC `branch.{b}.rpc.ticket.by_mp` с телом `{"mp_appointment_id": "…"}` → ответ `200` с `TicketView`, в нём `token`.
  Токен использовать только для сборки ссылки и не сохранять. Отделение недоступно — обычные ретраи `mp-callback`, событие остаётся в `CENTER_EVT`.
- Отказ по правилам (например, слот занят) — не ошибка обработки: узел записывает `rejected` и подтверждает сообщение. Повторная доставка того же `mp_appointment_id` игнорируется.
- Время в прошлом: узел отклоняет запись `SLOT_EXPIRED` (S10) — это ожидаемый результат для сценария обрыва связи.

## 2а. RPC: одна новая операция

| Subject | Тело | Ответ |
|---|---|---|
| `branch.{b}.rpc.ticket.by_mp` | `{"mp_appointment_id": "…"}` | `200 TicketView` или `404 TICKET_NOT_FOUND` |

Конверт RPC и коды ошибок прежние. Остальные операции (`branch.info`, `slots.list`, `appointment.create`, `qr.issue`, `ticket.get|cancel|postpone|checkin`) не менялись по форме; изменилось только содержимое ETA (см. п. 6).

## 3. Уведомления (модуль `notify`, `notify-mock`, DLQ)

Чего пока нет: `notify` worker, Resilience4j, DLQ, `notify-mock /chaos`. Узел уже публикует запросы:

- Событие `branch.{b}.evt.notification.requested` в `CENTER_EVT`. `data`: `kind` (`approaching`), **`dedup_key`** (`<ticket_id>:approaching`),
  `ticket_id`, `token_hash`, `display_number`, `service_id`, `status`, `position`, `eta`, `eta_confidence`.
- Идемпотентность — по `event_id` и по `dedup_key`; узел уведомляет по одному талону **один раз** (флаг в БД).
- Контактов клиента в MVP нет (канал — демо-адаптер). Клиент в Wi-Fi отделения получает то же событие по SSE ноды без участия центра.
- TTL сообщения проверяет центр по `occurred_at` (конфигурационный ключ `notifications.message_ttl_sec` в центре, на ноде его нет).
- Пересчёт периодический (5 с), поэтому событие приходит с задержкой до одного интервала после изменения очереди.

## 4. Heartbeat: изменилось содержимое версий

Заголовок и частота прежние. Поля:

- `config_version` — версия, **опубликованная центром**; сравнивайте её с версией, которую отправили (или с `config.applied.central_version`).
- `effective_config_version` — новое поле: действующая версия (растёт и от локальных правок руководителя). Не сравнивать с версией центра.
- `services[]` содержит только активные услуги (`id`, `code`, `name`, `number_prefix`); `windows_total` — только активные окна.
- Остальные поля прежние: `outbox_pending`, `outbox_lag_sec`, `windows_open`, `waiting`, `overdue`, `oldest_wait_sec`.

## 5. Новые события в `CENTER_EVT` (ingest их принимает без изменений)

| Событие | Когда |
|---|---|
| `config.applied` | узел применил версию центра |
| `mp.appointment.confirmed` / `rejected` | результат записи МП |
| `notification.requested` | «очередь приближается» |
| `incident.created` | оператор зафиксировал проблему |

Поля `ticket.called`: добавлены `model_version` и по каждому кандидату `predicted_duration_sec`, `basis`, `samples` (раньше `score` мог быть `null`, теперь число).

## 6. Что изменилось для клиентов узла и центра

- `TicketView.eta` и `position` считаются симуляцией по открытым окнам. Форма ответа та же. Если у услуги нет открытого окна — `eta: null`, `eta_confidence: "none"`.
- `TicketView.can_postpone` учитывает лимит (`postpone-max-count`, по умолчанию 1).
- `tickets.config_version` и `config_version` конверта — теперь `effective_config_version`.
- Новые ручки руководителя (`/api/v1/admin/...`): аналитика, отклонения, рекомендации, справочники (услуги, окна, сотрудники), конфигурация с границами,
  инциденты. Форматы — `docs/agents/admin-screen-task.md` (для агента `admin`) и additions.

## 7. Что нужно сделать в центре (сводка)

1. `config`: `PUT /config/global`, `/config/branches/{b}`, `POST /branches/{b}/users` → публикация `ConfigMessage` (версии ≥ 5; `Nats-Msg-Id` уникален).
2. `mp`: вебхук (HMAC, 202), `MP_INBOX` с `Nats-Msg-Id = mp_appointment_id`, `mp-callback` через `ticket.by_mp`, `mp-simulator`.
3. `notify`: потребитель `notification.requested` с ретраями, circuit breaker, DLQ, проверкой TTL и дедупликацией по `dedup_key`; `notify-mock` с `/chaos`.
4. `network`: показать `config_version` и `effective_config_version`, добавить аномалии из 9.5; учесть, что `services` в heartbeat — только активные.
5. Chaos: `restart-node.sh`, `notify-down.sh`; k6 `issue.js`.
6. OpenAPI (springdoc) в `node-app` и `center-app` — сейчас не подключён, агенты работают по markdown-контрактам.
7. Задачи агентам: `client`, `board`, `network`; `operator` — вместе с A.

## 8. Как проверить, что узлы по-прежнему дружат с центром

```bash
docker compose up -d --build
./scripts/smoke/day1.sh     # выдача талонов и ошибки
./scripts/smoke/day3.sh     # RPC через центр, недоступное отделение, два окна, разрыв
./scripts/smoke/day4.sh     # аналитика, инциденты, BRANCH_INBOX (конфигурация и запись МП)
./scripts/smoke/day5.sh     # справочники, рекомендации, heuristic, load-generator (нужны docker и java)
```
Тесты (нужен Docker): `cd java-backend && ./mvnw -pl node-app -am test`. Без Docker сборка образов работает (`-DskipTests`).

## 9. Открытые вопросы к B

- Кто пишет контроллеры `/admin/...` для справочников на стороне центра (в узле они уже есть)?
- Где хранится и как редактируется `notifications.message_ttl_sec`: в центральной конфигурации или локально в центре?
- Нужна ли в центре отдельная проверка «применена ли версия», или достаточно события `config.applied` и `config_version` в heartbeat?
- Формат ссылки на статус для МП: `https://<center>/b/{b}/t/{token}` или другой?
