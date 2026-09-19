#!/usr/bin/env bash
# Смоук-проверка дня 5: справочники руководителя (CRUD), рекомендации по окнам, переключение на heuristic,
# load-generator (история и живая нагрузка).
# Запуск из корня репо на all-in-one стенде:  docker compose up -d --build && ./scripts/smoke/day5.sh
# Нужны: curl, jq, docker, java (JDK 21+, для tools/load-generator).
set -uo pipefail

NODE=${NODE:-http://localhost:8080}
HUB_FROM_DOCKER=${HUB_FROM_DOCKER:-nats://host.docker.internal:4222}
PG_CONTAINER=${PG_CONTAINER:-esuo-postgres-b01}
PG_USER=${PG_USER:-esuo}; PG_DB=${PG_DB:-esuo}
BRANCH=${BRANCH:-b01}
HISTORY_SQL=${HISTORY_SQL:-/tmp/esuo-history.sql}
S_PARCEL=00000000-0000-4000-8000-000000000001

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
nats_pub() { docker run --rm natsio/nats-box nats --server "$HUB_FROM_DOCKER" pub "$1" -H "Nats-Msg-Id:$2" "$3" >/dev/null 2>&1; }
pg() { docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -tAc "$1" 2>/dev/null | tr -d '[:space:]'; }
wait_for() { local i; for i in $(seq 1 30); do eval "$2" >/dev/null 2>&1 && return 0; sleep 1; done; return 1; }

# Дожидаемся готовности узла (после docker compose up он стартует несколько секунд).
wait_for "узел готов" "[ \"\$(curl -s -o /dev/null -w '%{http_code}' $NODE/api/v1/public/branch)\" = 200 ]" || { bad "узел не поднялся"; exit 1; }

ADM=$(login admin "$ADMIN_PASS"); OP=$(login operator "$OP_PASS")
[ -n "$ADM" ] && [ "$ADM" != null ] && ok "логин admin" || { bad "логин admin"; exit 1; }
AA=(-H "Authorization: Bearer $ADM"); AO=(-H "Authorization: Bearer $OP")
RUN=$(date +%s)
PFX="S$((RUN % 100))"

echo "== Справочники: услуги"
req GET /api/v1/admin/services "${AA[@]}"
expect "список услуг: демо-услуги на месте" 200 'type=="array" and length>=3 and (.[0]|has("number_prefix"))'
req GET /api/v1/admin/services "${AO[@]}"
expect "оператору справочники закрыты → 403" 403 '.code=="FORBIDDEN"'
req POST /api/v1/admin/services "${AA[@]}" "${J[@]}" -d "{\"code\":\"smoke-$RUN\",\"name\":\"Смоук-услуга\",\"number_prefix\":\"$PFX\"}"
expect "создание услуги: 201, значения слота из конфигурации" 201 '.active==true and .slot_duration_min==15 and .slot_capacity==1'
SVC=$(echo "$BODY" | jq -r .id)
req POST /api/v1/admin/services "${AA[@]}" "${J[@]}" -d "{\"code\":\"smoke-$RUN\",\"name\":\"Дубль\",\"number_prefix\":\"Ъ\"}"
expect "повторный код → 400" 400 '.code=="VALIDATION_ERROR"'
req PUT "/api/v1/admin/services/$SVC" "${AA[@]}" "${J[@]}" -d '{"name":"Смоук-услуга 2","slot_capacity":3}'
expect "обновление услуги" 200 '.name=="Смоук-услуга 2" and .slot_capacity==3'

echo "== Справочники: окна и сотрудники"
req POST /api/v1/admin/windows "${AA[@]}" "${J[@]}" -d "{\"name\":\"Смоук-окно $RUN\",\"service_ids\":[\"$SVC\"]}"
expect "создание окна: closed, с услугой" 201 ".status==\"closed\" and .service_ids==[\"$SVC\"]"
WIN=$(echo "$BODY" | jq -r .id)
req PUT "/api/v1/admin/windows/$WIN/services" "${AA[@]}" "${J[@]}" -d "{\"service_ids\":[\"$SVC\",\"$S_PARCEL\"]}"
expect "перечень услуг окна заменён" 200 '.service_ids|length==2'
req PUT "/api/v1/admin/windows/$WIN/services" "${AA[@]}" "${J[@]}" -d '{"service_ids":["11111111-1111-4111-8111-111111111111"]}'
expect "несуществующая услуга → 404" 404 '.code=="SERVICE_NOT_FOUND"'
req POST /api/v1/admin/employees "${AA[@]}" "${J[@]}" -d "{\"full_name\":\"Смоук Сотрудник $RUN\"}"
expect "создание сотрудника" 201 '.active==true'
EMP=$(echo "$BODY" | jq -r .id)
req DELETE "/api/v1/admin/employees/$EMP" "${AA[@]}"
expect "мягкое удаление сотрудника" 200 '.active==false'
req GET /api/v1/admin/employees "${AA[@]}"
expect "удалённого сотрудника нет в списке" 200 "[.[]|select(.id==\"$EMP\")]|length==0"
req GET "/api/v1/admin/employees?include_inactive=true" "${AA[@]}"
expect "…но он есть с include_inactive" 200 "[.[]|select(.id==\"$EMP\")]|length==1"
req DELETE "/api/v1/admin/windows/$WIN" "${AA[@]}"
expect "мягкое удаление закрытого окна" 200 '.active==false'
req DELETE "/api/v1/admin/services/$SVC" "${AA[@]}"
expect "мягкое удаление услуги без талонов" 200 '.active==false'

echo "== Рекомендации по окнам"
req GET /api/v1/admin/recommendations "${AA[@]}"
expect "recommendations: структура ответа" 200 'has("recommend_open_window") and (.services|type=="array") and .overdue_min==10 and .overdue_count==5'

echo "== heuristic через конфигурацию центра"
docker info >/dev/null 2>&1 || { bad "docker недоступен — дальше нельзя опубликовать в hub"; echo "PASS=$PASS FAIL=$FAIL"; exit 1; }
cfg_field() { curl -s "$NODE/api/v1/admin/config" "${AA[@]}" | jq -r "$1"; }
nats_pub "center.config.branch.$BRANCH" "smoke5-h-$RUN" "{\"version\":$RUN,\"scope\":\"branch\",\"queue\":{\"scoring_strategy\":\"heuristic\"}}"
wait_for "heuristic" '[ "$(cfg_field .effective.scoring_strategy)" = heuristic ]' && ok "scoring_strategy = heuristic применён" || bad "heuristic не применился"

echo "== load-generator: история и живая нагрузка"
command -v java >/dev/null || { bad "java не найден — load-generator пропущен"; echo "PASS=$PASS FAIL=$FAIL"; exit 1; }
java tools/load-generator/LoadGenerator.java history --days 14 --per-day 40 --out "$HISTORY_SQL" >/dev/null 2>&1 && [ -s "$HISTORY_SQL" ] && ok "история сгенерирована ($HISTORY_SQL)" || bad "history: файл не создан"
docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -v ON_ERROR_STOP=1 < "$HISTORY_SQL" >/dev/null 2>&1 && ok "история загружена в БД" || bad "history: psql вернул ошибку" "проверьте демо-справочник (DEMO_DATA=true): docker exec -i $PG_CONTAINER psql ... < $HISTORY_SQL"
[ "$(pg "select count(*) from tickets where status='done' and issued_at < now() - interval '1 day'")" -gt 100 ] 2>/dev/null && ok "в БД >100 обслуженных талонов прошлых дней" || bad "мало исторических талонов"
[ "$(pg "select count(*) from handling_stats")" -gt 5 ] 2>/dev/null && ok "handling_stats пересобрана из истории" || bad "handling_stats пуста"
req GET "/api/v1/admin/analytics/summary?from=$(date -u -v-14d +%Y-%m-%d 2>/dev/null || date -u -d '14 days ago' +%Y-%m-%d)&to=$(date -u -v-1d +%Y-%m-%d 2>/dev/null || date -u -d yesterday +%Y-%m-%d)" "${AA[@]}"
expect "аналитика за прошлые дни: обслужено > 100, есть ожидание" 200 '.totals.served>100 and .totals.avg_wait_sec!=null and (.by_service|length)==3'
req GET "/api/v1/admin/analytics/windows?from=$(date -u -v-14d +%Y-%m-%d 2>/dev/null || date -u -d '14 days ago' +%Y-%m-%d)&to=$(date -u -v-1d +%Y-%m-%d 2>/dev/null || date -u -d yesterday +%Y-%m-%d)" "${AA[@]}"
expect "загрузка окон: открыто > 0, есть простой и обслуженные" 200 '[.[]|select(.open_sec>0 and .served>0)]|length>=2'

java tools/load-generator/LoadGenerator.java live --minutes 1 --speed 60 --arrivals-per-min 20 > /tmp/esuo-loadgen.log 2>&1 && ok "live-нагрузка отработала" || bad "live-нагрузка упала" "$(tail -5 /tmp/esuo-loadgen.log)"
grep -q 'обслужено [1-9]' /tmp/esuo-loadgen.log && ok "load-generator обслужил клиентов: $(grep 'Готово' /tmp/esuo-loadgen.log)" || bad "load-generator никого не обслужил"

echo "== вернуть uniform"
nats_pub "center.config.branch.$BRANCH" "smoke5-u-$RUN" "{\"version\":$((RUN+1)),\"scope\":\"branch\",\"queue\":{\"scoring_strategy\":\"uniform\"}}"
wait_for "uniform" '[ "$(cfg_field .effective.scoring_strategy)" = uniform ]' && ok "scoring_strategy возвращён в uniform" || bad "uniform не применился"

echo
echo "Итого: PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
