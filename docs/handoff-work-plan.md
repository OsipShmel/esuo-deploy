# ЕСУО — передача контекста в новый чат: промпт, как работает система, work plan

Файл самодостаточен: его можно целиком вставить в новый чат. Состояние на 20 сентября 2026, ветка `WiP/day3_nikitos`
(слияние в `develop` готовилось; `docs/` в репозитории игнорируется через `.gitignore`).

---

## 1. Промпт для нового чата (скопируйте как первое сообщение)

```text
Ты помогаешь разработчику A (Domain/Core) на хакатоне: проект ЕСУО — электронная система управления очередью почтовых отделений.
Стек: Java 21 (Spring Boot), PostgreSQL 16, NATS JetStream (hub + leaf), Docker Compose, фронтенд пишут ИИ-агенты (React + TS).

Правила работы:
1. Источник истины — дизайн-документ (ESUO-design-doc.md, свежий, лежит у меня в Downloads) и его дополнения из
   docs/ESUO-design-doc-additions.md. Старый work plan устарел. Если что-то расходится с дизайн-доком, следуй доку;
   если в доке чего-то нет — не выдумывай молча, а опиши отдельно как «нет в доке».
2. Не запускай тесты и не делай git commit/merge сам, если я не попрошу. Вместо этого говори, как мне проверить самому
   и на что смотреть в выводе. Компиляция (`./mvnw -q -pl node-app -am test-compile`) допустима.
3. Не читай docs/ в репозитории как источник требований: они могут отставать. Требования — из дизайн-дока, который я прикладываю.
4. Тесты интеграционные, на Testcontainers (нужен Docker). Запуск отдельных: добавляй -Dsurefire.failIfNoSpecifiedTests=false.
5. Пиши по-русски, коротко и по делу; ссылки на файлы давай относительными путями.
6. Мои части: домен и ядро очереди узла (java-backend/node-app), тесты, аналитика, конфигурация узла, load-generator, постановка
   задач агентам на экран admin. Часть разработчика B — центр (center-app), NATS-топология, mp/notify, chaos, деплой.

Прикладываю: дизайн-док, этот файл. Текущая задача: <вставить>.
```

---

## 2. Что за система (кратко)

Каждое отделение (ОПС) держит у себя **узел** `node-app` (Spring Boot + PostgreSQL + NATS leaf): очередь, API, SSE, статическая
раздача UI. **Центр** `center-app` (NATS hub, публичный шлюз HTTP→NATS RPC, реестр отделений по heartbeat, ingest событий,
SSE талона; в плане — `config`, `mp`, `notify`). Связь только через NATS leaf, соединение инициирует отделение.
Очередь отделения работает **автономно** при потере связи с центром: PostgreSQL узла — единственный источник истины.

Три способа попасть в очередь: **предварительная запись** (веб или МП, статус `booked` до check-in), **QR-талон** в отделении,
**живая очередь** (оператор добавляет клиента). Клиент идентифицируется токеном талона (`public_token`), без аккаунтов.

| Компонент | Роль |
|---|---|
| Узел (node-app) | очередь, окна, алгоритм выбора, ETA, таймеры, outbox, RPC-обработчики, SSE, аналитика отделения, конфигурация |
| Центр (center-app) | публичный API, RPC-мост к узлам, реестр по heartbeat, ingest событий (`CENTER_EVT`), SSE талона |
| NATS | `BRANCH_EVT` (узел→центр, через outbox), `BRANCH_INBOX` (центр→узел: `CONFIG`, `MP_INBOX`), RPC `branch.{b}.rpc.*`, heartbeat `branch.{b}.hb` |
| PostgreSQL узла | талоны, окна, журналы `ticket_events`/`window_events` (append-only), `outbox`, `inbox_processed`, `branch_config`, `handling_stats` |

Ключевые правила: любое изменение талона — одна транзакция вместе с `ticket_events` и `outbox`; события уходят в NATS только после коммита;
доставка «хотя бы один раз» + идемпотентные потребители; доменный слой не знает про NATS/HTTP; все коэффициенты — в конфигурации; ошибки —
`ProblemDetail` с кодами (`ErrorCode`).

---

## 3. Как работает построение очереди

Очередь — не структура в памяти, а выборка ожидающих талонов из БД. Порядок задают два момента времени каждого талона:
`eligible_from` (с какого момента можно вызывать) и `due_at` (дедлайн).

| Источник / событие | `eligible_from` | `due_at` |
|---|---|---|
| QR, живая очередь | момент выдачи | выдача + целевое ожидание (`target-wait-min` по источнику и категории: QR 20 / пенсионер 15 / СВО 5; walk-in 30 / 20 / 5) |
| Запись, check-in вовремя | `max(check-in, slot − early-checkin-min)` | время слота |
| Запись, опоздание > `grace-late-min` | момент check-in | check-in + целевое ожидание QR (`late-policy=convert_to_qr`; `reject` — отказ `SLOT_EXPIRED`) |
| «Отложить» | сейчас + минуты (лимит 1 раз, до 30 мин) | не меняется |
| «Вернуть» `keep_position` | сейчас | прежний `due_at` (голова очереди); `to_tail` — пересчитывается |
| «Перенаправить» | как было | прежний `due_at`; меняется услуга или `target_window_id` |

**Вызов следующего** (`CallNext`, оператор нажимает «Следующий»; автовызова нет): в одной транзакции блокируется строка окна
(`FOR UPDATE`), проверяется, что окно `open` и без активного талона; по каждой услуге окна берётся одна **голова** — первый допущенный
ожидающий талон по `(due_at, seq)` с `FOR UPDATE SKIP LOCKED` (адресный талон видит только своё окно). Выбор (`QueueSelection`):

1. есть просроченные головы (`due_at ≤ сейчас`) → самая ранняя по дедлайну, оценка не действует (`overdue_edf`);
2. иначе, если несколько голов с `due_at ≤ сейчас + slack-min` (5 мин) → максимум оценки (`score_in_slack`);
3. иначе чистый EDF (`edf`). Ничья — по `seq`.

Оценка (`scoring-strategy`): `uniform` — 1 для всех (получается EDF); `heuristic` — `1 / прогнозная длительность`, прогноз — каскад по
`handling_stats` (сотрудник+услуга+категория → сотрудник+услуга → услуга+категория → услуга → `default-service-duration-sec`; уровень принимается
при `sample_count ≥ heuristic-min-samples`). Оценка выбирает только **между разными услугами**, внутри услуги порядок не меняется.

Защита от двойного назначения: блокировка окна, `SKIP LOCKED`, `UPDATE` с проверкой версии, уникальный индекс «один активный талон на окно».
Решение журналируется (`ticket.called`: `reason`, `scoring_strategy`, `model_version`, кандидаты с `score`, `predicted_duration_sec`, `basis`, `samples`).

Жизненный цикл: `booked → waiting → called → serving → done`; `called` без явки → повторные вызовы по таймауту 90 с (до `max-recalls`=2) → `no_show`;
`requeue`, `redirect`, `postpone`, `cancel`, `no-show`; закрытие окна `graceful` (дообслужить и закрыть) или `force` (талон возвращается в голову с прежним `due_at`).
Таймеры без состояния в памяти (S5 повторный вызов, S8 истечение записи), пережимают рестарт (S12).

**ETA и позиция** (`QueueSimulation`, чистая функция): открытые окна раздают ожидающие талоны по тем же правилам (uniform), ETA — момент назначения окну;
учитываются допуск, адресные окна, остаток текущего обслуживания. Нет открытого окна с услугой → `eta=null`, `confidence=none` (S2).
**Уведомление «очередь приближается»:** периодически (5 с) для талона с `position ≤ 3` или `eta ≤ 5 мин` ставится флаг и пишется `notification.requested`
(`dedup_key = ticket_id:approaching`); клиент получает его и по SSE узла.

---

## 4. Конфигурация, аналитика и остальное, что уже есть в узле

- **Конфигурация:** `queue-defaults.yml` (`config-version: 4`) + версии центра из `BRANCH_INBOX` (`ConfigMessage`, частичное наложение, `bounds`, пользователи, сотрудники)
  + локальные переопределения руководителя в границах (`GET/PUT /admin/config`, `422 CONFIG_OUT_OF_BOUNDS`). `effective_version` растёт при любом изменении и пишется в талоны.
  **Версии центра — строго больше применённой (первая ≥ 5).**
- **Записи МП:** сообщение в `center.mp.inbox.branch.{b}` (`Nats-Msg-Id = mp_appointment_id`) → талон `booked` → событие `mp.appointment.confirmed|rejected`.
  Токен из отделения не уходит; центр получает его RPC `ticket.by_mp` и собирает ссылку.
- **Аналитика руководителя** (`/admin/analytics/summary|windows|deviations|unfinished`), отклонения: просроченные, неявки, долгие обслуживания, простой окон (S14), услуга без окна (S2), открытые инциденты.
- **Инциденты** (`POST /incidents`, `PATCH /admin/incidents/{id}`), **рекомендации по окнам** (`GET /admin/recommendations`), **справочники** (`/admin/services|windows|employees`, мягкое удаление).
- **Heartbeat:** `config_version` (версия центра) и `effective_config_version`; `services` только активные.
- **load-generator** (`tools/load-generator/LoadGenerator.java`): `live` (поток клиентов и операторов через REST) и `history` (SQL-история за N дней).
- **Роли:** `OPERATOR`, `BRANCH_ADMIN` (JWT от узла); `/api/v1/admin/**` — только `BRANCH_ADMIN`; публичные пути `/api/v1/public/**`.

---

## 5. Что сделано (по дням дизайн-дока, часть A)

| День | Статус |
|---|---|
| 1 | ✅ схема, выдача ×3 источника, идемпотентность |
| 2 | ✅ окна, `call-next`, `Uniform`, complete/requeue/redirect/recall/no-show, тесты конкурентности |
| 3 | ✅ check-in, postpone, таймеры, ETA, уведомление о приближении, `RestartRecoveryIT`, `CheckInIT`, `PostponeIT` |
| 4 | ✅ конфигурация из `BRANCH_INBOX`, переопределения и границы, аналитика, отклонения, незавершённые |
| 5 | ✅ `handling_stats`, `HeuristicScoringStrategy`, `load-generator`, рекомендации, сравнение uniform/heuristic, справочники, `NotificationOutageIT`, `HeartbeatIT` |
| 6 | ❌ багфиксы по итогам прогонов, `docs/demo/results.md` с реальными числами, репетиция и заморозка |

Тесты: около 150+, все зелёные (`./mvnw -pl node-app -am test`, нужен Docker). Проверки ТЗ №2–7, №9, №10 закрыты на ноде; №1 и №8 зависят от центра (B).
Смоук на живом стенде: `scripts/smoke/day1.sh`, `day3.sh`, `day4.sh` (BRANCH_INBOX, аналитика, инциденты — прогнан, 23/25, два ложных FAIL исправлены), `day5.sh`.

---

## 6. Что осталось по нашей части (актуальный work plan)

**Сейчас (не код):**
1. Закоммитить остаток (heartbeat, `AbstractNodeIT`, тесты `CheckInIT`, `PostponeIT`, `NotificationOutageIT`, `HeartbeatIT`), слить `WiP/day3_nikitos` в `develop` (fast-forward), перед этим `git fetch`.
2. Убрать `/docs` из `.gitignore` (или переслать файлы вручную): иначе материалы для B в репозиторий не попадут.
3. Занести в `docs/demo/results.md` реальные числа: итог тестов, дата, коммит, таблица из `java-backend/node-app/target/simulation/uniform-vs-heuristic.md`, результаты `day4.sh`/`day5.sh`.
4. Перенести `docs/ESUO-design-doc-additions.md` в дизайн-док (правило 0.3.1: документ первым).

**Решения от заказчика:**
5. **S8:** в доке «истекает в `slot + grace_late_min`» (10 мин), а `late-checkin-retention-min: 60` (6.9) и код используют 60 мин — выбрать одно.
6. **`auto-call-on-free`:** реализовать автовызов при открытии окна и после `complete` или убрать ключ (сейчас не используется).
7. Пересмотреть значения `target-wait-min` и `grace-late-min` (в файле помечены).

**Постановка задач агентам:**
8. Экран `admin`: постановка готова (`docs/agents/admin-screen-task.md`), нужно отдать агенту и принять результат.
9. Экран `operator` (вместе с B): постановка не написана; ручки на узле есть (`call-next`, `start`, `complete`, `recall`, `no-show`, `requeue`, `redirect`, `walkin`, `incidents`, `/operator/stream`).

**Необязательное:**
10. Кеш `estimateAll()` (~1 с): сейчас `TicketViews.of()` гонит симуляцию на каждый талон; SSE узла делает это на каждый снапшот подписчика.
11. Учёт сотрудника в ETA (16.3): каскад по сотрудникам работает только в scoring.
12. Отклонение «окно обслуживает медленнее нормы» (16.3): данные в `handling_stats` есть, ручки нет.
13. Кеширование `PolicyRepository.current()`: вызывается очень часто, каждый раз разбирает JSON.

**Общее с B и командой:**
14. OpenAPI (springdoc) в `node-app` и `center-app` — сейчас не подключён, агенты работают по markdown-контрактам.
15. Ротация подписанного QR (13.4): в узле статический `demo:<branch>`.
16. Стенд из трёх ПК, репетиция демо (5 минут, раздел 20.2), презентация, видео, заморозка версии.

**Порядок вырезания при нехватке времени (по доку):** рекомендации по окнам → `HeuristicScoringStrategy` → ротация QR → обзор сети → права subjects в NATS.
**Не вырезается:** десять проверок ТЗ, автономность отделения, outbox, RPC-мост, запуск одной командой.

**Часть B (по коду, уточнить):** центр — модули `config`, `mp` (вебхук, `MP_INBOX`, callback через `ticket.by_mp`), `notify` (ретраи, CB, DLQ, TTL, `notify-mock`), `mp-simulator`;
chaos-скрипты (`restart-node.sh`, `notify-down.sh`), k6 `issue.js`, rate limit, обзор сети, внешний деплой, задачи агентам на `client`, `board`, `network`. Подробности для B — `docs/for-developer-B.md`.

---

## 7. Как запускать и проверять

```bash
# компиляция без тестов
cd java-backend && ./mvnw -q -pl node-app -am test-compile

# все тесты (Docker обязателен)
cd java-backend && ./mvnw -pl node-app -am test

# отдельные тесты
cd java-backend && ./mvnw -pl node-app -am test -Dtest='CheckInIT,PostponeIT' -Dsurefire.failIfNoSpecifiedTests=false

# стенд: центр + два отделения (после старта подождать ~10 секунд, узел поднимается)
docker compose up -d --build
./scripts/smoke/day1.sh   # выдача талонов
./scripts/smoke/day3.sh   # RPC через центр, недоступное отделение, два окна, разрыв
./scripts/smoke/day4.sh   # аналитика, инциденты, BRANCH_INBOX (нужны docker и nats-box)
./scripts/smoke/day5.sh   # справочники, рекомендации, heuristic, load-generator (нужна java)

# нагрузка и история
java tools/load-generator/LoadGenerator.java live --minutes 2 --speed 60
java tools/load-generator/LoadGenerator.java history --days 14 --out /tmp/h.sql
docker exec -i esuo-postgres-b01 psql -U esuo -d esuo < /tmp/h.sql
```

Демо-логины: `operator`, `operator2`, `admin`; пароли — переменные `DEMO_OPERATOR_PASSWORD`, `DEMO_ADMIN_PASSWORD` из `.env`. Порты: узел b01 — 8080, b02 — 8090, центр — 8081, hub NATS — 4222/8222.

---

## 8. Подводные камни

- **Изоляция тестов:** общая БД на все классы; `AbstractNodeIT.resetState()` чистит талоны, журналы, `handling_stats`, `inbox_processed`, возвращает конфигурацию очереди и услуги демо-окон.
  `windows.open` заменяет набор услуг окна насовсем — тесты, открывающие окно, должны учитывать это.
- **`handling_stats` копит целые секунды:** обслуживание короче секунды не записывается (в тестах сдвигайте `called_at` назад).
- **Тесты `CheckInIT` со слотом в прошлом** не рассчитаны на запуск между 00:00 и 01:30 по Москве.
- **Журналы `ticket_events` и `window_events` защищены триггерами** от изменения и очистки; в тестах для сброса отключается `session_replication_role`.
- **`mvn test` и `mvn package` без `-DskipTests`** теперь поднимают Testcontainers; `Dockerfile` собирает с `-DskipTests`.
- **`config_version` в талонах — это `effective_version`**, а в heartbeat `config_version` — версия центра (не путать).
- **Кириллица в query-параметрах без кодирования** Tomcat отклоняет HTML-страницей ещё до приложения — клиент обязан кодировать значения.
- **`docs/` игнорируется git**: файлы для команды нужно пересылать вручную или убрать игнор.

---

## 9. Карта репозитория (что где искать)

```text
java-backend/
  shared-contracts/   контракты: ErrorCode, EventEnvelope, Subjects, TokenHash, RPC, DTO, inbox (ConfigMessage, MpAppointmentMessage)
  node-app/           honey.road.node
    queue/domain/       QueueSelection, QueueSimulation, Ticket, QueuePolicy — без Spring Web и NATS
    queue/application/  CallNext, IssueTicket, TicketCommands, WindowCommands, WaitTimeEstimator, ApproachingNotifier,
                        BranchConfigService, MpAppointmentHandler, AnalyticsQueries, Incidents, Recommendations, AdminCatalog, ConfigRules
    queue/scoring/      Uniform / HeuristicScoringStrategy, ScoreResult
    api/                publicapi, operator, admin, sse, error
    messaging/          nats, outbox, rpc, heartbeat, inbox (BranchInboxConsumer, BranchInboxProcessor)
    scheduler/          QueueTimers, ApproachingScheduler
    persistence/        репозитории (JDBC)
    src/main/resources/db/migration/  V1–V5
    src/test/.../it/    интеграционные тесты (Testcontainers), queue/domain — юнит и симуляции
  center-app/         honey.road.center (B)
infrastructure/config/queue-defaults.yml   коэффициенты очереди (config-version 4)
infrastructure/nats/                       hub.conf, leaf.conf
tools/load-generator/LoadGenerator.java
scripts/smoke, scripts/chaos
docs/                  (в git игнорируется) work plan, additions, for-developer-B, agents/admin-screen-task, demo/results
```
