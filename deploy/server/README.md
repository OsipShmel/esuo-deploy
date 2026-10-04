# ЕСУО на сервере

Один хост, пять групп контейнеров (отдельные compose-проекты): центр, отделение b01, отделение b02, рой, пульт
сценариев. Мониторинг живёт со своими сущностями: Loki, Grafana, Prometheus и экспортёры — в группе центра, сборщик
логов отделения — в группе отделения. Отделения с ноутбуков подключаются к хабу центра через интернет.

Группы собираются из тех же описаний, что и стенд «всё в одном» (`deploy/center`, `deploy/branch`, `deploy/swarm`);
файлы этой папки добавляют только серверное: имена контейнеров как у стенда, общую сеть `esuo-wan`, «кабели» связи
отделений, пульт сценариев. Поэтому `scripts/demo` и `scripts/chaos` работают на сервере теми же командами, что на
ноутбуке.

| Группа | Проект | Файлы | Что внутри |
|---|---|---|---|
| Центр | `esuo-center` | `deploy/center/docker-compose.yml` + `center.yml` | база, хаб NATS, центр, симуляторы МП и уведомлений; Loki, Grafana, Prometheus, экспортёры, сборщик логов центра |
| Отделение b01 | `esuo-branch-b01` | `deploy/branch/docker-compose.yml` + `branch.yml` + `b01.env` | база, leaf, узел, сборщик логов, кабель связи с хабом |
| Отделение b02 | `esuo-branch-b02` | то же + `b02.env` | то же |
| Рой | `esuo-swarm` | `deploy/swarm/docker-compose.yml` + `swarm.yml` | четыре региональных leaf; `swarm-sim` — по команде |
| Пульт сценариев | `esuo-demo` | `demo.yml` | `demo-runner`: вкладка «Сценарии» на `/demo` отделения |

## 1. Внешний трафик: поддомены → порты сервера

Внешний прокси (TLS, сертификаты) — ваш; сервисы слушают порты хоста. Адреса берутся из `DOMAIN` в `.env`.

| Внешний адрес | Куда на сервере | Что там |
|---|---|---|
| `https://DOMAIN` | `:8081` (center-app) | клиент: выбор отделения, запись, статус талона; `/network` — обзор сети (вход оператора сети); API центра, `/swagger-ui.html` |
| `https://grafana.DOMAIN` | `:3000` (Grafana) | дашборды «ЕСУО · Центр», «Сеть», «Отделение», логи в Explore; вход `admin` / `GRAFANA_ADMIN_PASSWORD` |
| `https://prometheus.DOMAIN` | `:9090` (Prometheus) | метрики центра, хаба и базы центра; своего входа у Prometheus нет — закройте basic auth на прокси |
| `https://b01.DOMAIN` | `:8080` (узел b01) | клиент `/`, табло `/board`, оператор `/operator`, руководитель `/admin`, демо-пульт и сценарии `/demo` |
| `https://b02.DOMAIN` | `:8090` (узел b02) | то же для b02 |
| `DOMAIN:7422`, **TCP** | `:7422` (хаб NATS) | leaf-подключения отделений с ноутбуков; это не HTTP: пробросить TCP как есть, TLS не терминировать |

Что нужно от прокси:

- **SSE.** Табло, оператор, руководитель, статус талона и живой лог сценариев — Server-Sent Events (пути с `/stream`).
  Выключить буферизацию ответа (nginx: `proxy_buffering off`) и поднять таймаут чтения до часа
  (`proxy_read_timeout 3600s`); `Host` и `X-Forwarded-Proto` передавать.
- **Без префиксов путей.** Каждый поддомен проксируется в корень своего порта: экраны и API живут от `/`.
- **Порты 8081, 8080, 8090, 3000, 9090** не должны быть видны из интернета мимо прокси. Прокси на этом же хосте —
  оставьте `*_BIND=127.0.0.1` (как в `env.example`); на другой машине — `0.0.0.0` и фаервол «только с прокси».
- Остальные порты групп (базы, NATS-клиент, Loki, симуляторы, порт управления центра 9081) слушают только `127.0.0.1`
  — ими пользуются скрипты на самом сервере.

## 2. Ресурсы [Расчёт]

| Что | Память |
|---|---|
| Центр (`CENTER_HEAP=4g`) | ~5 ГБ |
| PostgreSQL центра (`CENTER_DB_SHARED_BUFFERS=2GB`) | ~2,5 ГБ |
| Хаб NATS | до 2 ГБ (лимит контейнера) |
| Мониторинг центра | до 2,3 ГБ (Loki и Grafana по 512 МБ, Prometheus 1 ГБ, экспортёры, сборщик) |
| Отделения b01, b02 (`NODE_HEAP=1g`) | ~1,5 ГБ каждое |
| Региональные leaf | до 1 ГБ каждый |
| Рой (`SWARM_HEAP`) | 2 ГБ на 40 000 отделений, 4 ГБ на 250 000 |

Без роя — ~14 ГБ; с роем на 250 000 — ~24 ГБ. Рекомендуем 32 ГБ ОЗУ и 8+ vCPU для роя на 250 000, 16 ГБ — для стенда
и роя до 40 000. Диск: история метрик до `METRICS_MAX_SIZE`, Prometheus до 10 ГБ, логи — 7 дней.

## 3. Первый запуск

Нужны Docker с Compose v2.24+, `git`; для скриптов на сервере — `bash`, `curl`, `jq`, `python3`.

```bash
git clone <репозиторий> esuo && cd esuo
cp deploy/server/env.example .env                    # DOMAIN, пароли, CENTER_HEAP=4g
cp deploy/server/b01.env.example deploy/server/b01.env
cp deploy/server/b02.env.example deploy/server/b02.env
docker network create esuo-wan                       # общая сеть групп — один раз на сервер
```

Все команды — из корня репозитория. Порядок: центр → отделения → пульт → рой.

```bash
# центр с мониторингом
docker compose -f deploy/center/docker-compose.yml -f deploy/server/center.yml --env-file .env \
  --profile monitoring up -d --build

# отделения (каждое — своя группа)
docker compose -f deploy/branch/docker-compose.yml -f deploy/server/branch.yml \
  --env-file .env --env-file deploy/server/b01.env --profile monitoring up -d --build
docker compose -f deploy/branch/docker-compose.yml -f deploy/server/branch.yml \
  --env-file .env --env-file deploy/server/b02.env --profile monitoring up -d --build

# пульт сценариев
docker compose -f deploy/server/demo.yml --env-file .env up -d --build

# рой: региональные leaf (сам рой запускается по команде, раздел 6)
docker compose -f deploy/swarm/docker-compose.yml -f deploy/server/swarm.yml --env-file .env up -d

./scripts/demo/run.sh doctor                         # всё зелёное — стенд готов
```

Короче — алиасами (например, в `~/.bashrc` на сервере):

```bash
alias esuo-center='docker compose -f deploy/center/docker-compose.yml -f deploy/server/center.yml --env-file .env --profile monitoring'
alias esuo-b01='docker compose -f deploy/branch/docker-compose.yml -f deploy/server/branch.yml --env-file .env --env-file deploy/server/b01.env --profile monitoring'
alias esuo-b02='docker compose -f deploy/branch/docker-compose.yml -f deploy/server/branch.yml --env-file .env --env-file deploy/server/b02.env --profile monitoring'
alias esuo-demo='docker compose -f deploy/server/demo.yml --env-file .env'
alias esuo-swarm='docker compose -f deploy/swarm/docker-compose.yml -f deploy/server/swarm.yml --env-file .env'
```

Дальше примеры — с ними: `esuo-center ps`, `esuo-b01 logs -f node-app`.

Корневой `docker-compose.yml` (стенд «всё в одном») на сервере не запускать: `docker compose up` без `-f` поднимет его
из того же `.env`, и имена контейнеров столкнутся.

## 4. Обновление

```bash
git pull
esuo-center up -d --build                            # пересобирает и пересоздаёт только изменившееся
esuo-b01 up -d --build
esuo-b02 up -d --build
esuo-demo up -d --build
esuo-swarm up -d
```

Одна служба: `esuo-center up -d --build center-app`, `esuo-b01 up -d --build node-app`. Перезапуск без пересборки —
`esuo-b01 restart node-app`. Данные лежат в томах групп и переживают пересоздание контейнеров; `down -v` их удаляет.

Пароль базы запоминается при первом запуске группы: смена `DB_PASSWORD` в `.env` при старом томе не даёт центру и
узлам войти в базу.

## 5. Тестовые скрипты

**На сервере, из корня репозитория — те же команды, что на ноутбуке.** Имена контейнеров и локальные порты групп
совпадают со стендом «всё в одном», пароли скрипты берут из того же `.env`:

```bash
./scripts/demo/run.sh doctor
./scripts/demo/run.sh list                           # все сценарии
./scripts/demo/run.sh link-cut --fast                # любой сценарий; --fast — без пауз показа
./scripts/demo/run.sh pension-ab
./scripts/demo/run.sh repeat normal-day 3
./scripts/chaos/cut-branch-link.sh b01               # обрыв связи b01 ↔ центр; restore — вернуть, status — состояние
./scripts/chaos/cpu-starve.sh b01 60                 # и остальные scripts/chaos/*.sh — как на стенде
```

**Из браузера:** `https://b01.DOMAIN/demo` → вход руководителя → вкладка «Сценарии»: те же сценарии кнопкой, живой лог,
остановка с возвратом стенда в порядок.

**С ноутбука** сценарии против сервера не запускаются: им нужен Docker сервера (обрыв связи, заливка данных, базы
отделений). Запускайте по `ssh` на сервере или с вкладки «Сценарии».

Не гоняйте сценарии подряд одной командой `run.sh all` на живом показе: запись, оставленная одним сценарием, может по
демо-часам встать в очередь следующего и сбить его проверку (`docs/demo/results.md`, раздел 8).

## 6. Рой

```bash
esuo-swarm up -d --force-recreate nats-region-1 nats-region-2 nats-region-3 nats-region-4   # перед замером: обнулить регионы
esuo-swarm --profile run run --rm swarm-sim --branches 40000 --duration 10m --date 2026-10-05 --start 12:00
```

Отчёт роя — `docs/demo/load/swarm-report-*.json`; потери — `events_acked_total` из отчёта против
`docker exec esuo-postgres-center psql -U esuo -d esuo_center -c "select count(*) from events where branch_id like 's%'"`.
В воскресенье нужен `--date` с будним днём: в модели потока воскресенье — выходной. На 250 000 отделений —
`SWARM_HEAP=4g` в `.env`. Отделения роя видны на `https://DOMAIN/network`, нагрузка — на дашборде «ЕСУО · Центр».

## 7. Отделение с ноутбука

Ноутбуку нужен только исходящий TCP до `DOMAIN:7422`. Номер отделения — свой у каждого ноутбука, не `b01`/`b02`.

```bash
cp deploy/branch/.env.example deploy/branch/b03.env
#   BRANCH_ID=b03
#   CENTER_HOST=<DOMAIN>, CENTER_LEAF_PORT=7422
#   LEAF_PASSWORD=<LEAF_PASSWORD из .env сервера>
#   DB_PASSWORD, JWT_SECRET — любые свои
#   DEMO_MODE=true, NODE_PORT=8082
#   QR_PUBLIC_BASE_URL=https://<DOMAIN>
docker compose -f deploy/branch/docker-compose.yml --env-file deploy/branch/b03.env --profile monitoring up -d --build
```

Экраны отделения — на самом ноутбуке: `http://localhost:8082` (`/board`, `/operator`, `/admin`, `/demo`). Через
полминуты отделение появляется на `https://DOMAIN/network`, его важные логи — в Grafana сервера, события и метрики —
в центре. Обрыв связи ноутбука с интернетом — тот же сценарий, что `link-cut`: зал работает, после возвращения сети
всё доезжает.

## 8. Логи и состояние

```bash
esuo-center ps                                       # состояние группы
esuo-center logs -f center-app                       # логи центра; esuo-b01 logs -f node-app — узла
curl -s localhost:8222/leafz | jq '[.leafs[].name]'  # кто подключён к хабу: отделения и регионы
```

Здоровье ПК отделений, логи и снимки диагностики — в карточке отделения на `/network`; графики — в Grafana.
