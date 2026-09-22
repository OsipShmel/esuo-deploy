#!/usr/bin/env bash
# Смоук-проверка дня 4: аналитика отделения, инциденты, конфигурация и записи МП через NATS (BRANCH_INBOX).
# Запуск из корня репо на all-in-one стенде:  docker compose up -d --build && ./scripts/smoke/day4.sh
# Нужны: curl, jq, docker (сообщения публикуются в hub контейнером natsio/nats-box).
set -uo pipefail

NODE=${NODE:-http://localhost:8080}                  # b01
HUB_FROM_DOCKER=${HUB_FROM_DOCKER:-nats://host.docker.internal:4222}   # hub, опубликованный на 127.0.0.1:4222
NODE_CONTAINER=${NODE_CONTAINER:-esuo-node-b01}
PG_CONTAINER=${PG_CONTAINER:-esuo-postgres-b01}
PG_USER=${PG_USER:-esuo}; PG_DB=${PG_DB:-esuo}
BRANCH=${BRANCH:-b01}
S_PARCEL=00000000-0000-4000-8000-000000000001
W1=00000000-0000-4000-8000-000000000003

command -v jq >/dev/null || { echo "Нужен jq"; exit 2; }
[ -f .env ] && { set -a; . ./.env; set +a; }
OP_PASS=${DEMO_OPERATOR_PASSWORD:-operator123}
ADMIN_PASS=${DEMO_ADMIN_PASSWORD:-admin123}

PASS=0; FAIL=0
green() { printf '\033[32m%s\033[0m\n' "$*"; }
red()   { printf '\033[31m%s\033[0m\n' "$*"; }
ok()    { PASS=$((PASS+1)); green "  PASS  $1"; }
bad()   { FAIL=$((FAIL+1)); red   "  FAIL  $1"; [ -n "${2:-}" ] && echo "        $2"; }
req() { local method=$1 path=$2; shift 2
  BODY=$(curl -s -X "$method" "$NODE$path" -w $'\n%{http_code}' "$@")
  STATUS=${BODY##*$'\n'}; BODY=${BODY%$'\n'*}; }
expect() { local name=$1 want=$2 cond=${3:-true}
  if [ "$STATUS" = "$want" ] && echo "$BODY" | jq -e "$cond" >/dev/null 2>&1; then ok "$name"
  else bad "$name" "ждали $want, пришло $STATUS: $(echo "$BODY" | head -c 400)"; fi; }
J=(-H 'Content-Type: application/json')
login() { curl -s -X POST "$NODE/api/v1/auth/login" "${J[@]}" -d "{\"login\":\"$1\",\"password\":\"$2\"}" | jq -r .access_token; }
nats_pub() { # nats_pub SUBJECT MSG_ID BODY — публикация в hub (JetStream-стрим центра сохранит её)
  docker run --rm natsio/nats-box nats --server "$HUB_FROM_DOCKER" pub "$1" -H "Nats-Msg-Id:$2" "$3" >/dev/null 2>&1; }
pg() { docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -tAc "$1" 2>/dev/null | tr -d '[:space:]'; }
wait_for() { # wait_for ОПИСАНИЕ КОМАНДА — до 30 с
  local i; for i in $(seq 1 30); do eval "$2" >/dev/null 2>&1 && return 0; sleep 1; done; return 1; }

OP=$(login operator "$OP_PASS"); ADM=$(login admin "$ADMIN_PASS")
[ -n "$OP" ] && [ "$OP" != null ] && ok "логин operator" || bad "логин operator"
[ -n "$ADM" ] && [ "$ADM" != null ] && ok "логин admin" || { bad "логин admin"; exit 1; }
AO=(-H "Authorization: Bearer $OP"); AA=(-H "Authorization: Bearer $ADM")
RUN=$(date +%s)

echo "== Аналитика (A1)"
req GET /api/v1/admin/analytics/summary "${AA[@]}"
expect "summary: 200 и поля totals/by_source/by_service" 200 '.totals.issued != null and (.by_source|type=="array") and (.by_service|type=="array")'
req GET "/api/v1/admin/analytics/summary?from=2026-01-01&to=2026-01-02" "${AA[@]}"
expect "summary за пустой период: нули" 200 '.totals.issued == 0 and .totals.avg_wait_sec == null'
req GET "/api/v1/admin/analytics/summary?from=yesterday" "${AA[@]}"
expect "summary: кривая дата → 400" 400 '.code == "VALIDATION_ERROR"'
req GET /api/v1/admin/analytics/windows "${AA[@]}"
expect "windows: массив со статистикой окон" 200 'type=="array" and length>0 and .[0].utilization != null'
req GET /api/v1/admin/analytics/deviations "${AA[@]}"
expect "deviations: counts по 7 типам" 200 '(.counts|keys|length)==7 and (.items|type=="array")'
req GET /api/v1/admin/analytics/unfinished "${AA[@]}"
expect "unfinished: массив" 200 'type=="array"'
req GET /api/v1/admin/analytics/summary "${AO[@]}"
expect "summary под оператором → 403" 403 '.code == "FORBIDDEN"'

echo "== Инциденты (A2)"
req POST /api/v1/incidents "${AO[@]}" "${J[@]}" -d "{\"kind\":\"technical\",\"description\":\"smoke $RUN: не печатает\",\"window_id\":\"$W1\"}"
expect "оператор создаёт инцидент" 201 '.status=="open"'
INC=$(echo "$BODY" | jq -r .id)
req GET /api/v1/admin/analytics/deviations "${AA[@]}"
expect "инцидент виден в отклонениях" 200 "[.items[]|select(.type==\"open_incident\" and .incident_id==\"$INC\")]|length==1"
req PATCH "/api/v1/admin/incidents/$INC" "${AA[@]}" "${J[@]}" -d '{"status":"resolved"}'
expect "руководитель закрывает инцидент" 200 '.status=="resolved"'
req PATCH "/api/v1/admin/incidents/$INC" "${AO[@]}" "${J[@]}" -d '{"status":"resolved"}'
expect "оператор не закрывает инцидент → 403" 403 '.code=="FORBIDDEN"'
req POST /api/v1/incidents "${AO[@]}" "${J[@]}" -d '{"kind":"weird","description":"x"}'
expect "неверный kind → 400" 400 '.code=="VALIDATION_ERROR"'

echo "== Конфигурация из центра (BRANCH_INBOX)"
docker info >/dev/null 2>&1 || { bad "docker недоступен — дальше нельзя опубликовать в hub"; echo "PASS=$PASS FAIL=$FAIL"; exit 1; }
VER=$RUN
CFG='{"version":'$VER',"scope":"branch","queue":{"slack_min":7},"bounds":{"slack_min":{"min":0,"max":15},"call_timeout_sec":{"min":30,"max":300}}}'
nats_pub "center.config.branch.$BRANCH" "smoke-cfg-$RUN" "$CFG"
cfg_version() { curl -s "$NODE/api/v1/admin/config" "${AA[@]}" | jq -r .central_version; }
if wait_for "конфигурация применена" '[ "$(cfg_version)" = "$VER" ]'; then ok "центральная версия $VER применена нодой"
else bad "версия не применилась за 30 с" "смотри: docker logs $NODE_CONTAINER | grep inbox"; fi
req GET /api/v1/admin/config "${AA[@]}"
expect "effective.slack_min = 7, границы на месте" 200 '.effective.slack_min==7 and .bounds.slack_min.max==15'
nats_pub "center.config.branch.$BRANCH" "smoke-cfg-$RUN" "$CFG"; sleep 2
req GET /api/v1/admin/config "${AA[@]}"
expect "повторная доставка не меняет версию" 200 ".central_version==$VER"

echo "== Локальные переопределения в границах"
req PUT /api/v1/admin/config "${AA[@]}" "${J[@]}" -d '{"slack_min":10}'
expect "slack_min=10 в границах → effective 10" 200 '.effective.slack_min==10 and .local_overrides.slack_min==10'
req PUT /api/v1/admin/config "${AA[@]}" "${J[@]}" -d '{"slack_min":99}'
expect "slack_min=99 вне границ → 422" 422 '.code=="CONFIG_OUT_OF_BOUNDS"'
req PUT /api/v1/admin/config "${AA[@]}" "${J[@]}" -d '{"max_recalls":9}'
expect "параметр без границ → 422" 422 '.code=="CONFIG_OUT_OF_BOUNDS"'
req PUT /api/v1/admin/config "${AA[@]}" "${J[@]}" -d '{}'
expect "пустой объект снимает переопределения" 200 '.effective.slack_min==7'

echo "== Запись из МП через MP_INBOX"
SLOT_DAY=$(date -u -v+1d +%Y-%m-%d 2>/dev/null || date -u -d tomorrow +%Y-%m-%d)
HOUR=$(printf '%02d' $((6 + RUN % 8)))                 # 06:00Z..13:00Z = 09:00..16:00 МСК, шаг слота 15 мин
MP_ID="mp-smoke-$RUN"
MP='{"mp_appointment_id":"'$MP_ID'","service_code":"parcel","slot_start":"'${SLOT_DAY}T${HOUR}':00:00Z","client_category":"default","client_ref":"smoke"}'
nats_pub "center.mp.inbox.branch.$BRANCH" "$MP_ID" "$MP"
if wait_for "талон МП создан" "[ \"\$(pg \"select status from tickets where mp_appointment_id='$MP_ID'\")\" = booked ]"; then ok "запись МП → талон booked"
else bad "талон МП не создан" "возможно слот занят прошлым прогоном (тогда в outbox будет mp.appointment.rejected): docker logs $NODE_CONTAINER | grep inbox"; fi
[ "$(pg "select count(*) from outbox where subject like '%mp.appointment.confirmed' and payload::text like '%$MP_ID%'")" = 1 ] \
  && ok "событие mp.appointment.confirmed в outbox" || bad "нет mp.appointment.confirmed"
nats_pub "center.mp.inbox.branch.$BRANCH" "$MP_ID" "$MP"; sleep 2
[ "$(pg "select count(*) from tickets where mp_appointment_id='$MP_ID'")" = 1 ] && ok "повторная доставка не дублирует талон" || bad "талон продублирован"
PAST='{"mp_appointment_id":"mp-old-'$RUN'","service_code":"parcel","slot_start":"2020-01-01T07:00:00Z","client_category":"default"}'
nats_pub "center.mp.inbox.branch.$BRANCH" "mp-old-$RUN" "$PAST"
REJ_SQL="select count(*) from outbox where subject like '%mp.appointment.rejected' and payload::text like '%mp-old-$RUN%' and payload::text like '%SLOT_EXPIRED%'"
wait_for "отказ" "[ \"\$(pg \"$REJ_SQL\")\" = 1 ]" \
  && ok "запись на прошедший слот → mp.appointment.rejected (SLOT_EXPIRED)" || bad "отказ SLOT_EXPIRED не записан"

echo
echo "Итого: PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
