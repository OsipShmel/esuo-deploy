#!/usr/bin/env bash
# Смоук центра (дизайн-док 4.6–4.8, 9.5): конфигурация отделения, пользователи, запись из МП с callback, уведомления.
# Запуск из корня репо на all-in-one стенде:  docker compose up -d --build && ./scripts/smoke/day4-center.sh
# Нужны: curl, jq. Отделение b01 должно быть на связи с центром.
set -uo pipefail
cd "$(dirname "$0")/../.."
CENTER=${CENTER:-http://localhost:8081}
NODE=${NODE:-http://localhost:8080}
MPSIM=${MPSIM:-http://localhost:8096}
NOTIFY=${NOTIFY:-http://localhost:8095}
BRANCH=${BRANCH:-b01}
S_PARCEL=00000000-0000-4000-8000-000000000001
command -v jq >/dev/null || { echo "Нужен jq"; exit 2; }
[ -f .env ] && { set -a; . ./.env; set +a; }

PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); printf '\033[32m  PASS  %s\033[0m\n' "$1"; }
bad() { FAIL=$((FAIL+1)); printf '\033[31m  FAIL  %s\033[0m\n' "$1"; [ -n "${2:-}" ] && echo "        $2"; }
req() { local method=$1 url=$2; shift 2
  BODY=$(curl -s -X "$method" "$url" -w $'\n%{http_code}' "$@"); STATUS=${BODY##*$'\n'}; BODY=${BODY%$'\n'*}; }
expect() { local name=$1 want=$2 cond=${3:-true}
  if [ "$STATUS" = "$want" ] && echo "$BODY" | jq -e "$cond" >/dev/null 2>&1; then ok "$name"
  else bad "$name" "ждали $want, пришло $STATUS: $(echo "$BODY" | head -c 400)"; fi; }
wait_until() { # wait_until ОПИСАНИЕ СЕКУНД КОМАНДА
  local i; for i in $(seq 1 "$2"); do eval "$3" >/dev/null 2>&1 && { ok "$1 (${i} с)"; return 0; }; sleep 1; done
  bad "$1" "не дождались за $2 с"; return 1; }
J=(-H 'Content-Type: application/json')
RUN=$(date +%s)

echo "== 0. Стенд"
req GET "$CENTER/api/v1/network/overview"
expect "центр видит $BRANCH online" 200 ".branches[] | select(.id==\"$BRANCH\") | .online"
req GET "$CENTER/v3/api-docs"
expect "OpenAPI центра, поля в snake_case" 200 '.openapi | startswith("3.1")'

echo "== 1. Конфигурация отделения (4.8)"
req PUT "$CENTER/api/v1/config/branches/$BRANCH" "${J[@]}" -d '{"queue":{"slack_min":7}}'
expect "PUT /config/branches/$BRANCH {slack_min: 7}" 200 '.version >= 100 and .published_to == ["'"$BRANCH"'"]'
VER=$(echo "$BODY" | jq -r .version)
wait_until "config.applied v$VER дошёл до центра, pending=false" 90 \
  "curl -s $CENTER/api/v1/config | jq -e '.branches[] | select(.branch_id==\"$BRANCH\") | .applied_version >= $VER and (.pending|not)'"
ADM=$(curl -s -X POST "$NODE/api/v1/auth/login" "${J[@]}" -d "{\"login\":\"admin\",\"password\":\"${DEMO_ADMIN_PASSWORD:-admin123}\"}" | jq -r .access_token)
req GET "$NODE/api/v1/admin/config" -H "Authorization: Bearer $ADM"
expect "нода: central_version=$VER, slack_min=7" 200 ".central_version == $VER and .effective.slack_min == 7"
req PUT "$CENTER/api/v1/config/branches/$BRANCH" "${J[@]}" -d '{"queue":{"no_such_key":1}}'
expect "неизвестный ключ очереди → 400" 400 '.code == "VALIDATION_ERROR" and (.errors|length) > 0'
req PUT "$CENTER/api/v1/config/global" "${J[@]}" -d '{"bounds":{"slack_min":{"min":10,"max":5}}}'
expect "границы min > max → 400" 400 '.code == "VALIDATION_ERROR"'
req PUT "$CENTER/api/v1/config/branches/zz99" "${J[@]}" -d '{"queue":{"slack_min":7}}'
expect "неизвестное отделение → 404" 404 '.code == "BRANCH_NOT_FOUND"'
req GET "$CENTER/api/v1/config/versions?branch=$BRANCH&limit=5"
expect "журнал версий без хешей паролей" 200 "length > 0 and (.[0].message.users // [] | all(has(\"password_hash\") | not))"

echo "== 2. Пользователь из центра входит на ноде (13.2)"
LOGIN="op$RUN"
req POST "$CENTER/api/v1/branches/$BRANCH/users" "${J[@]}" -d "{\"login\":\"$LOGIN\",\"password\":\"secret-$RUN\",\"role\":\"OPERATOR\",\"full_name\":\"Смоук $RUN\"}"
expect "POST /branches/$BRANCH/users → 201" 201 ".user.login == \"$LOGIN\" and .config_version >= 100"
req POST "$CENTER/api/v1/branches/$BRANCH/users" "${J[@]}" -d "{\"login\":\"$LOGIN\",\"password\":\"secret-$RUN\",\"role\":\"OPERATOR\",\"full_name\":\"x\"}"
expect "повтор login → 409" 409 '.code == "LOGIN_TAKEN"'
wait_until "вход $LOGIN на ноде" 90 \
  "curl -s -X POST $NODE/api/v1/auth/login -H 'Content-Type: application/json' -d '{\"login\":\"$LOGIN\",\"password\":\"secret-$RUN\"}' | jq -e '.access_token|length>20'"

echo "== 3. Запись из МП (4.6, проверка №1)"
TOMORROW=$(date -u -v+1d +%F 2>/dev/null || date -u -d tomorrow +%F)
req GET "$CENTER/api/v1/branches/$BRANCH/slots?service_id=$S_PARCEL&date=$TOMORROW"
SLOT=$(echo "$BODY" | jq -r '[.[] | select(.remaining_capacity > 0)] | .[length/2|floor].slot_start // empty')
[ -n "$SLOT" ] && ok "свободный слот $SLOT" || bad "свободный слот на $TOMORROW" "$STATUS $(echo "$BODY" | head -c 200)"
MPID="mp-$RUN"
req POST "$MPSIM/send" "${J[@]}" -d "{\"mp_appointment_id\":\"$MPID\",\"branch_id\":\"$BRANCH\",\"service_code\":\"parcel\",\"slot_start\":\"$SLOT\"}"
expect "вебхук принят → 202" 200 '.status == 202 and .response.status == "accepted"'
wait_until "callback confirmed со ссылкой на статус" 90 \
  "curl -s $MPSIM/callbacks/$MPID | jq -e '.body.result == \"confirmed\" and (.body.ticket.status_url | test(\"/b/$BRANCH/t/[0-9a-f-]{36}$\"))'"
URL=$(curl -s "$MPSIM/callbacks/$MPID" | jq -r .body.ticket.status_url); TOKEN=${URL##*/}
req GET "$CENTER/api/v1/branches/$BRANCH/tickets/$TOKEN"
expect "талон по ссылке: booked, $(curl -s "$MPSIM/callbacks/$MPID" | jq -r .body.ticket.display_number)" 200 '.status == "booked" and .source == "appointment"'
req POST "$MPSIM/send" "${J[@]}" -d "{\"mp_appointment_id\":\"$MPID\",\"branch_id\":\"$BRANCH\",\"service_code\":\"parcel\",\"slot_start\":\"$SLOT\"}"
expect "повтор с тем же телом → 202" 200 '.status == 202'
req POST "$MPSIM/send" "${J[@]}" -d "{\"mp_appointment_id\":\"$MPID\",\"branch_id\":\"$BRANCH\",\"service_code\":\"send\",\"slot_start\":\"$SLOT\"}"
expect "тот же id, другое тело → 422" 200 '.status == 422 and .response.code == "IDEMPOTENCY_CONFLICT"'
req POST "$MPSIM/send" "${J[@]}" -d "{\"branch_id\":\"$BRANCH\",\"service_code\":\"parcel\",\"in_minutes\":30,\"signature\":\"sha256=00\"}"
expect "неверная подпись → 401" 200 '.status == 401 and .response.code == "UNAUTHENTICATED"'
req POST "$MPSIM/send" "${J[@]}" -d "{\"branch_id\":\"$BRANCH\",\"service_code\":\"parcel\",\"in_minutes\":30,\"timestamp\":\"$((RUN-900))\"}"
expect "подпись старше 5 мин → 401" 200 '.status == 401'
req POST "$MPSIM/send" "${J[@]}" -d "{\"branch_id\":\"zz99\",\"service_code\":\"parcel\",\"in_minutes\":30}"
expect "неизвестное отделение → 404" 200 '.status == 404 and .response.code == "BRANCH_NOT_FOUND"'
PAST="mp-past-$RUN"
req POST "$MPSIM/send" "${J[@]}" -d "{\"mp_appointment_id\":\"$PAST\",\"branch_id\":\"$BRANCH\",\"service_code\":\"parcel\",\"in_minutes\":-120}"
expect "запись на прошедший слот принята центром → 202" 200 '.status == 202'
wait_until "callback rejected с причиной SLOT_EXPIRED" 90 \
  "curl -s $MPSIM/callbacks/$PAST | jq -e '.body.result == \"rejected\" and .body.reason == \"SLOT_EXPIRED\" and .body.ticket == null'"
req GET "$CENTER/api/v1/admin/mp/requests?limit=20"
expect "журнал МП: callback_sent у обеих" 200 "[.[] | select(.mp_appointment_id == \"$MPID\" or .mp_appointment_id == \"$PAST\") | .status] == [\"callback_sent\",\"callback_sent\"]"

echo "== 4. Уведомления (4.7) и обзор сети"
req GET "$CENTER/api/v1/admin/notifications/status"
expect "статус уведомлений" 200 '.circuit_breaker.state != null and .counts.sent != null and .counts.dlq_open != null'
req GET "$NOTIFY/health"
expect "notify-mock жив" 200 '.status == "UP"'
req GET "$CENTER/api/v1/admin/dlq?kind=notification"
expect "DLQ: список" 200 'type == "array"'
req GET "$CENTER/api/v1/network/overview"
expect "обзор сети: версии конфигурации" 200 ".branches[] | select(.id==\"$BRANCH\") | has(\"effective_config_version\") and has(\"config_published_version\") and (.anomalies | index(\"config_pending\") | not)"

echo "PASS=$PASS FAIL=$FAIL"; [ "$FAIL" = 0 ]
