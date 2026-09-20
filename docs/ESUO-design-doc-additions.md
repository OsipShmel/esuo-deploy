# Дополнения к дизайн-доку (для вставки)

Дизайн-док в `Downloads` я не менял. Здесь — то, чего в нём нет, но что уже реализовано в коде. По правилу 0.3.1 документ
должен идти первым, поэтому каждый пункт помечен местом вставки. После переноса в дизайн-док этот файл можно удалить.

Метки как в доке: **[MVP]** — реализовано и покрыто тестами, **[Не проверено на стенде]** — есть код и автотесты без NATS,
но на живом стенде не прогонялось.

---

## 1. Решение по токену для МП (раздел 4.6, 10.1, 11.2) — было открытым вопросом

**Проблема.** Callback в МП должен содержать «ссылку на статус» (11.2), а ссылка строится из `public_token`.
Токен из отделения не уходит ни в NATS, ни в журнал (10.2).

**Решение [MVP].** Токен не попадает в события. Центр получает его синхронным RPC:

| Subject | Тело | Ответ |
|---|---|---|
| `branch.{b}.rpc.ticket.by_mp` | `{"mp_appointment_id": "..."}` | `200`, `TicketView` (в нём `token`) или `404 TICKET_NOT_FOUND` |

Порядок в центре: `mp-callback` читает `mp.appointment.confirmed` из `CENTER_EVT` → вызывает `ticket.by_mp` →
собирает ссылку `https://<center>/b/{b}/t/{token}` → отправляет callback в МП. Токен живёт только в памяти обработчика
и в теле callback, нигде не сохраняется. RPC-ответ с токеном — тот же путь, каким токен уже возвращается клиенту
в `appointment.create`. Если отделение недоступно в момент callback, обработчик повторяет по общему правилу ретраев
`mp-callback`, потому что событие остаётся в `CENTER_EVT`.

В таблицу 10.1 добавить строку `ticket.by_mp` в перечень RPC-операций.

---

## 2. Сообщения центр → отделение (раздел 4.8, 4.6, 10.1)

Оба сообщения приходят в `BRANCH_INBOX`, потребитель `node-inbox` (10.5). Ключи JSON — snake_case.

### 2.1. Конфигурация: `center.config.global`, `center.config.branch.{b}`

```json
{
  "version": 3,
  "scope": "branch",
  "queue": { "slack_min": 7, "target_wait_min": { "qr": { "default": 25 } } },
  "bounds": { "slack_min": { "min": 0, "max": 15 }, "call_timeout_sec": { "min": 30, "max": 300 } },
  "employees": [ { "id": "uuid", "full_name": "Иванова А.", "active": true } ],
  "users": [ { "id": "uuid", "login": "ivanova", "password_hash": "$2a$...", "role": "OPERATOR",
               "employee_id": "uuid", "active": true } ]
}
```

| Поле | Правило |
|---|---|
| `version` | Применяется, только если выше действующей центральной версии отделения (`branch_config.central_version`); равная и меньшая — устаревшие, игнорируются без ошибки |
| `queue` | Ключи из 6.9 в snake_case. Полный набор или часть: **частичный накладывается на действующую центральную конфигурацию глубоким слиянием**; отсутствующие ключи сохраняются |
| `bounds` | Заменяет действующие границы. Ключи с границами — единственные, которые руководитель вправе переопределить. Без `bounds` остаются прежние |
| `employees`, `users` | Upsert по `id` (сотрудники) и `login` (пользователи). Отсутствующие в сообщении не удаляются; отключение — `active=false`. Пароль — только bcrypt-хеш. Допустимые роли: `OPERATOR`, `BRANCH_ADMIN` |

Применение атомарно: невалидное сообщение (например, `slack_min < 0` или неизвестная роль) откатывается целиком.
Переопределения руководителя, вышедшие за новые границы, при применении версии отбрасываются.
Действующая версия `effective_version` растёт при любом изменении (центр или руководитель) и записывается в каждый
новый талон как `config_version`.

Ответ отделения — событие `config.applied`:

```json
{ "central_version": 3, "effective_version": 7, "scope": "branch", "dropped_overrides": 0 }
```

### 2.2. Запись из МП: `center.mp.inbox.branch.{b}`

```json
{ "mp_appointment_id": "mp-4711", "service_code": "parcel", "slot_start": "2026-09-21T07:00:00Z",
  "client_category": "default", "client_ref": "user-1" }
```

`Nats-Msg-Id` = `mp_appointment_id`. Услуга задаётся кодом (`services.code`), потому что идентификаторы услуг
принадлежат отделению. Талон создаётся тем же `IssueTicket`, что и веб-запись: `booked`, слот проверяется по 6.3.

Результат — одно из событий:

| Событие | `data` |
|---|---|
| `mp.appointment.confirmed` | `mp_appointment_id`, `ticket_id`, `display_number`, `token_hash`, `status`, `service_code`, `slot_start`, `client_ref` |
| `mp.appointment.rejected` | `mp_appointment_id`, `reason`, `detail`, `service_code`, `slot_start`, `client_ref` |

Значения `reason`: `SLOT_EXPIRED` (S10), `SLOT_UNAVAILABLE`, `SERVICE_NOT_FOUND`, `VALIDATION_ERROR`, `IDEMPOTENCY_CONFLICT`.

### 2.3. Гарантии потребителя (10.5)

- Дедупликация по `inbox_processed(message_id)` в той же транзакции, что и применение.
- `message_id` = заголовок `Nats-Msg-Id`, при его отсутствии — `BRANCH_INBOX:<номер в стриме>`.
- Постоянная ошибка (битый JSON, невалидная конфигурация) — сообщение отмечается обработанным и подтверждается: повторы бессмысленны.
- Временная ошибка (БД) — не подтверждается, `nak` с задержкой 5 с, дальше `backoff` 1 с → 5 мин, `max_deliver=10`.
- Отказ по правилам записи МП — не ошибка обработки, а результат `mp.appointment.rejected`.

**[Не проверено на стенде]:** сам pull-консьюмер. Проверка: `scripts/smoke/day4.sh`.

---

## 3. Событие `notification.requested` (раздел 4.7)

```json
{ "kind": "approaching", "dedup_key": "<ticket_id>:approaching", "ticket_id": "uuid", "token_hash": "…",
  "display_number": "П-014", "service_id": "uuid", "status": "waiting", "position": 2,
  "eta": "2026-09-21T10:42:00Z", "eta_confidence": "low" }
```

Условие: `position ≤ approaching-position-lte` или `eta ≤ now + approaching-eta-lte-min`. Не уведомляются: талоны, не допущенные
к вызову (`eligible_from` в будущем: запись раньше времени, отложенные), и талоны без ETA (нет открытого окна с их услугой).
Флаг `notified_approaching_at` ставится проверкой-и-установкой: событие по талону уходит ровно один раз. Пересчёт — периодический
(`esuo.timers.interval`, по умолчанию 5 с), поэтому задержка уведомления до одного интервала. Клиентская страница получает
то же событие по SSE ноды.

---

## 4. Конфигурация очереди (раздел 6.9): новые ключи

```yaml
  idle-alert-min: 15      # S14: окно open без активности дольше — отклонение
  long-service-min: 20    # обслуживание дольше — отклонение «долгое обслуживание»
```

`config-version` в `queue-defaults.yml` повышен до 3. Там же для локального переопределения пригодны только параметры с `bounds`.
В 8.1 `branch_config` добавить колонку `bounds jsonb NOT NULL DEFAULT '{}'` (миграция V4).

---

## 5. Аналитика отделения (раздел 9.3): определения и формат [MVP]

Параметры `from`, `to` необязательны: дата `2026-09-21` (по часовому поясу отделения; дата в `to` включает весь день)
или момент ISO-8601. По умолчанию — от начала сегодняшнего дня до текущего момента.

**Определения (чтобы цифры на экране можно было объяснить):**

| Термин | Определение |
|---|---|
| Период | по `issued_at` ∈ [from, to) |
| Обслужен | статус `done` |
| Ожидание | от `checked_in_at` (для записи) или `issued_at` до **первого** `ticket.called` из журнала; повторные вызовы и возвраты не сбрасывают |
| Длительность обслуживания | `completed_at − (serving_started_at | called_at)` у обслуженных |
| Окно открыто | интервалы между `window.opened` (не повторным) и `window.closed` из журнала окна, обрезанные периодом; простой = открыто − обслуживает |

**`GET /admin/analytics/summary`**

```json
{ "from": "…", "to": "…",
  "totals": { "issued": 5, "served": 2, "cancelled": 1, "no_show": 0, "active": 2,
              "avg_wait_sec": 42, "max_wait_sec": 90, "avg_service_sec": 120 },
  "by_source":  [ { "source": "qr", "issued": 3, "served": 2, "avg_wait_sec": 42, "max_wait_sec": 90 } ],
  "by_service": [ { "service_id": "…", "service_name": "…", "issued": 3, "served": 2,
                    "avg_wait_sec": 42, "max_wait_sec": 90, "avg_service_sec": 120 } ] }
```

**`GET /admin/analytics/windows`** — массив `{ window_id, name, open_sec, serving_sec, idle_sec, served, avg_service_sec, utilization }`,
`utilization` ∈ [0, 1].

**`GET /admin/analytics/deviations`** — `{ generated_at, from, to, counts: {тип: число}, items: [ {type, …} ] }`. Типы:

| `type` | Условие | Поля |
|---|---|---|
| `overdue_ticket` | `waiting` и `due_at < now` (текущее состояние) | `ticket_id`, `display_number`, `service_id`, `source`, `due_at`, `overdue_sec` |
| `no_show` | `no_show` за период | `ticket_id`, `display_number`, `service_id`, `source`, `reason`, `at` |
| `long_service` | обслуживание дольше `long-service-min` | `ticket_id`, `display_number`, `service_id`, `window_id`, `employee_id`, `duration_sec` |
| `idle_window` | S14: окно `open`, нет активного талона, нет действий дольше `idle-alert-min` | `window_id`, `window_name`, `idle_sec`, `last_activity_at` |
| `service_without_window` | S2: есть `waiting`, а открытого окна с услугой нет | `service_id`, `service_name`, `waiting` |
| `slow_window` | 16.3: среднее обслуживание окна за период ≥ `slow-window-factor` (1.5) × норма по тем же услугам (среднее по отделению); окна с числом обслуживаний < `heuristic-min-samples` не оцениваются | `window_id`, `window_name`, `avg_service_sec`, `norm_service_sec`, `ratio`, `samples` |
| `open_incident` | инцидент со статусом `open` | `incident_id`, `kind`, `description`, `window_id`, `ticket_id`, `created_at` |

**`GET /admin/analytics/unfinished`** — талоны периода в статусах `booked`, `waiting`, `called`, `serving`:
`{ ticket_id, display_number, source, status, service_id, service_name, issued_at, slot_start, due_at, window_id }`.
Записи на будущие дни (`booked`) в него попадают.


---

## 6. Инциденты (раздел 5.1, 9.3) [MVP]

| Метод | Путь | Роль | Тело | Ответ |
|---|---|---|---|---|
| POST | `/incidents` | `OPERATOR`, `BRANCH_ADMIN` | `kind` (`technical` \| `operational`), `description` (до 1000), `window_id?`, `ticket_id?` | 201, инцидент; событие `incident.created` |
| PATCH | `/admin/incidents/{id}` | `BRANCH_ADMIN` | `{"status": "resolved"}` | 200; повторное закрытие идемпотентно |

Инцидент: `{ id, kind, description, window_id, ticket_id, reported_by, status, created_at, resolved_at }`.
`reported_by` — сотрудник из токена. Событие `incident.created`: `incident_id`, `kind`, `description`, `window_id`, `ticket_id`.

---

## 7. Конфигурация отделения (раздел 9.3) [MVP]

- `GET /admin/config` → `{ central_version, effective_version, central, local_overrides, bounds, effective }`.
- `PUT /admin/config` — тело: объект переопределений в snake_case, например `{"slack_min": 10}`; `{}` снимает все.
  Ошибка `422 CONFIG_OUT_OF_BOUNDS`, если значение вне `bounds` или у параметра границ нет. Вложенные значения
  (`target_wait_min`) проверяются по каждому числу.

---

## 8. Справочники руководителя (раздел 9.3) [MVP]

Роль `BRANCH_ADMIN`. Удаление мягкое: `DELETE` возвращает запись с `active=false`; запись остаётся ради истории талонов.
Списки по умолчанию без отключённых; `?include_inactive=true` — со всеми.

| Ресурс | Методы | Тело | Правила |
|---|---|---|---|
| `/admin/services` | GET, GET `/{id}`, POST, PUT `/{id}`, DELETE `/{id}` | `code` (a-z, 0-9, `_`, `-`, до 32; только при создании), `name`, `number_prefix` (1–3 символа), `slot_duration_min`, `slot_capacity`, `active` | `code` и `number_prefix` уникальны в отделении (в т.ч. среди отключённых); слот и вместимость по умолчанию — из конфигурации очереди; отключить услугу нельзя, пока по ней есть талоны `booked`/`waiting`/`called`/`serving` (`409 INVALID_STATE_TRANSITION`) |
| `/admin/windows` | GET, GET `/{id}`, POST, PUT `/{id}` (имя), DELETE `/{id}`, PUT `/{id}/services` | `name`, `service_ids` | Создаётся `closed`; удалить можно только закрытое окно; при удалении адрес `target_window_id` у ожидающих талонов сбрасывается; удалённое окно недоступно для открытия (`404 WINDOW_NOT_FOUND`), в АРМ и аналитике его нет; `service_ids` — только активные услуги отделения, дубли схлопываются |
| `/admin/employees` | GET, GET `/{id}`, POST, PUT `/{id}`, DELETE `/{id}` | `full_name`, `active` | Нельзя отключить сотрудника, работающего в открытом окне |

Ответ окна: `{ id, name, status, active, current_employee_id, service_ids }`. В 8.1 `windows` добавить колонку
`active boolean NOT NULL DEFAULT true` (миграция V5). Каталог услуг в heartbeat и в `GET /public/branch` содержит только активные.

---

## 9. Рекомендации открыть окно (раздел 6.8, 9.3) [MVP-демо]

`GET /admin/recommendations` → `{ generated_at, recommend_open_window, overdue_min, overdue_count, services: [...] }`.

Услуга попадает в рекомендацию, если выполнено хотя бы одно условие:

| `reasons[]` | Условие |
|---|---|
| `eta_overrun` | ETA хотя бы одного её ожидающего талона позже его `due_at` больше чем на `recommend-overdue-min` минут |
| `overdue_count` | просроченных ожидающих талонов услуги больше `recommend-overdue-count` |
| `no_open_window` | есть ожидающие, а открытого окна с этой услугой нет (S2) |

Элемент `services[]`: `{ service_id, service_name, waiting, overdue, max_eta_overrun_sec, open_windows, reasons, suggested_windows: [{window_id, name}] }`.
`suggested_windows` — закрытые окна, обслуживающие услугу. Сортировка — по убыванию `max_eta_overrun_sec`. Окно система не открывает: решение за руководителем.
Новые ключи 6.9: `recommend-overdue-min: 10`, `recommend-overdue-count: 5`.

---

## 10. HeuristicScoringStrategy и журнал вызова (раздел 6.5, 16.2) [MVP-демо]

`score = 1 / predicted_duration_sec`. Прогноз — каскад по `handling_stats`; уровень берётся, если `sample_count >= heuristic-min-samples` (6.9, по умолчанию 5):

1. `employee_service_category` — сотрудник + услуга + категория;
2. `employee_service` — сотрудник + услуга (средневзвешенное по категориям);
3. `service_category` — услуга + категория (все сотрудники);
4. `service` — услуга (все сотрудники и категории);
5. `fallback` — `default-service-duration-sec`.

Переключение — `scoring-strategy: heuristic` в конфигурации (в том числе сообщением центра). Дедлайн остаётся ограничителем: при просроченных
головах score не применяется, вне `slack` решает EDF.

`ticket.called` теперь всегда содержит оценки всех кандидатов и `model_version` выбранной стратегии
(`handling_stats-v1` у heuristic, `const` у uniform):

```json
{ "reason": "score_in_slack", "scoring_strategy": "heuristic", "model_version": "handling_stats-v1",
  "candidates": [ { "ticket_id": "…", "display_number": "О-004", "service_id": "…", "due_at": "…", "score": 0.0083,
                    "predicted_duration_sec": 120, "basis": "service_category", "samples": 10 } ] }
```

`handling_stats` копит целые секунды: обслуживание короче секунды в статистику не попадает.
Оценка ETA берёт среднее по услуге при `sample_count >= heuristic-min-samples` (раньше порог был константой 5).

---

## 11. load-generator (раздел 3.2, 16.1) [MVP-демо]

`tools/load-generator/LoadGenerator.java` — один файл без зависимостей: `java tools/load-generator/LoadGenerator.java <режим>`.

| Режим | Что делает |
|---|---|
| `live` | Поток клиентов (QR 60%, живая очередь 40%, наплыв ×2 во второй половине) и операторов на запущенной ноде через REST: `call-next` → `start` → `complete`, 3% неявок. Закономерности 16.1: разная длительность услуг (посылка 240 с, отправка 420 с, финансы 600 с), `pensioner` дольше (×1,2…1,5), быстрый (×0,75) и медленный (×1,35) сотрудник, лог-нормальный шум. `--speed` ускоряет обслуживание |
| `history` | SQL-файл с историей за `--days` дней: обслуженные талоны, журнал `ticket.*`, `window.opened/closed`, пенсионные дни (3, 13, 23 число) и обеденный пик; `handling_stats` пересобирается из обслуженных талонов. Идемпотентен (талоны `ON CONFLICT DO NOTHING`) |

Сравнение uniform и heuristic — `UniformVsHeuristicTest` (детерминированная симуляция на настоящем `QueueSelection`); таблица для
отчёта пишется в `target/simulation/uniform-vs-heuristic.md`. Метка результата — [Проверено на синтетике].

---

## 11а. Heartbeat: поля версий конфигурации (раздел 4.1, 10.1)

| Поле | Смысл |
|---|---|
| `config_version` | Версия конфигурации, **опубликованная центром** и применённая узлом (`branch_config.central_version`). По ней центр видит, дошла ли его версия; значение совпадает с `central_version` в событии `config.applied` |
| `effective_config_version` | Действующая версия: растёт при любом изменении (версия центра или локальное переопределение руководителя). Именно она записывается в `tickets.config_version` и в `config_version` конверта событий |

---

## 12. Чего нет в доке и нет в коде (нужно решить или сделать)

| Что | Замечание |
|---|---|
| OpenAPI (springdoc) | Раздел 15 предполагает клиент, сгенерированный из OpenAPI. В `node-app` springdoc не подключён. Пока контракт — этот документ и `docs/agents/admin-screen-task.md` |
| Формат `notification.requested` для центра | см. п. 3; центр его пока не читает |
| Определение «нормы» для `slow_window` | В доке не задано; принято в коде: п. 5, ключ `slow-window-factor: 1.5` (6.9) |
| Истечение записи: S8 (`slot + grace_late_min`) против `late-checkin-retention-min: 60` | Решение заказчика: остаётся настраиваемым конфигом, по умолчанию 60; S8 в доке привести в соответствие |

Реализовано после первой версии этого файла: `auto-call-on-free` (6.4: автовызов при `open` и после `complete`, по умолчанию выключен); при `scoring-strategy: heuristic` ETA считается по каскаду со скоростью сотрудника окна (16.3), при `uniform` — по среднему услуги.
