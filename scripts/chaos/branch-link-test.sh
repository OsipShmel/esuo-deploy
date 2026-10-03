#!/usr/bin/env bash
# Сценарий «потеря связи отделения с центром» целиком, с проверками (all-in-one стенд).
#   ./scripts/chaos/branch-link-test.sh          (по умолчанию b01 на :8080)
# Что доказываем:
#   1) при разрыве центр видит отделение offline, а запросы клиентов из интернета в него — понятный 503;
#   2) отделение продолжает работать: выдаёт талоны и вызывает клиентов;
#   3) после восстановления связи всё, что произошло офлайн, само доезжает до центра.
set -uo pipefail
cd "$(dirname "$0")/../.."
BRANCH=${BRANCH:-b01}
NODE=${NODE:-http://localhost:8080}
CENTER=${CENTER:-http://localhost:8081}
LINK=./scripts/chaos/cut-branch-link.sh
W1=00000000-0000-4000-8000-000000000003
S_PARCEL=00000000-0000-4000-8000-000000000001
[ -f .env ] && { set -a; . ./.env; set +a; }
. ./scripts/center-auth.sh                     # обзор сети — закрытый API центра
command -v jq >/dev/null || { echo "Нужен jq"; exit 2; }

PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); printf '\033[32m  PASS  %s\033[0m\n' "$1"; }
bad() { FAIL=$((FAIL+1)); printf '\033[31m  FAIL  %s\033[0m\n' "$1"; [ -n "${2:-}" ] && echo "        $2"; }
step() { printf '\n\033[1m== %s\033[0m\n' "$1"; }
online() { center_card "$BRANCH" | jq -r '.online'; }
is_online()  { [ "$(online)" = true ]; }
is_offline() { [ "$(online)" = false ]; }
# событий отделения в БД центра (через ingest)
center_events() {
  docker compose exec -T postgres-center psql -U "${DB_USER:-esuo}" -d esuo_center -tAc \
    "SELECT count(*) FROM events WHERE branch_id='$BRANCH'" 2>/dev/null | tr -d '[:space:]'
}
wait_for() { # wait_for "описание" секунд команда... — ждёт, пока команда вернёт 0
  local what=$1 limit=$2; shift 2
  for ((i = 0; i < limit; i += 3)); do "$@" && { echo "        ($what: за ${i} с)"; return 0; }; sleep 3; done
  return 1
}

TOKEN=$(curl -s -X POST "$NODE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"login\":\"operator\",\"password\":\"${DEMO_OPERATOR_PASSWORD:-operator123}\"}" | jq -r .access_token)
AUTH=(-H "Authorization: Bearer $TOKEN")

step "0. Исходное состояние: связь есть"
$LINK "$BRANCH" restore >/dev/null
wait_for "online" 60 is_online && ok "центр видит $BRANCH online" || bad "$BRANCH не online до начала теста"
BEFORE=$(center_events); echo "        событий $BRANCH в БД центра: $BEFORE"

step "1. Разрыв связи"
$LINK "$BRANCH"
wait_for "offline" 60 is_offline && ok "центр пометил $BRANCH offline (нет heartbeat)" || bad "$BRANCH всё ещё online"
CODE=$(curl -s -o /tmp/link-503.json -w '%{http_code}' "$CENTER/api/v1/branches/$BRANCH/info")
[ "$CODE" = 503 ] && [ "$(jq -r .code /tmp/link-503.json)" = BRANCH_UNAVAILABLE ] \
  && ok "запрос клиента из интернета в $BRANCH → 503 BRANCH_UNAVAILABLE" || bad "ожидали 503" "получили $CODE"

step "2. Отделение работает автономно"
curl -s -X POST "$NODE/api/v1/windows/$W1/open" "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d "{\"service_ids\":[\"$S_PARCEL\"]}" >/dev/null
ISSUED=0
for i in 1 2 3; do
  C=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$NODE/api/v1/public/qr-tickets" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: offline-$(date +%s)-$i-$RANDOM" \
    -d "{\"qr_code\":\"demo:$BRANCH\",\"service_id\":\"$S_PARCEL\",\"device_id\":\"offline-$(date +%s)-$i-$RANDOM\"}")
  [ "$C" = 201 ] && ISSUED=$((ISSUED+1))
done
[ "$ISSUED" = 3 ] && ok "выдано 3 QR-талона без связи с центром" || bad "выдано $ISSUED из 3"
CALLED=0
for i in 1 2 3; do
  ID=$(curl -s -X POST "$NODE/api/v1/windows/$W1/call-next" "${AUTH[@]}" | jq -r '.ticket_id // empty')
  [ -n "$ID" ] && CALLED=$((CALLED+1)) && curl -s -X POST "$NODE/api/v1/tickets/$ID/complete" "${AUTH[@]}" >/dev/null
done
[ "$CALLED" -ge 1 ] && ok "вызвано и обслужено $CALLED клиентов без связи с центром" || bad "вызовы не прошли"
HEALTH=$(curl -s "$NODE/actuator/health" | jq -c '.components.nats.details | {connected, outbox_pending}')
echo "        нода: $HEALTH  (connected=true — это связь с СВОИМ leaf; события копятся в его BRANCH_EVT)"
$LINK "$BRANCH" status | sed 's/^/        /'
DURING=$(center_events)
[ "$DURING" = "$BEFORE" ] && ok "в центр за время разрыва ничего не пришло ($DURING)" || bad "центр что-то получил?" "$BEFORE → $DURING"

step "3. Восстановление связи и докачка"
$LINK "$BRANCH" restore
wait_for "online" 60 is_online && ok "$BRANCH снова online" || bad "$BRANCH не вернулся online"
EXPECTED_MIN=$((BEFORE + ISSUED))
events_caught_up() { [ "$(center_events)" -ge "$EXPECTED_MIN" ] 2>/dev/null; }
if wait_for "докачка" 120 events_caught_up; then
  AFTER=$(center_events); ok "офлайн-события доехали до центра сами: $BEFORE → $AFTER"
else
  bad "докачка не завершилась за 2 мин" "событий в центре: $(center_events), ждали ≥ $EXPECTED_MIN"
fi
$LINK "$BRANCH" status | sed 's/^/        /'

echo; echo "PASS=$PASS FAIL=$FAIL"
[ "$FAIL" = 0 ]