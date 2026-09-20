# Задача для агента: экран `admin` (руководитель отделения)

Ты пишешь приложение `admin` в проекте `frontend/` (React 18 + TypeScript + Vite + TanStack Query). Оно работает **только на узле отделения**
(`node-app`) и открывается руководителем отделения. Язык интерфейса — русский. Контракт — этот файл; расхождения с реальным API
считай ошибкой API, а не поводом менять контракт: остановись и сообщи.

OpenAPI-спецификации пока нет (springdoc в `node-app` не подключён), поэтому типы и клиент пиши вручную по таблицам ниже
и держи их в одном модуле `src/admin/api.ts`. Когда спецификация появится, этот модуль заменяется сгенерированным клиентом.

## Общие правила (нарушать нельзя)

1. Базовый адрес API — `/api/v1` того же хоста, где открыт фронтенд (без хардкода). Разработка: прокси Vite на `http://localhost:8080`.
2. Все ключи JSON — **snake_case**, времена — ISO-8601 в UTC (`2026-09-21T07:00:00Z`); показывай в часовом поясе отделения (`Europe/Moscow`,
   брать из `GET /public/branch` → `timezone`).
3. Аутентификация: `POST /auth/login` → `access_token` (JWT, 12 ч). Хранить в памяти и `sessionStorage`, отправлять заголовком
   `Authorization: Bearer …`. Роль `BRANCH_ADMIN` нужна для всего под `/admin`. При `401` — на экран входа.
4. Ошибки приходят как `application/problem+json` (RFC 9457) с полями `code`, `title`, `detail`, `request_id`, `errors[]`.
   **Ветвись по `code`, не по тексту.** Показывай `title`/`detail` пользователю, `request_id` — мелко, для поддержки.
5. Живые данные — SSE `GET /admin/stream?access_token=<jwt>` (браузерный `EventSource` не умеет заголовки, поэтому токен в query;
   только для путей `…/stream`). Первое сообщение `snapshot`, затем события; раз в 15 с `heartbeat`; при обрыве `EventSource`
   переподключается сам — после переподключения жди новый `snapshot`. Если SSE недоступен, опрашивай `GET /queue` раз в 10 с.
6. Команды с изменением состояния талона передают `expected_version` (из последнего полученного объекта). Ответ `409 STALE_STATE` —
   покажи «данные изменились» и обнови.
7. Доступность: контраст AA, крупные кнопки, переключатель увеличенного шрифта и высокой контрастности.

## Экраны и ручки

### 1. Вход
`POST /auth/login` `{login, password}` → `{access_token, token_type, expires_at, role, employee_id}`. Демо: `admin` / пароль из окружения.
Роль не `BRANCH_ADMIN` — сообщение «нужны права руководителя».

### 2. Очередь в реальном времени
Источник: `GET /admin/stream` (снапшот `{windows: WindowView[], queue: QueueItem[]}`, далее события `ticket.*`, `window.*`) и `GET /queue?service_id&status`.

`QueueItem`: `ticket_id, display_number, service_id, service_name, source (appointment|qr|walkin), client_category, status (booked|waiting|called|serving), issued_at, slot_start, eligible_from, due_at, overdue (bool), position, window_id, target_window_id, called_at, call_count, version`.

`WindowView`: `id, name, status (open|closing|closed), employee_id, service_ids[], active_ticket (TicketView|null), version`.

Показать: таблицу очереди (источник иконкой/меткой, просроченные выделены цветом **и** значком), карточки окон с активным талоном.
Руководитель может то же, что оператор: открыть/закрыть окно, «Следующий» — **это делает экран `operator`, здесь не нужно**. На `admin` — только просмотр.

### 3. Аналитика (проверка ТЗ №10)
Период: два поля даты (по умолчанию сегодня); параметры `from`, `to` — `2026-09-21` или ISO-момент; дата в `to` включает весь день.

| Ручка | Ответ |
|---|---|
| `GET /admin/analytics/summary` | `{from, to, totals: {issued, served, cancelled, no_show, active, avg_wait_sec, max_wait_sec, avg_service_sec}, by_source: [{source, issued, served, avg_wait_sec, max_wait_sec}], by_service: [{service_id, service_name, issued, served, avg_wait_sec, max_wait_sec, avg_service_sec}]}` |
| `GET /admin/analytics/windows` | `[{window_id, name, open_sec, serving_sec, idle_sec, served, avg_service_sec, utilization}]`, `utilization` от 0 до 1 |
| `GET /admin/analytics/deviations` | `{generated_at, from, to, counts: {overdue_ticket, no_show, long_service, idle_window, service_without_window, open_incident}, items: [{type, …}]}` |
| `GET /admin/analytics/unfinished` | `[{ticket_id, display_number, source, status, service_id, service_name, issued_at, slot_start, due_at, window_id}]` |

Секунды показывай как «12 мин 30 с»; `null` в среднем/максимуме — «нет данных», не «0». Пустой период — пустое состояние, не ошибка.
Показать: плитки итогов; таблицы по источникам и услугам; загрузку окон полосой (`utilization`) с подписью в процентах; вкладку
«Отклонения» с группировкой по `type` и счётчиком в заголовке группы; вкладку «Незавершённые».

Поля элементов отклонений:
- `overdue_ticket`: `ticket_id, display_number, service_id, source, due_at, overdue_sec`
- `no_show`: `ticket_id, display_number, service_id, source, reason, at`
- `long_service`: `ticket_id, display_number, service_id, window_id, employee_id, duration_sec`
- `idle_window`: `window_id, window_name, idle_sec, last_activity_at`
- `service_without_window`: `service_id, service_name, waiting`
- `open_incident`: `incident_id, kind (technical|operational), description, window_id, ticket_id, created_at`

### 4. Инциденты
Список — это элементы `open_incident` из отклонений. Кнопка «Закрыть»: `PATCH /admin/incidents/{id}` `{"status":"resolved"}` → инцидент.
Повторное закрытие безопасно. Создаёт инциденты оператор (`POST /incidents`), на `admin` создания нет.

### 5. Настройки приоритетов в границах центра
`GET /admin/config` → `{central_version, effective_version, central, local_overrides, bounds, effective}`.
`PUT /admin/config` — тело: объект переопределений, например `{"slack_min": 10}`; `{}` снимает все. Ошибка `422 CONFIG_OUT_OF_BOUNDS` —
покажи `detail` у поля.

Форму строй **из `bounds`**: для каждого ключа `bounds` — числовое поле с подсказкой «от min до max» и текущим значением из `effective`;
ключей без границ в форме нет (их менять нельзя). Вложенные (`target_wait_min` → источник → категория) — сгруппируй. Показывай
«центральное значение» рядом с локальным и кнопку «сбросить к центральному». Если `bounds` пуст — «Центр не разрешил локальные изменения».

### 6. Справочники: услуги, окна, сотрудники
Все ручки под `/admin`, роль `BRANCH_ADMIN`. Удаление мягкое: `DELETE` возвращает запись с `active=false`; списки без отключённых, `?include_inactive=true` — со всеми.

| Ресурс | Ручки | Тело |
|---|---|---|
| Услуги | `GET/POST /admin/services`, `GET/PUT/DELETE /admin/services/{id}` | `code` (только при создании: a-z, 0-9, `_`, `-`), `name`, `number_prefix` (1–3 символа), `slot_duration_min`, `slot_capacity`, `active` |
| Окна | `GET/POST /admin/windows`, `GET/PUT/DELETE /admin/windows/{id}`, `PUT /admin/windows/{id}/services` | `name`, `service_ids`; для `…/services` — `{"service_ids": [...]}` |
| Сотрудники | `GET/POST /admin/employees`, `GET/PUT/DELETE /admin/employees/{id}` | `full_name`, `active` |

Ответы: услуга `{id, code, name, number_prefix, slot_duration_min, slot_capacity, active}`; окно `{id, name, status, active, current_employee_id, service_ids}`;
сотрудник `{id, full_name, active}`. Ошибки показывай по `code`: `VALIDATION_ERROR` (занят код или префикс, неверные значения), `INVALID_STATE_TRANSITION`
(нельзя удалить: у услуги есть талоны в работе, окно открыто, сотрудник в открытом окне — покажи `detail`), `SERVICE_NOT_FOUND`, `WINDOW_NOT_FOUND`.
В форме окна набор услуг выбирай из активных услуг; создание услуги без слотов по умолчанию берёт значения из конфигурации отделения (поля можно не передавать).

### 7. Рекомендации по окнам
`GET /admin/recommendations` → `{generated_at, recommend_open_window, overdue_min, overdue_count, services: [{service_id, service_name, waiting, overdue, max_eta_overrun_sec, open_windows, reasons, suggested_windows: [{window_id, name}]}]}`.
`reasons`: `eta_overrun` («ожидание превышает дедлайн»), `overdue_count` («много просроченных»), `no_open_window` («нет открытого окна»). Покажи баннер, когда `recommend_open_window=true`:
по услугам, с причинами простым языком и подсказкой «Открыть можно: Окно 1, Окно 3» из `suggested_windows`. Кнопки «открыть окно» на `admin` нет — это делает оператор.

## Ещё недоступно на сервере

Дизайн-док описывает, но кода пока нет: отклонение «окно обслуживает медленнее нормы». Экран не рисуй, пока ручки нет.

## Результат

- `frontend/src/admin/` — приложение, `api.ts` — типы и клиент, страницы по разделам выше.
- Состояния загрузки, пустые данные, ошибки (по `code`), разрыв SSE.
- Ничего не хардкодь, кроме путей API. Никаких секретов в коде.
- Проверка вручную: запусти стенд (`docker compose up -d --build`), войди как `admin`, пройди разделы 2–7. Данные для аналитики и рекомендаций:
  `java tools/load-generator/LoadGenerator.java history --days 14 --out /tmp/h.sql` (см. `scripts/smoke/day5.sh`) и `… live --minutes 2`.
