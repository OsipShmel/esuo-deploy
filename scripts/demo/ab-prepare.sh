#!/usr/bin/env bash
# Подготовка стенда к демо A/B «без прогноза против прогноза» (load-planning.md 7.2, сценарий «пенсионные выплаты»).
#
#   b01 — «нулевой день»: истории нет, модель не опубликована → прогноз упирается в default-service-duration-sec (300 с)
#   b02 — «обученное отделение»: шесть недель истории (history.sql) и опубликованная секция model
#
# Что делает, по порядку:
#   1. центр: planning.json глобально; model — только на b02 (глобально и на b01 секция снимается)
#   2. ждёт, пока обе ноды применят конфигурацию (b02 видит модель, b01 — нет)
#   3. сброс обеих нод (POST /admin/demo/reset {scenario: pension}): талоны, окна, статистика, уровень нагрузки
#   4. seed.sql в обе ноды (окна 4–5 и сотрудники смены), history.sql только в b02
#
# Нужны: curl, jq, docker (psql внутри контейнеров БД), DEMO_MODE=true на нодах.
# Адреса: NODE, NODE2, CENTER; контейнеры БД: PG_B01, PG_B02 (по умолчанию all-in-one стенд).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
SC="$ROOT/infrastructure/demo/scenarios"
[ -f "$ROOT/.env" ] && { set -a; . "$ROOT/.env"; set +a; }

NODE=${NODE:-http://localhost:8080}
NODE2=${NODE2:-http://localhost:8090}
CENTER=${CENTER:-http://localhost:8081}
BRANCH=${BRANCH:-b01}
BRANCH2=${BRANCH2:-b02}
PG_B01=${PG_B01:-esuo-postgres-b01}
PG_B02=${PG_B02:-esuo-postgres-b02}
DB_USER=${DB_USER:-esuo}
ADMIN_PASS=${DEMO_ADMIN_PASSWORD:-admin123}
PROFILE=${PROFILE:-pension}

B=$'\033[1m'; G=$'\033[32m'; R=$'\033[31m'; D=$'\033[2m'; Z=$'\033[0m'
step() { printf '\n%s▸ %s%s\n' "$B" "$*" "$Z"; }
ok()   { printf '  %s✔ %s%s\n' "$G" "$*" "$Z"; }
die()  { printf '  %s✘ %s%s\n' "$R" "$*" "$Z"; exit 1; }
for c in curl jq docker; do command -v "$c" >/dev/null || die "нужен $c"; done

token() { curl -sf -X POST "$1/api/v1/auth/login" -H 'Content-Type: application/json' \
            -d "$(jq -nc --arg p "$ADMIN_PASS" '{login:"admin",password:$p}')" | jq -r '.access_token // empty' || true; }
put_center() { # put_center ПУТЬ ТЕЛО — публикация в центр, падает с телом ответа при ошибке
  local out code
  out=$(curl -s -X PUT "$CENTER$1" -H 'Content-Type: application/json' --data-binary "$2" -w $'\n%{http_code}')
  code=${out##*$'\n'}
  [ "${code:0:1}" = 2 ] || die "PUT $1 → HTTP $code: $(echo "${out%$'\n'*}" | head -c 300)"
}
psql_in() { docker exec -i "$1" psql -q -v ON_ERROR_STOP=1 -U "$DB_USER" -d esuo >/dev/null; }

# ---------------------------------------------------------------- --undo: вернуть стенд в обычный режим
# После pension-ab на обеих нодах висит ручной профиль «пенсионные выплаты», а демо-часы стоят на конце показа.
# Остальным сценариям это мешает (экраны нагрузки показывают чужой день), поэтому перед ними — --undo.
if [ "${1:-}" = "--undo" ]; then
  step "Возвращаю стенд в обычный режим"
  for n in "$NODE" "$NODE2"; do
    t=$(token "$n"); [ -n "$t" ] || die "не удалось войти руководителем на $n"
    curl -sf -X POST "$n/api/v1/admin/demo/reset" -H "Authorization: Bearer $t" -H 'Content-Type: application/json' -d '{}' >/dev/null \
      || die "сброс $n не прошёл (DEMO_MODE=true?)"
    curl -sf -X PUT "$n/api/v1/admin/load/profile" -H "Authorization: Bearer $t" -H 'Content-Type: application/json' \
      -d '{"profile": null}' >/dev/null || die "не удалось снять ручной профиль на $n"
  done
  ok "очереди пусты, окна закрыты, профиль дня — по календарю, демо-часы — реальное время"
  exit 0
fi

# ---------------------------------------------------------------- 0. стенд жив и в демо-режиме
step "Проверяю стенд"
for n in "$NODE" "$NODE2"; do
  curl -sf "$n/actuator/health" >/dev/null || die "нода $n не отвечает — docker compose up -d --build"
  [ "$(curl -sf "$n/api/v1/public/demo/clock" | jq -r .enabled)" = true ] \
    || die "на $n выключен DEMO_MODE: добавьте DEMO_MODE=true в .env и docker compose up -d node-b01 node-b02"
done
curl -sf "$CENTER/actuator/health" >/dev/null || die "центр $CENTER не отвечает"
for c in "$PG_B01" "$PG_B02"; do docker inspect "$c" >/dev/null 2>&1 || die "нет контейнера БД $c (задайте PG_B01/PG_B02)"; done
T1=$(token "$NODE"); T2=$(token "$NODE2")
[ -n "$T1" ] && [ -n "$T2" ] || die "не удалось войти руководителем (DEMO_ADMIN_PASSWORD в .env)"
ok "ноды $BRANCH и $BRANCH2, центр и БД на месте, демо-режим включён"

# ---------------------------------------------------------------- 1. конфигурация центра
step "Публикую конфигурацию: planning всем, model только $BRANCH2"
put_center /api/v1/config/global "$(jq -c '. + {model: null}' "$SC/planning.json")"
ok "planning.json → глобально (секция model глобально снята)"
# Не null, а явное выключение: пустую секцию нода читает как «ничего не менять» и оставила бы модель,
# если та когда-то уже доехала до b01 (BranchConfigService.applyCentral).
put_center "/api/v1/config/branches/$BRANCH" '{"model": {"enabled": false}}'
ok "$BRANCH: модель выключена — прогноз на константе 300 с"
put_center "/api/v1/config/branches/$BRANCH2" "$(cat "$SC/model.json")"
MODEL_VERSION=$(jq -r .model.version "$SC/model.json")
ok "$BRANCH2: модель $MODEL_VERSION"

step "Жду, пока ноды применят конфигурацию"
applied=0
for _ in $(seq 1 60); do
  v1=$(curl -sf "$NODE/api/v1/admin/model" -H "Authorization: Bearer $T1" | jq -r '.version // "none"' || echo "?")
  v2=$(curl -sf "$NODE2/api/v1/admin/model" -H "Authorization: Bearer $T2" | jq -r '.version // "none"' || echo "?")
  p1=$(curl -sf "$NODE/api/v1/admin/config" -H "Authorization: Bearer $T1" | jq -r '.planning.profiles | has("pension")' 2>/dev/null || echo false)
  p2=$(curl -sf "$NODE2/api/v1/admin/config" -H "Authorization: Bearer $T2" | jq -r '.planning.profiles | has("pension")' 2>/dev/null || echo false)
  if [ "$v1" = none ] && [ "$v2" = "$MODEL_VERSION" ] && [ "$p1" = true ] && [ "$p2" = true ]; then applied=1; break; fi
  sleep 1
done
[ "$applied" = 1 ] || die "конфигурация не доехала за 60 с: $BRANCH модель=$v1, $BRANCH2 модель=$v2 (ждали none и $MODEL_VERSION). Связь с центром? GET $CENTER/api/v1/network/overview"
ok "$BRANCH: модели нет; $BRANCH2: модель $MODEL_VERSION; профиль «${PROFILE}» есть у обеих"

# ---------------------------------------------------------------- 2. сброс
step "Сбрасываю ноды (талоны, окна, статистика, уровень нагрузки), профиль дня — $PROFILE"
for pair in "$NODE|$T1" "$NODE2|$T2"; do
  n=${pair%%|*}; t=${pair#*|}
  curl -sf -X POST "$n/api/v1/admin/demo/reset" -H "Authorization: Bearer $t" -H 'Content-Type: application/json' \
       -d "{\"scenario\":\"$PROFILE\"}" >/dev/null || die "сброс $n не прошёл"
done
ok "обе ноды чистые"

# ---------------------------------------------------------------- 3. справочники и история
step "Справочники смены (5 окон, 4 сотрудника) в обе ноды"
# seed.sql и history.sql выпущены генератором под b01; в b02 идут с заменой идентификатора отделения,
# иначе внешние ключи на branch(id) откатят весь файл.
sed "s/'b01'/'$BRANCH'/g" "$SC/seed.sql" | psql_in "$PG_B01"
sed "s/'b01'/'$BRANCH2'/g" "$SC/seed.sql" | psql_in "$PG_B02"
ok "seed.sql применён"

step "История обслуживаний только в $BRANCH2 (~4 тыс. талонов, 10–30 с)"
[ -f "$SC/history.sql" ] || die "нет $SC/history.sql — cd python-ai-service && uv run esuo-ml generate --end-day 2026-09-20"
sed "s/'b01'/'$BRANCH2'/g" "$SC/history.sql" | psql_in "$PG_B02"
h2=$(docker exec "$PG_B02" psql -tA -U "$DB_USER" -d esuo -c "SELECT count(*) FROM tickets WHERE status='done'")
h1=$(docker exec "$PG_B01" psql -tA -U "$DB_USER" -d esuo -c "SELECT count(*) FROM tickets")
ok "$BRANCH2: $h2 обслуженных талонов в истории; $BRANCH: $h1 талонов"

printf '\n%sСтенд готов.%s Дальше: %s./scripts/demo/run.sh pension-ab%s (скорость: --rate 60)\n' "$B$G" "$Z" "$B" "$Z"