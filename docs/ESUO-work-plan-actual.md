# ЕСУО — актуальный план работ (хакатон)

Основа — дизайн-док (свежая версия, раздел 21 и 20). Старый work plan учтён только там, где не противоречит доку.
Статусы сверены с кодом ветки `develop` на момент составления. Часть B оценена по структуре кода и требует подтверждения с B.

Обозначения: ✅ сделано и проверено тестами · 🟡 сделано частично · ❌ не сделано · ❓ нужно решение

---

## 1. Где мы сейчас

| Область | Статус |
|---|---|
| Схема БД, миграции V1–V4, ограничения целостности (8.3) | ✅ |
| Выдача талонов ×3 источника, идемпотентность, номера, слоты | ✅ `QrIssueIT`, `AppointmentCreateIT`, `ConcurrentIssueIT` |
| Окна open/close (graceful/force), call-next (EDF + допуск + slack + score) | ✅ `CallNextIT`, `CloseWindowIT`, `ConcurrentCallNextIT` |
| complete / start / recall / no-show / requeue / redirect / check-in / postpone | ✅ (redirect и requeue покрыты тестами, check-in и postpone — нет) |
| Таймеры S5 (повторный вызов → no_show), S8 (истечение записи) | ✅ `RestartRecoveryIT` (S12) |
| ETA: симуляция по 6.8 (`QueueSimulation`) | ✅ `QueueSimulationTest`, `ApproachingNotificationIT` |
| Уведомление «очередь приближается» (запись в outbox) | ✅ |
| Конфигурация центра из `BRANCH_INBOX`, пользователи, локальные переопределения в границах | ✅ `BranchInboxIT` (без реального NATS) |
| Записи МП: приём на ноде, confirmed / rejected | ✅ `BranchInboxIT` |
| `node-inbox` консьюмер (транспорт NATS) | 🟡 написан, проверен только компиляцией — нужен прогон на стенде |
| Аналитика отделения, отклонения, рекомендации | ❌ |
| Инциденты (`POST /incidents`, `PATCH /admin/incidents/{id}`) | ❌ |
| Admin CRUD справочников (`/admin/services`, `/windows`, `/employees`) | ❌ (путь закрыт ролью, контроллеров нет) |
| `handling_stats` → `HeuristicScoringStrategy`, `load-generator` | 🟡 статистика копится, стратегии и генератора нет |
| Центр: шлюз, RPC, реестр по heartbeat, ingest, SSE талона | ✅ (по коду) |
| Центр: `config`, `mp`, `notify`, DLQ | ❌ (по коду) |
| Фронтенд (SPA) | ❌ |

---

## 2. Что осталось — разработчик A

> **Обновление (после дня 5 по доку).** Сделано и покрыто тестами (запуск тестов — за автором): A0.1–A0.3, A1 аналитика, A2 инциденты,
> A3 admin CRUD, A4 частично (постановка `admin` в `docs/agents/admin-screen-task.md`), A6 `HeuristicScoringStrategy`, A7 `load-generator`
> (`tools/load-generator`), A8 сравнение (`UniformVsHeuristicTest` → `target/simulation/…md`), A9 рекомендации, A10 гарантия ограничения ожидания
> (`UniformVsHeuristicTest.longServiceIsNotStarvedByAStreamOfShortOnes`). A5 тоже написан (`CheckInIT`, `PostponeIT`, `NotificationOutageIT`), заготовка `docs/demo/results.md` создана. **Осталось:** прогнать тесты и `day5.sh`
> и занести реальные числа в `docs/demo/results.md`, A11–A14, перенос `ESUO-design-doc-additions.md` в дизайн-док, OpenAPI. Таблицы ниже — исходный план.

### 2.1. Блокеры и решения (сделать первыми, это быстро)

| # | Задача | Зачем |
|---|---|---|
| A0.1 | ❓ **Ссылка на статус для МП.** Центру для callback нужен `public_token`, а из отделения он не уходит (`EventEnvelope` отклоняет ключ). Выбрать: отдельное поле `status_token` только в `mp.appointment.confirmed` (и снять проверку для него) **или** ссылка по `mp_appointment_id` с поиском токена через RPC `ticket.get` | Без решения B не может собрать callback в МП |
| A0.2 | Внести в дизайн-док (правило 0.3.1 — документ первым): формат `ConfigMessage` и `MpAppointmentMessage`, payload `config.applied`, `mp.appointment.confirmed/rejected`, `notification.requested` (`dedup_key`, `kind`, `position`, `eta`) | Контракты для B и агентов |
| A0.3 | Прогнать `BRANCH_INBOX` на стенде вручную (`nats pub` в `center.config.branch.b01` и `center.mp.inbox.branch.b01`), убедиться в `outcome=APPLIED` в логах ноды | Транспорт — единственная непроверенная часть |

### 2.2. Приоритет P0 — без этого не проходят проверки ТЗ

| # | Задача | Проверка / критерий готовности |
|---|---|---|
| A1 | **Аналитика отделения** (`GET /admin/analytics/summary`, `/windows`, `/deviations`, `/unfinished`): обслужено, среднее и максимальное ожидание по источникам и услугам; загрузка окон (время открытия, обслуживания, простой, число клиентов); отклонения — просроченные талоны, `no_show`, долгие обслуживания, окна без активности (S14, `idle_alert_min` — добавить в конфиг), услуги без открытых окон (S2), инциденты; незавершённые за период | `AnalyticsSummaryIT` (проверка ТЗ №10). Работает офлайн, по локальной БД |
| A2 | **Инциденты**: `POST /incidents` (`kind`, `description`, `window_id?`, `ticket_id?`), `PATCH /admin/incidents/{id}`, событие `incident.created` | Инцидент виден в `deviations`, событие в outbox |
| A3 | **Admin CRUD** справочников: `/admin/services`, `/admin/windows`, `/admin/employees` (мягкое удаление), `PUT /admin/windows/{id}/services`. Договориться с B, кто пишет контроллеры; сервисы под ними — A | Нужно агентам на `admin` и для демо (руководитель меняет окна и услуги) |
| A4 | **Постановка задач агентам на `admin`** (экран руководителя): по OpenAPI и разделу 15, после A1–A3 | Готов экран `admin`: очередь, аналитика, отклонения, конфиг |
| A5 | **Тесты сценариев, которых не хватает**: `CheckInIT` (S6 раньше времени, S7 опоздание с `convert_to_qr` и `reject`, S8 истечение), `PostponeIT` (лимит, допуск), `NotificationOutageIT` (вместе с B; на стороне A — что очередь не зависит от доставки) | Все 10 проверок ТЗ покрыты автотестом |

### 2.3. Приоритет P1 — заметно улучшает демо

| # | Задача | Критерий |
|---|---|---|
| A6 | **`HeuristicScoringStrategy`**: каскад 5 уровней из 16.2 (сотрудник+услуга+категория → … → `fallback_duration_sec`), `min_samples`, в `ticket.called` — уровень каскада и число наблюдений | `HeuristicScoringTest` на детерминированной статистике; при равных дедлайнах в пределах `slack` выбирается более короткое обслуживание |
| A7 | **`load-generator`** с синтетической историей (16.1): разные длительности услуг, `pensioner` дольше, быстрый и медленный сотрудник, наплыв в обед | Заполняет `handling_stats` и данные для аналитики; запускается одной командой |
| A8 | **Сравнение uniform и heuristic** на прогоне генератора → `docs/demo/results.md` с меткой [Проверено], датой и коммитом | Таблица пропускной способности, данные для презентации |
| A9 | **Рекомендации по окнам** (`GET /admin/recommendations`, 6.8): ETA превышает `due_at` больше чем на `overdue_min` или просроченных больше `overdue_count` | Видна в демо-шаге 8 |
| A10 | **`QueueFairnessTest`**: детерминированная симуляция перегрузки, гарантия ограничения ожидания живой очереди | Зелёный тест |

### 2.4. Приоритет P2 — если останется время

| # | Задача |
|---|---|
| A11 | Кеш `estimateAll()` на ~1 с: `TicketViews.of()` сейчас гонит симуляцию на каждый талон, в списке `/queue` из N талонов это N симуляций |
| A12 | `auto_call_on_free`: флаг есть в конфиге, но нигде не используется; либо реализовать (авто-вызов при `open` и `complete`), либо убрать из конфига и дока |
| A13 | Учёт сотрудника в прогнозе длительности (ETA «с учётом скорости конкретных сотрудников», 16.3) — подмена источника `durationSec` в `WaitTimeEstimator` |
| A14 | Пересмотреть значения `target-wait-min` и `grace-late-min` в `queue-defaults.yml` (в файле помечено «стоит пересмотреть»), убрать устаревшие комментарии |

### 2.5. Общее для A и B

- Контракты перед стартом задач агентам: OpenAPI (springdoc) должен быть актуален, иначе агенты пишут по устаревшему.
- Стенд из трёх ПК и репетиция демо.
- Презентация и видео (день 6).

---

## 3. Что осталось — разработчик B (по коду, уточнить)

| Область | Статус | Что нужно |
|---|---|---|
| Центр: `config` (`PUT /config/global`, `/config/branches/{b}`, `POST /branches/{b}/users`), запись в `CONFIG` | ❌ | Формат — `ConfigMessage` из shared-contracts |
| Центр: `mp` — вебхук `POST /webhooks/mp/appointments` (HMAC, 202), публикация в `MP_INBOX` с `Nats-Msg-Id = mp_appointment_id`, callback в МП по `mp.appointment.*` | ❌ | Зависит от решения A0.1 |
| `mp-simulator` | ❌ | Шлёт вебхуки, принимает callback |
| `notify` worker: `NotificationGateway`, Resilience4j (таймауты, ретраи, circuit breaker), DLQ, проверка TTL, `GET /admin/notifications/dlq` | ❌ | Читает `notification.requested` (формат готов) |
| `notify-mock` с `/chaos` | ❌ | Для проверки ТЗ №8 |
| `GET /network/overview` со всеми аномалиями | 🟡 | Есть контроллер, сверить набор аномалий с 9.5 |
| Ротация подписанного QR, ограничение частоты (rate limit) | 🟡 | `QrCodes` на ноде есть, ротацию и лимиты проверить |
| Chaos-скрипты: `restart-node.sh`, `notify-down.sh` | ❌ | Есть только `cut-branch-link.sh` и `branch-link-test.sh` |
| k6-сценарий `issue.js` (проверка ТЗ №9 нагрузкой) | ❌ | Для демо-шага 4 |
| Наблюдаемость: структурированные логи, `/actuator/prometheus` | 🟡 | Логи в формате ECS есть |
| Внешний деплой центра, README «запуск одной командой» | 🟡 | Compose есть, деплой на VPS и README сверить |
| OpenAPI (springdoc) | ❓ | Проверить, подключён ли |
| Постановка задач агентам: `client`, `board`, `network`, `operator` (вместе с A) | ❌ | |

---

## 4. Фронтенд (ИИ-агенты)

Экраны по разделу 15: `client`, `board`, `operator`, `admin`, `network`. Сейчас в `frontend/` только README. Постановка: A — `admin`, B — `client`, `board`, `network`, `operator` — оба. Готовность OpenAPI и SSE-контрактов — предпосылка. **Это самый большой риск по срокам**: если задачи агентам не ушли, это нужно сделать сразу.

---

## 5. Матрица десяти проверок ТЗ

| # | Проверка | Автотест | Стенд / демо | Статус |
|---|---|---|---|---|
| 1 | Предварительная запись | `AppointmentCreateIT`, `BranchInboxIT` | Веб через центр и `mp-simulator` | 🟡 нет пути через центр и МП |
| 2 | QR-талон | `QrIssueIT` | Скан QR с табло | ✅ на ноде |
| 3 | Вызов клиента | `CallNextIT` | АРМ оператора | ✅ на ноде |
| 4 | Два окна без двойного назначения | `ConcurrentCallNextIT` | Демо-шаг 4 (скрипт) | ✅ на ноде |
| 5 | Закрытие окна с активным клиентом | `CloseWindowIT` | Демо-шаг 5 | ✅ на ноде |
| 6 | Перенаправление | `RedirectIT` | Демо-шаг 5 | ✅ на ноде |
| 7 | Рестарт ноды | `RestartRecoveryIT` | `chaos/restart-node.sh` | 🟡 нет скрипта |
| 8 | Недоступны уведомления | `NotificationOutageIT` | `notify-mock /chaos` | ❌ нужны notify и mock (B) |
| 9 | Конкурентное создание талонов | `ConcurrentIssueIT` | k6 | 🟡 нет k6 |
| 10 | Аналитика руководителя | `AnalyticsSummaryIT` | Экран `admin` | ❌ A1 |

Дополнительно по доку: разрыв связи и докачка (`cut-branch-link.sh`, есть), падение центра, падение локального NATS, `QueueFairnessTest` (A10).

---

## 6. Порядок работ

| Этап | A | B | Общий выход |
|---|---|---|---|
| **Сразу** | A0.1–A0.3, задачи агентам на `admin` (с заглушкой OpenAPI) | Центр `config` и `mp`, `mp-simulator`; задачи агентам на `client`, `board` | Записи МП и конфигурация идут в отделение |
| **Далее** | A1, A2, A3 (аналитика, инциденты, admin CRUD), A5 | `notify` + Resilience4j + DLQ + `notify-mock`, network overview | Проверки №8, №10 проходят вручную |
| **Затем** | A6–A9 (heuristic, load-generator, сравнение, рекомендации) | Chaos-скрипты, k6, rate limit, ротация QR | Все 10 проверок проходят автотестом |
| **Финал** | Багфиксы, `docs/demo/results.md`, A10, A11 | Внешний деплой, README, финализация OpenAPI | Репетиция демо, видео, презентация, заморозка версии |

**Порядок вырезания при нехватке времени** (из дока): рекомендации по окнам (A9) → `HeuristicScoringStrategy` (A6–A8) → ротация QR → обзор сети → права subjects в NATS.
**Не вырезается:** десять проверок ТЗ, автономность отделения, outbox, RPC-мост, запуск одной командой.

---

## 7. Риски

| Риск | Что делать |
|---|---|
| Фронтенд не начат | Задачи агентам ставить немедленно, не ждать готовности всего бэкенда |
| Проверка №8 зависит только от B | Согласовать порядок: `notify` и `notify-mock` — в первую очередь после `mp` |
| `node-inbox` не проверен на живом NATS | A0.3 в ближайшее время; поведение сообщений лучше увидеть до интеграции с центром |
| Докачка после обрыва на стенде идёт до ~1 мин | Учесть в сценарии демо (шаг 7): заложить паузу |
| Дизайн-док отстаёт от кода (форматы сообщений) | A0.2 до того, как B начнёт `config`/`mp` |

---

## 8. Ссылки на тесты и как запускать

```bash
cd java-backend && ./mvnw -pl node-app -am test          # все тесты, нужен Docker
cd java-backend && ./mvnw -pl node-app -am test -Dtest=QueueSimulationTest,ConfigRulesTest   # без Docker
```

Ожидаемо: 80 тестов (55 прежних, 25 новых для конфигурации и МП). После добавления A1–A5 матрица из раздела 5 должна стать полностью зелёной.
