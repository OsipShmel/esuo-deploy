# ЕСУО — электронная система управления очередью

Очередь для сети почтовых отделений. **Центр** и **отделения**: каждое отделение — автономная ячейка
(Spring Boot + PostgreSQL + NATS leaf) и работает без центра; события копятся и докачиваются при восстановлении
связи. Центр — публичный шлюз, обзор сети, конфигурация, уведомления, интеграция с мобильным приложением.

Отделение не только держит очередь, но и **планирует нагрузку**: модель длительности обслуживания
предсказывает, сколько работы принесёт каждый час, система ставит смену, заранее закрывает запись на часы,
которые окна не вытянут, и честно показывает клиенту ожидание.

### Для жюри: с чего начать

| | |
|---|---|
| 📄 [**Отчёт о проделанной работе**](docs/ESUO-report.md) | что сделано и чем отличается, цифры, покрытие десяти проверок ТЗ — 5 минут |
| ✅ [**Как проверить решение**](docs/demo/run-guide.md) | запуск и десять проверок ТЗ по шагам — 20 минут |
| 🏗 [**Технический дизайн-документ**](docs/ESUO-design-doc.md) | архитектура, алгоритм очереди, прогноз, отказы, 40 000 отделений, стек |
| 📊 [**Результаты проверок**](docs/demo/results.md) | 343 автотеста узла, 128 центра и 42 модели — без падений; 71 из 71 проверки демо-сценариев |
| 🗺 [**Навигатор по документации**](docs/README.md) | все материалы, видео, где какой код |

---

## Запуск за три команды

Нужны Docker, `curl`, `jq`, `python3` 3.9+.

```bash
cp .env.example .env                 # впишите секреты (см. ниже); DEMO_MODE=true уже стоит
docker compose up -d --build         # центр + отделения b01 и b02 + симуляторы МП и уведомлений
./scripts/demo/run.sh doctor         # проверка стенда: всё зелёное — можно работать
```

Первый старт нод — 30–60 секунд; если `doctor` красный сразу после `up`, подождите минуту и повторите.

### Переменные окружения (`.env`)

| Переменная | Что это | Как заполнить |
|---|---|---|
| `DB_PASSWORD` | пароль PostgreSQL центра и отделений | любой |
| `JWT_SECRET` | ключ подписи токенов | `openssl rand -base64 32` |
| `LEAF_PASSWORD` | пароль подключения отделений к NATS центра | любой |
| `MP_WEBHOOK_SECRET` | HMAC-подпись вебхуков мобильного приложения | `openssl rand -base64 32` |
| `DEMO_MODE` | демо-часы (ускорение времени) и сброс стенда | `true` для демо |
| `DEMO_DATA` | демо-справочники и пользователи | `true` |
| `DEMO_OPERATOR_PASSWORD`, `DEMO_ADMIN_PASSWORD` | пароли `operator` / `admin` на нодах | по умолчанию `operator123` / `admin123` |
| `CENTER_ADMIN_LOGIN`, `CENTER_ADMIN_PASSWORD` | оператор сети: вход в обзор сети и закрытый API центра | `network` / `network123` в `.env.example`; пусто — закрытый API недоступен никому |
| `QR_SECRET` | ключ подписи QR отделения: код на табло меняется каждые 2 мин, наклейка — долгоживущая | `openssl rand -base64 32`; пусто — демо-код `demo:<b>` (только при `DEMO_DATA=true`) |
| `QR_MODE` | куда ведёт QR на табло: `center` (интернет), `local` (Wi-Fi отделения), `auto` — центр, пока есть связь | по умолчанию `auto`; адрес центра — `CENTER_PUBLIC_URL` |

### Где что открыть

| Экран | Адрес |
|---|---|
| Обзор сети (центр) | http://localhost:8081/network — вход `network` / `CENTER_ADMIN_PASSWORD` |
| Клиент | http://localhost:8080/ (b01), http://localhost:8090/ (b02) |
| Табло | http://localhost:8080/board, http://localhost:8090/board |
| Оператор / руководитель | `/operator`, `/admin` на порту отделения |
| Демо-пульт | http://localhost:8080/demo |
| Swagger | http://localhost:8080/swagger-ui.html (нода), http://localhost:8081/swagger-ui.html (центр) |

---

## Мониторинг: логи, метрики и правила

```bash
docker compose --profile monitoring up -d --build   # + Loki, Grafana и сборщик логов на каждом отделении
./scripts/demo/run.sh monitoring                     # ≈4 мин: живая ошибка, обрыв связи, полные логи, метрики, снимок, перезапуск
```

**Логи.** Важные строки (WARN, ERROR, события жизненного цикла) отделение отправляет само и без потерь: при обрыве
связи они ждут на диске отделения и доезжают со своим временем. Полные логи любого уровня центр забирает только по
запросу — `POST /api/v1/branches/{b}/diagnostics/logs`, в ответе ссылка на Grafana.

**Метрики.** В каждом heartbeat (раз в 10 с) узел присылает блок `sys`: CPU, память, пул соединений и размер БД,
диск, p95 и доля 5xx своего API, RTT и переподключения связи с центром, число ошибок в логе. Центр пишет heartbeat
пачками раз в 5 с и хранит историю 48 ч — `GET /api/v1/branches/{b}/metrics?from&to`.

**Снимок диагностики.** `POST /api/v1/branches/{b}/diagnostics/snapshots` за секунды забирает с отделения потоки JVM
(заблокированные, deadlock'и), активные запросы и блокировки БД, пул соединений, outbox, состояние NATS, версии
конфигурации, диск и последние события жизненного цикла. Раздел, который не ответил за 8 с (например, при зависшей
базе), приходит с ошибкой, остальные — как есть. Снимки хранятся 30 дней: список —
`GET /api/v1/branches/{b}/diagnostics/snapshots`, целиком — `GET /api/v1/diagnostics/snapshots/{id}`.

**Правила.** `infrastructure/config/monitoring-rules.yml`: CPU, медленный API, очередь к БД, диск, нестабильная связь,
расхождение часов, перезапуск. Центр проверяет их раз в 15 с; сработавшие видны в `/network` → «Аномалии» с текстом
для человека. При `DEMO_MODE=true` длительности правил сокращаются в 10 раз.

**Хаос.** `./scripts/demo/run.sh health` — нехватка CPU, зависшая блокировка в базе, `kill -9` во время обрыва связи;
центр различает, что сломалось. Каждую поломку можно устроить и отдельно — скрипт сам вернёт всё назад и пока
показывает, что видит центр: `scripts/chaos/cpu-starve.sh b01`, `db-lock.sh b01`, `db-freeze.sh b01`, `node-kill.sh b01`,
`cut-branch-link.sh b01`. Если не отвечает только база отделения, в обзоре сети — «данные очереди не обновляются»,
а не «нет связи».

**В интерфейсе.** На `/network` у каждого отделения строка «Узел»: сработавшие правила, CPU, p95, диск и версия;
кнопки «Подробные логи (30 мин)» (появится ссылка на них в Grafana) и «Снять диагностику» (снимок скачивается
файлом); ссылка «Графики» открывает дашборд «ЕСУО · Отделение» на этом отделении.

**Grafana** — http://localhost:3000: дашборды «ЕСУО · Отделение», «ЕСУО · Сеть» и «ЕСУО · Центр», логи — Explore →
Loki. Метрики отделений Grafana читает ролью `grafana_ro` (только `branches`, `branch_metrics`, `branch_alerts`),
пароль — `GRAFANA_DB_PASSWORD`.

**Сам центр.** Prometheus (http://localhost:9090) собирает метрики центра, хаба NATS и базы центра; дашборд
«ЕСУО · Центр» — CPU, куча, GC, сброс heartbeat, проход правил, события, хаб, база и логи центра. На `/network` —
строка «Центр» с главным из этого. `POST /api/v1/center/diagnostics` (оператор сети) — снимок центра: какие потоки
едят CPU (включая GC и JIT), память, очереди, пул БД; с `?heap=true` — что лежит в куче.

Закрытый API центра — под входом оператора сети. Без профиля стенд прежний: метрики и правила работают в центре, узел
пишет файл логов, а всё отправленное в центр ждёт Loki в его стриме `CENTER_LOG` (до 7 дней).

## Главное демо: прогноз против его отсутствия

```bash
./scripts/demo/run.sh pension-ab              # ≈4 мин; --rate 120 — вдвое быстрее
```

Один и тот же пенсионный день — клиентов на 30 % больше, чем в профиле, — проигрывается на двух отделениях сразу:

| | **b01 — без прогноза** | **b02 — с прогнозом** |
|---|---|---|
| Что знает | ничего: 300 с на любое обслуживание | шесть недель истории и модель длительности |
| Смена по своему прогнозу | 4 → 3 → 2 окна, 12 окно-часов | 5 окон до полудня, 19 окно-часов |
| Запись из МП на 10:00 | принята в перегруженный час | `BOOKING_LIMITED` — запись закрыта заранее |
| Среднее / максимальное ожидание | 31,6 / 97,6 мин | **14,7 / 42,0 мин** |
| Очередь к 12:30 | 72 | **16** |

Скрипт сам готовит стенд, ведёт окна, пишет запись через мобильное приложение и в конце печатает таблицу сравнения.
Что показывать на экранах в какой момент — [`docs/demo/ab-cheatsheet.md`](docs/demo/ab-cheatsheet.md).

То же сравнение на синтетике без стенда ([`docs/demo/ml-report.md`](docs/demo/ml-report.md), пенсионный день):

| | Без прогноза | С прогнозом |
|---|---:|---:|
| Среднее ожидание клиента, мин | 94 | **4** |
| Ошибка обещанного времени вызова, мин | 57 | **9** |
| Просроченных талонов | 142 | **0** |

---

## Остальные демо и тесты

```bash
./scripts/demo/run.sh list                    # все сценарии: обычный день, три источника, МП, спорные ситуации,
                                              # аналитика, обрыв связи, отказ уведомлений, пенсионный день
./scripts/demo/run.sh repeat pension-ab 3     # три прогона подряд — одинаковый ход событий
./scripts/demo/run.sh normal                  # вернуть стенд в обычный режим после pension-ab

docker compose --profile demo up -d --build   # + пульт сценариев: вкладка «Сценарии» на /demo запускает их кнопкой
                                              # (сервис demo-runner получает сокет Docker — только на демо-стенде)

cd java-backend && ./mvnw -pl node-app -am test   # автотесты отделения (Testcontainers, нужен Docker)
python3 tools/demo-runner/test_runner.py           # проверки пульта сценариев (без стенда)
cd python-ai-service && uv run pytest              # модель длительности
```

Подробное руководство по записи демо — [`docs/demo/video-guide.md`](docs/demo/video-guide.md).

## Рой: центр под сотни тысяч отделений

```bash
docker compose --profile swarm up -d                 # четыре региональных leaf + мониторинг центра
cd java-backend && ./mvnw -pl swarm-sim -am package -DskipTests && cd ..
java -Xmx4g -jar java-backend/swarm-sim/target/swarm-sim-0.0.1-SNAPSHOT.jar --branches 250000 --duration 8m --start 12:00
```

В воскресенье добавьте `--date <будний день>` — в модели потока воскресенье выходной. Перед новым замером обнулите
регионы: `docker compose --profile swarm up -d --force-recreate nats-region-1 nats-region-2 nats-region-3 nats-region-4`.
Что держит центр на 250 000 отделений и как это проверено — [`docs/final_tmp/ESUO-scale.md`](docs/final_tmp/ESUO-scale.md),
параметры роя — [`java-backend/swarm-sim/README.md`](java-backend/swarm-sim/README.md). На сервере с роем:
`CENTER_HEAP=4g`, `CENTER_DB_SHARED_BUFFERS` — четверть памяти.

## Где результаты

| Файл | Что там |
|---|---|
| [`docs/demo/results.md`](docs/demo/results.md) | все итоги одной страницей: автотесты, десять проверок ТЗ, сценарии, модель, A/B |
| [`docs/demo/ml-report.md`](docs/demo/ml-report.md) | модель длительности в цифрах (`uv run esuo-ml report --end-day 2026-09-20`) |
| [`docs/demo/jury/ab-report.md`](docs/demo/jury/ab-report.md) | отчёт последнего `pension-ab`: итоги, смена по часам, ход дня |
| [`docs/demo/jury/runs.md`](docs/demo/jury/runs.md), [`repeatability.md`](docs/demo/jury/repeatability.md) | журнал всех прогонов демо и вердикты повторяемости; каждый прогон целиком — `runs/*.json` |
| [`docs/openapi/`](docs/openapi) | OpenAPI отделения и центра; Swagger UI — на каждом сервисе |

Новые прогоны дописываются в `docs/demo/jury` сами: каждый `run.sh` добавляет строку в `runs.md` и JSON прогона.

---

## Устройство

```
java-backend/
  shared-contracts/   контракты: ошибки, события, subjects NATS, RPC, DTO
  node-app/           отделение: очередь, API, SSE, outbox, планирование нагрузки, модель длительности
  center-app/         центр: шлюз HTTP→RPC, обзор сети, конфигурация, уведомления, МП, мониторинг отделений
  swarm-sim/          рой виртуальных отделений для замеров центра
frontend/             клиент, табло, оператор, руководитель, сеть, демо-пульт — одна сборка
python-ai-service/    обучение модели длительности, синтетика, трассы демо, отчёт
tools/                load-generator, notify-mock, mp-simulator
infrastructure/       конфиги NATS, значения очереди и планирования по умолчанию, данные демо
deploy/               compose по сущностям: center (с мониторингом центра), branch, swarm; корневой — из них
scripts/              demo/, smoke/, chaos/, openapi/
```

Стек: Java 25, Spring Boot 4, PostgreSQL 16, NATS 2.15 JetStream (leaf-домены), React 18 + TypeScript, Python 3.11.

---

## Две машины в локальной сети: центр и отделения отдельно

Отделение само подключается к центру (исходящий TCP на порт 7422), поэтому на центре ничего про отделения
настраивать не нужно: первый heartbeat регистрирует отделение. Пример: **машина A — центр** (`192.168.1.10`),
**машина B — отделения** (`192.168.1.20`). На обеих нужен Docker и клон репозитория.

**Машина A — центр** (+ симуляторы МП и уведомлений; `--profile monitoring` — с мониторингом центра):

```bash
cp deploy/center/.env.example deploy/center/.env
#   DB_PASSWORD, JWT_SECRET, LEAF_PASSWORD, MP_WEBHOOK_SECRET; LEAF_ADVERTISE=192.168.1.10:7422;
#   CENTER_PUBLIC_URL=http://192.168.1.10:8081 (имена переменных — те же, что в корневом .env)
docker compose -f deploy/center/docker-compose.yml --env-file deploy/center/.env up -d --build
```

**Машина B — отделение b01:**

```bash
cp deploy/branch/.env.example deploy/branch/b01.env
#   BRANCH_ID=b01, CENTER_HOST=192.168.1.10, LEAF_PASSWORD = LEAF_PASSWORD центра,
#   DB_PASSWORD и JWT_SECRET — любые, DEMO_MODE=true, NODE_PORT=8082,
#   QR_PUBLIC_BASE_URL=http://192.168.1.10:8081 (QR на табло ведёт на центр, пока есть связь)
docker compose -f deploy/branch/docker-compose.yml --env-file deploy/branch/b01.env up -d --build
```

Через полминуты b01 появится на `http://192.168.1.10:8081/network`; сама нода — `http://192.168.1.20:8082`
(табло `/board`, оператор `/operator`). Открыть на машинах порты: **A — 7422 и 8081, B — 8082**
(на маке: Системные настройки → Сеть → Брандмауэр, разрешить входящие для Docker).

**Второе отделение для `pension-ab`** — там же, на B, отдельным env-файлом с другими портами:

```bash
cp deploy/branch/b01.env deploy/branch/b02.env
#   BRANCH_ID=b02, NODE_PORT=8083, DB_PORT=5434, LEAF_CLIENT_PORT=4223, LEAF_MONITOR_PORT=8224
docker compose -f deploy/branch/docker-compose.yml --env-file deploy/branch/b02.env up -d --build
```

**Демо на двух машинах.** Подготовка A/B льёт данные в базы отделений, поэтому идёт на B; сам прогон — на A,
где живёт симулятор мобильного приложения:

```bash
# машина B: подготовить стенд
NODE=http://localhost:8082 NODE2=http://localhost:8083 CENTER=http://192.168.1.10:8081 \
PG_B01=esuo-branch-b01-postgres-1 PG_B02=esuo-branch-b02-postgres-1 ./scripts/demo/ab-prepare.sh

# машина A: прогон
AB_SKIP_PREPARE=1 NODE=http://192.168.1.20:8082 NODE2=http://192.168.1.20:8083 ./scripts/demo/run.sh pension-ab
```

**Рой на третьей машине** — регионы подключаются к хабу центра так же, исходящим TCP на 7422:

```bash
cp deploy/swarm/.env.example deploy/swarm/.env      # HUB_HOST=192.168.1.10, LEAF_PASSWORD центра
docker compose -f deploy/swarm/docker-compose.yml --env-file deploy/swarm/.env up -d
docker compose -f deploy/swarm/docker-compose.yml --env-file deploy/swarm/.env run --rm swarm-sim --branches 40000
```

Остальные сценарии запускаются так же — с адресами в `NODE`, `NODE2`, `CENTER`. Для обрыва связи на двух машинах
задайте `CUT_CMD` — команду, которая принимает `cut` или `restore` (например, `docker stop`/`start` leaf-контейнера
на B по ssh).
