#!/usr/bin/env bash
# Смоук-проверка чекпоинта дня 1 для node-app.
# Запуск из корня репо:  ./scripts/smoke/day1.sh            — основной прогон
#                         ./scripts/smoke/day1.sh restart    — после перезапуска node-app
# Нужны: curl, jq (mac: brew install jq), запущенные postgres + NATS (docker compose) и node-app.
set -uo pipefail

NODE=${NODE:-http://localhost:8080}
HUB_MONITOR=${HUB_MONITOR:-http://localhost:8222}
WINDOW_ID=00000000-0000-4000-8000-000000000003   # демо-окно из DatabaseBootstrap
STATE_FILE=.smoke-day1-token

command -v jq >/dev/null || { echo "Нужен jq: brew install jq"; exit 2; }
[ -f .env ] && { set -a; . ./.env; set +a; }

PASS=0; FAIL=0
green() { printf '\033[32m%s\033[0m\n' "$*"; }
red()   { printf '\033[31m%s\033[0m\n' "$*"; }
ok()    { PASS=$((PASS+1)); green "  PASS  $1"; }
bad()   { FAIL=$((FAIL+1)); red   "  FAIL  $1"; [ -n "${2:-}" ] && echo "        $2"; }

# req МЕТОД ПУТЬ [curl-аргументы...] → $STATUS и $BODY
req() {
  local method=$1 path=$2; shift 2
  BODY=$(curl -s -X "$method" "$NODE$path" -w $'\n%{http_code}' "$@")
  STATUS=${BODY##*$'\n'}; BODY=${BODY%$'\n'*}
}
expect() { # expect "название" ожидаемый_статус [jq-условие]
  local name=$1 want=$2 cond=${3:-true}
  if [ "$STATUS" = "$want" ] && echo "$BODY" | jq -e "$cond" >/dev/null 2>&1; then ok "$name"
  else bad "$name" "ждали $want, пришло $STATUS: $(echo "$BODY" | head -c 300)"; fi
}
json() { echo "$BODY" | jq -r "$1"; }

if [ "${1:-}" = "restart" ]; then
  echo "== 15. Талон пережил перезапуск"
  [ -f "$STATE_FILE" ] || { red "Нет $STATE_FILE — сначала основной прогон"; exit 2; }
  req GET "/api/v1/public/tickets/$(cat "$STATE_FILE")"
  expect "талон на месте после рестарта" 200 '.status == "waiting"'
  echo; echo "PASS=$PASS FAIL=$FAIL"; [ "$FAIL" = 0 ]; exit
fi

RUN=$(date +%s)

echo "== 1–2. Приложение живо"
req GET /actuator/health
expect "health UP" 200 '.status == "UP"'

echo "== 3. Leaf подключён к hub"
LEAFS=$(curl -s "$HUB_MONITOR/leafz" | jq '.leafs | length' 2>/dev/null || echo 0)
[ "${LEAFS:-0}" -ge 1 ] && ok "leafz: подключено $LEAFS" || bad "leafz" "подключений: ${LEAFS:-0} (подняты ли nats-hub и nats-leaf?)"

echo "== 4. Отделение и услуга"
req GET /api/v1/public/branch
expect "GET /public/branch" 200 '.services | length > 0'
SERVICE_ID=$(json '(.services[] | select(.code=="parcel") | .id) // .services[0].id')

echo "== 5–8. QR-талон и идемпотентность"
QR_BODY=$(jq -nc --arg s "$SERVICE_ID" --arg d "dev-$RUN" '{qr_code:"demo:b01", service_id:$s, device_id:$d}')
H=(-H 'Content-Type: application/json')
req POST /api/v1/public/qr-tickets "${H[@]}" -H "Idempotency-Key: qr-$RUN" -d "$QR_BODY"
expect "5. выдача QR-талона" 201 '.status == "waiting" and .source == "qr"'
TICKET_ID=$(json .ticket_id); TOKEN=$(json .token); echo "$TOKEN" > "$STATE_FILE"
echo "        талон $(json .display_number), token сохранён в $STATE_FILE"

req POST /api/v1/public/qr-tickets "${H[@]}" -H "Idempotency-Key: qr-$RUN" -d "$QR_BODY"
expect "6. повтор с тем же ключом → тот же талон" 201 ".ticket_id == \"$TICKET_ID\""

OTHER=$(echo "$QR_BODY" | jq -c '.client_category = "pensioner"')
req POST /api/v1/public/qr-tickets "${H[@]}" -H "Idempotency-Key: qr-$RUN" -d "$OTHER"
expect "7. тот же ключ, другое тело → IDEMPOTENCY_CONFLICT" 422 '.code == "IDEMPOTENCY_CONFLICT"'

req POST /api/v1/public/qr-tickets "${H[@]}" -H "Idempotency-Key: qr2-$RUN" -d "$QR_BODY"
expect "8. второй талон на устройство → ACTIVE_TICKET_EXISTS" 409 '.code == "ACTIVE_TICKET_EXISTS"'

echo "== 9. Запись на слот и отмена"
TOMORROW=$(date -v+1d +%F 2>/dev/null || date -d tomorrow +%F)
req GET "/api/v1/public/slots?service_id=$SERVICE_ID&date=$TOMORROW"
expect "слоты на $TOMORROW" 200 'length > 0'
SLOT=$(json '.[-1].slot_start')   # последний слот дня — меньше шанс, что занят прошлыми прогонами
req POST /api/v1/public/appointments "${H[@]}" -H "Idempotency-Key: ap-$RUN" \
    -d "$(jq -nc --arg s "$SERVICE_ID" --arg t "$SLOT" '{service_id:$s, slot_start:$t}')"
expect "запись на $SLOT" 201 '.status == "booked"'
AP_TOKEN=$(json .token)
req POST "/api/v1/public/tickets/$AP_TOKEN/cancel"
expect "отмена записи" 200 '.status == "cancelled"'

echo "== 10–12. Безопасность"
WALKIN=$(jq -nc --arg s "$SERVICE_ID" '{service_id:$s}')
req POST "/api/v1/windows/$WINDOW_ID/walkin" "${H[@]}" -H "Idempotency-Key: wi0-$RUN" -d "$WALKIN"
expect "10. walk-in без токена → 401 problem+json" 401 '.code == "UNAUTHENTICATED" and .request_id != null'

req POST /api/v1/auth/login "${H[@]}" \
    -d "$(jq -nc --arg p "${DEMO_OPERATOR_PASSWORD:-}" '{login:"operator", password:$p}')"
expect "11a. логин operator" 200 '.access_token | length > 20'
JWT=$(json .access_token)

req POST "/api/v1/windows/$WINDOW_ID/walkin" "${H[@]}" -H "Authorization: Bearer $JWT" \
    -H "Idempotency-Key: wi-$RUN" -d "$WALKIN"
expect "11b. walk-in с токеном" 201 '.source == "walkin"'

req GET /api/v1/admin/analytics/summary -H "Authorization: Bearer $JWT"
expect "12. operator в админку → 403" 403 '.code == "FORBIDDEN"'

req POST /api/v1/auth/login "${H[@]}" -d '{"login":"operator","password":"definitely-wrong"}'
expect "    неверный пароль → 401" 401 '.code == "UNAUTHENTICATED"'

echo "== 13. Ошибки"
req POST /api/v1/public/qr-tickets "${H[@]}" -H "Idempotency-Key: bad-$RUN" -d '{broken'
expect "битый JSON → 400" 400 '.code == "VALIDATION_ERROR"'
req GET /api/v1/public/tickets/00000000-0000-4000-8000-00000000dead
expect "неизвестный токен → 404" 404 '.code == "TICKET_NOT_FOUND"'
req POST /api/v1/public/qr-tickets "${H[@]}" -d "$QR_BODY"
expect "без Idempotency-Key → 400" 400 '.code == "VALIDATION_ERROR"'

echo "== 14. События легли в outbox (outbox-воркер с дня 3 переводит их в sent)"
OUTBOX=$(docker compose exec -T postgres-b01 psql -U "${DB_USER:-esuo}" -d esuo -tAc \
  "SELECT count(*) FROM outbox WHERE subject LIKE 'branch.%.evt.ticket.%'" 2>/dev/null | tr -d '[:space:]')
[ "${OUTBOX:-0}" -gt 0 ] 2>/dev/null && ok "outbox: $OUTBOX событий" || bad "outbox" "получили: '${OUTBOX}' (запущен ли стенд через docker compose?)"

echo
echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" = 0 ] && green "Основной прогон зелёный. Перезапусти node-app и выполни: $0 restart" || red "Есть падения — смотри вывод выше и лог node-app по request_id"
[ "$FAIL" = 0 ]
