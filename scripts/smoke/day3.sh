#!/usr/bin/env bash
# Смоук-проверка дня 3: NATS/outbox, окна и вызов, конкурентный вызов, закрытие окна, перенаправление,
# автоподключение отделений к центру, RPC через центр, недоступность отделения.
# Запуск из корня репо на all-in-one стенде:  docker compose up -d --build && ./scripts/smoke/day3.sh
# Нужны: curl, jq.
set -uo pipefail

NODE=${NODE:-http://localhost:8080}          # b01
NODE2=${NODE2:-http://localhost:8090}        # b02
CENTER=${CENTER:-http://localhost:8081}
HUB_MONITOR=${HUB_MONITOR:-http://localhost:8222}
W1=00000000-0000-4000-8000-000000000003      # Окно 1: посылки + отправка
W2=00000000-0000-4000-8000-000000000007      # Окно 2: посылки + финансы
W3=00000000-0000-4000-8000-000000000008      # Окно 3: отправка + финансы
S_PARCEL=00000000-0000-4000-8000-000000000001
S_SEND=00000000-0000-4000-8000-000000000004
S_FIN=00000000-0000-4000-8000-000000000005

command -v jq >/dev/null || { echo "Нужен jq"; exit 2; }
[ -f .env ] && { set -a; . ./.env; set +a; }
OP_PASS=${DEMO_OPERATOR_PASSWORD:-operator123}

PASS=0; FAIL=0
green() { printf '\033[32m%s\033[0m\n' "$*"; }
red()   { printf '\033[31m%s\033[0m\n' "$*"; }
ok()    { PASS=$((PASS+1)); green "  PASS  $1"; }
bad()   { FAIL=$((FAIL+1)); red   "  FAIL  $1"; [ -n "${2:-}" ] && echo "        $2"; }
req() { # req БАЗА МЕТОД ПУТЬ [curl-аргументы...] → $STATUS $BODY
  local base=$1 method=$2 path=$3; shift 3
  BODY=$(curl -s -X "$method" "$base$path" -w $'\n%{http_code}' "$@")
  STATUS=${BODY##*$'\n'}; BODY=${BODY%$'\n'*}
}
expect() {
  local name=$1 want=$2 cond=${3:-true}
  if [ "$STATUS" = "$want" ] && echo "$BODY" | jq -e "$cond" >/dev/null 2>&1; then ok "$name"
  else bad "$name" "ждали $want, пришло $STATUS: $(echo "$BODY" | head -c 300)"; fi
}
json() { echo "$BODY" | jq -r "$1"; }
J=(-H 'Content-Type: application/json')
RUN=$(date +%s)

login() { # login БАЗА ЛОГИН → токен
  curl -s -X POST "$1/api/v1/auth/login" "${J[@]}" -d "{\"login\":\"$2\",\"password\":\"$OP_PASS\"}" | jq -r .access_token
}
TOKEN1=$(login "$NODE" operator); TOKEN2=$(login "$NODE" operator2)
A1=(-H "Authorization: Bearer $TOKEN1"); A2=(-H "Authorization: Bearer $TOKEN2")
[ "$TOKEN1" != "null" ] && [ -n "$TOKEN1" ] && ok "логин operator" || bad "логин operator"
[ "$TOKEN2" != "null" ] && [ -n "$TOKEN2" ] && ok "логин operator2" || bad "логин operator2"

qr() { # qr БАЗА ОТДЕЛЕНИЕ УСЛУГА → талон в $BODY
  req "$1" POST /api/v1/public/qr-tickets "${J[@]}" -H "Idempotency-Key: qr-$RUN-$RANDOM$RANDOM" \
    -d "{\"qr_code\":\"demo:$2\",\"service_id\":\"$3\",\"device_id\":\"dev-$RUN-$RANDOM$RANDOM\"}"
}

echo "== 1. NATS и outbox"
req "$NODE" GET /actuator/health
expect "health: nats connected, BRANCH_EVT создан" 200 '.components.nats.details.connected == true and .components.nats.details.branch_evt_stream == true'
sleep 2
req "$NODE" GET /actuator/health
expect "outbox разгребается (pending == 0)" 200 '.components.nats.details.outbox_pending == 0'

echo "== 2. Окна и вызов"
req "$NODE" POST "/api/v1/windows/$W1/close" "${J[@]}" "${A1[@]}" -d '{"mode":"force"}'
req "$NODE" POST "/api/v1/windows/$W1/open" "${J[@]}" "${A1[@]}" -d "{\"service_ids\":[\"$S_PARCEL\",\"$S_SEND\"]}"
expect "открыть окно 1" 200 '.status == "open" and (.service_ids | length) == 2'
req "$NODE" POST "/api/v1/windows/$W2/close" "${J[@]}" "${A2[@]}" -d '{"mode":"force"}'
req "$NODE" POST "/api/v1/windows/$W2/open" "${J[@]}" "${A2[@]}" -d "{\"service_ids\":[\"$S_PARCEL\",\"$S_FIN\"]}"
expect "открыть окно 2" 200 '.status == "open"'
# опустошаем очередь окон, оставшуюся от прошлых прогонов
for w in $W1 $W2; do
  for _ in $(seq 1 50); do
    req "$NODE" POST "/api/v1/windows/$w/call-next" "${A1[@]}"
    [ "$STATUS" = 200 ] || break
    req "$NODE" POST "/api/v1/tickets/$(json .ticket_id)/complete" "${A1[@]}"
  done
done

qr "$NODE" b01 "$S_PARCEL"; T1=$(json .ticket_id); TOK1=$(json .token)
expect "QR-талон (посылки)" 201 '.status == "waiting" and .position == 0'
req "$NODE" POST "/api/v1/windows/$W1/call-next" "${A1[@]}"
expect "call-next: вызван наш талон" 200 ".ticket_id == \"$T1\" and .status == \"called\" and .window.name == \"Окно 1\""
req "$NODE" POST "/api/v1/windows/$W1/call-next" "${A1[@]}"
expect "повторный call-next → WINDOW_BUSY" 409 '.code == "WINDOW_BUSY"'
req "$NODE" GET "/api/v1/tickets/$T1/events" "${A1[@]}"
expect "журнал: ticket.called с причиной и кандидатами" 200 'map(select(.event_type=="ticket.called"))[0].data | (.reason != null and (.candidates|length) >= 1)'
req "$NODE" POST "/api/v1/tickets/$T1/complete" "${J[@]}" "${A1[@]}" -d '{"expected_version": 0}'
expect "complete со старой версией → STALE_STATE" 409 '.code == "STALE_STATE"'
req "$NODE" POST "/api/v1/tickets/$T1/start" "${A1[@]}"
expect "start → serving" 200 '.status == "serving"'
req "$NODE" POST "/api/v1/tickets/$T1/complete" "${A1[@]}"
expect "complete → done" 200 '.status == "done"'

echo "== 3. Конкурентный вызов из двух окон (проверка ТЗ №4)"
for i in $(seq 1 10); do qr "$NODE" b01 "$S_PARCEL"; done
TMP=$(mktemp -d)
for i in $(seq 1 10); do
  curl -s -X POST "$NODE/api/v1/windows/$W1/call-next" "${A1[@]}" > "$TMP/w1-$i" &
  curl -s -X POST "$NODE/api/v1/windows/$W2/call-next" "${A2[@]}" > "$TMP/w2-$i" &
  wait
  for w in w1 w2; do
    id=$(jq -r '.ticket_id // empty' "$TMP/$w-$i"); [ -n "$id" ] && echo "$id" >> "$TMP/called" && \
      curl -s -X POST "$NODE/api/v1/tickets/$id/complete" "${A1[@]}" >/dev/null
  done
done
TOTAL=$(wc -l < "$TMP/called" | tr -d ' '); UNIQUE=$(sort -u "$TMP/called" | wc -l | tr -d ' ')
[ "$TOTAL" = "$UNIQUE" ] && [ "$TOTAL" -ge 10 ] && ok "вызвано $TOTAL, уникальных $UNIQUE — двойных назначений нет" \
  || bad "двойное назначение?" "вызвано $TOTAL, уникальных $UNIQUE"
rm -rf "$TMP"

echo "== 4. Закрытие окна с активным клиентом (ТЗ №5)"
qr "$NODE" b01 "$S_PARCEL"; T2=$(json .ticket_id)
qr "$NODE" b01 "$S_PARCEL"; T3=$(json .ticket_id)
req "$NODE" POST "/api/v1/windows/$W1/call-next" "${A1[@]}"
expect "вызван первый из двух" 200 ".ticket_id == \"$T2\""
req "$NODE" POST "/api/v1/windows/$W1/close" "${J[@]}" "${A1[@]}" -d '{"mode":"graceful"}'
expect "graceful → closing" 200 '.status == "closing"'
req "$NODE" POST "/api/v1/windows/$W1/close" "${J[@]}" "${A1[@]}" -d '{"mode":"force"}'
expect "force → closed" 200 '.status == "closed"'
req "$NODE" GET "/api/v1/queue?service_id=$S_PARCEL&status=waiting" "${A1[@]}"
expect "талон вернулся в голову очереди (позиция 0, раньше T3)" 200 \
  "(map(select(.ticket_id==\"$T2\"))[0].position == 0) and (map(select(.ticket_id==\"$T3\"))[0].position == 1)"

echo "== 5. Перенаправление (ТЗ №6)"
req "$NODE" POST "/api/v1/windows/$W2/call-next" "${A2[@]}"
expect "окно 2 вызывает T2" 200 ".ticket_id == \"$T2\""
req "$NODE" POST "/api/v1/tickets/$T2/redirect" "${J[@]}" "${A2[@]}" -d "{\"service_id\":\"$S_FIN\",\"reason\":\"нужен перевод\"}"
expect "redirect на услугу → waiting, услуга сменилась" 200 ".status == \"waiting\" and .service.id == \"$S_FIN\""
req "$NODE" POST "/api/v1/windows/$W2/call-next" "${A2[@]}"
expect "окно 2 (финансы) вызывает перенаправленный T2 первым" 200 ".ticket_id == \"$T2\""
req "$NODE" POST "/api/v1/tickets/$T2/complete" "${A2[@]}"
req "$NODE" POST "/api/v1/windows/$W2/call-next" "${A2[@]}"
[ "$STATUS" = 200 ] && req "$NODE" POST "/api/v1/tickets/$(json .ticket_id)/complete" "${A2[@]}"

# окно 1 снова открыто — на нём завязан day1.sh (walk-in)
req "$NODE" POST "/api/v1/windows/$W1/open" "${J[@]}" "${A1[@]}" -d "{\"service_ids\":[\"$S_PARCEL\",\"$S_SEND\"]}"

echo "== 6. Центр: автоподключение отделений"
sleep 12   # heartbeat раз в 10 с
req "$CENTER" GET /api/v1/network/overview
expect "центр видит b01 и b02 online без ручной настройки" 200 \
  '([.branches[] | select(.online) | .id] | index("b01") != null and index("b02") != null)'
expect "события b01/b02 подключены к CENTER_EVT" 200 '[.branches[] | select(.id=="b01" or .id=="b02") | .events_attached] | all'
LEAFS=$(curl -s "$HUB_MONITOR/leafz" | jq '.leafs | length' 2>/dev/null || echo 0)
[ "${LEAFS:-0}" -ge 2 ] && ok "hub: $LEAFS leaf-подключения" || bad "leafz" "подключений: ${LEAFS:-0}"
JSZ=$(curl -s "$HUB_MONITOR/jsz?streams=true" | jq '[.account_details[]?.stream_detail[]? | select(.name=="CENTER_EVT") | .state.messages] | first // 0')
[ "${JSZ:-0}" -gt 0 ] && ok "CENTER_EVT содержит $JSZ событий отделений" || bad "CENTER_EVT пуст"

echo "== 7. RPC через центр (запись из интернета)"
req "$CENTER" POST /api/v1/branches/b02/qr-tickets "${J[@]}" -H "Idempotency-Key: c-$RUN" \
  -d "{\"qr_code\":\"demo:b02\",\"service_id\":\"$S_SEND\",\"device_id\":\"web-$RUN\"}"
expect "QR-талон в b02 через центр" 201 '.branch_id == "b02" and .status == "waiting"'
CTOK=$(json .token)
req "$CENTER" GET "/api/v1/branches/b02/tickets/$CTOK"
expect "статус талона через центр" 200 '.status == "waiting"'
req "$CENTER" GET "/api/v1/branches/b02/tickets/00000000-0000-4000-8000-00000000dead"
expect "чужой токен через центр → 404 TICKET_NOT_FOUND (ошибка ноды транслирована)" 404 '.code == "TICKET_NOT_FOUND"'
req "$CENTER" GET "/api/v1/branches/b99/tickets/$CTOK"
expect "несуществующее отделение → 503 BRANCH_UNAVAILABLE" 503 '.code == "BRANCH_UNAVAILABLE"'
SSE=$(curl -s -N --max-time 3 "$CENTER/api/v1/branches/b02/tickets/$CTOK/stream" | head -c 2000)
echo "$SSE" | grep -q "event:snapshot" && ok "SSE центра: первый кадр snapshot" || bad "SSE центра" "$(echo "$SSE" | head -c 200)"
SSE=$(curl -s -N --max-time 3 "$NODE/api/v1/public/tickets/$TOK1/stream" | head -c 2000)
echo "$SSE" | grep -q "event:snapshot" && ok "SSE ноды: первый кадр snapshot" || bad "SSE ноды" "$(echo "$SSE" | head -c 200)"

echo; echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" = 0 ]
