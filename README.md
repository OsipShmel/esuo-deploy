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
| 📊 [**Результаты проверок**](docs/demo/results.md) | 250 автотестов узла и 42 модели — без падений; 69 из 69 проверок демо-сценариев |
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
  center-app/         центр: шлюз HTTP→RPC, обзор сети, конфигурация, уведомления, МП
frontend/             клиент, табло, оператор, руководитель, сеть, демо-пульт — одна сборка
python-ai-service/    обучение модели длительности, синтетика, трассы демо, отчёт
tools/                load-generator, notify-mock, mp-simulator
infrastructure/       конфиги NATS, значения очереди и планирования по умолчанию, данные демо
deploy/               compose для центра и отделения на разных машинах
scripts/              demo/, smoke/, chaos/, openapi/
```

Стек: Java 25, Spring Boot 4, PostgreSQL 16, NATS 2.10 JetStream (leaf-домены), React 18 + TypeScript, Python 3.11.

---

## Две машины в локальной сети: центр и отделения отдельно

Отделение само подключается к центру (исходящий TCP на порт 7422), поэтому на центре ничего про отделения
настраивать не нужно: первый heartbeat регистрирует отделение. Пример: **машина A — центр** (`192.168.1.10`),
**машина B — отделения** (`192.168.1.20`). На обеих нужен Docker и клон репозитория.

**Машина A — центр** (+ симуляторы МП и уведомлений):

```bash
cp deploy/center/.env.example deploy/center/.env
#   пароли и секреты; LEAF_ADVERTISE=192.168.1.10:7422; CENTER_PUBLIC_URL=http://192.168.1.10:8081
docker compose -f deploy/center/docker-compose.yml --env-file deploy/center/.env up -d --build
```

**Машина B — отделение b01:**

```bash
cp deploy/branch/.env.example deploy/branch/b01.env
#   BRANCH_ID=b01, CENTER_HOST=192.168.1.10, LEAF_PASSWORD = LEAF_SHARED_PASSWORD центра,
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

Остальные сценарии запускаются так же — с адресами в `NODE`, `NODE2`, `CENTER`. Для обрыва связи на двух машинах
задайте `CUT_CMD` — команду, которая принимает `cut` или `restore` (например, `docker stop`/`start` leaf-контейнера
на B по ssh).
