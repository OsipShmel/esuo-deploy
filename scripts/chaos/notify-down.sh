#!/usr/bin/env bash
# Проверка ТЗ №8 на all-in-one стенде: недоступность уведомлений.
#   ./scripts/chaos/notify-down.sh          — полный сценарий (~5 мин): отказ → CB открыт, DLQ растёт → восстановление
#   ./scripts/chaos/notify-down.sh down|up  — только переключить notify-mock (error | ok)
# Ожидаемо: при отказе очередь работает, circuit breaker OPEN, dead_letters растёт; после восстановления новые
# уведомления доставляются, повтор из DLQ (POST /admin/dlq/{id}/retry) даёт delivered.
set -uo pipefail
cd "$(dirname "$0")/../.."
NODE=${NODE:-http://localhost:8080}
CENTER=${CENTER:-http://localhost:8081}
NOTIFY=${NOTIFY:-http://localhost:8095}
W1=00000000-0000-4000-8000-000000000003
S_PARCEL=00000000-0000-4000-8000-000000000001
command -v jq >/dev/null || { echo "Нужен jq"; exit 2; }
[ -f .env ] && { set -a; . ./.env; set +a; }

chaos() { curl -fsS -X POST "$NOTIFY/chaos" -H 'Content-Type: application/json' -d "{\"mode\":\"$1\"}" >/dev/null \
  && echo "notify-mock: режим $1" || { echo "notify-mock недоступен ($NOTIFY)"; exit 1; }; }
case "${1:-run}" in
  down) chaos error; exit 0 ;;
  up)   chaos ok; exit 0 ;;
  run)  ;;
  *)    echo "usage: $0 [run|down|up]"; exit 2 ;;
esac

PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); printf '\033[32m  PASS  %s\033[0m\n' "$1"; }
bad() { FAIL=$((FAIL+1)); printf '\033[31m  FAIL  %s\033[0m\n' "$1"; [ -n "${2:-}" ] && echo "        $2"; }
J=(-H 'Content-Type: application/json')
status() { curl -s "$CENTER/api/v1/admin/notifications/status"; }
poll() { # poll ОПИСАНИЕ СЕКУНД jq-УСЛОВИЕ-ПО-status
  local i; for i in $(seq 1 "$2"); do status | jq -e "$3" >/dev/null 2>&1 && { ok "$1 (${i} с)"; return 0; }; sleep 1; done
  bad "$1" "за $2 с: $(status)"; return 1; }

RUN=$(date +%s)
OP=$(curl -s -X POST "$NODE/api/v1/auth/login" "${J[@]}" -d "{\"login\":\"operator\",\"password\":\"${DEMO_OPERATOR_PASSWORD:-operator123}\"}" | jq -r .access_token)
[ -n "$OP" ] && [ "$OP" != null ] || { bad "логин operator на $NODE"; exit 1; }
A=(-H "Authorization: Bearer $OP")
curl -s -o /dev/null -X POST "$NODE/api/v1/windows/$W1/open" "${A[@]}" "${J[@]}" -d "{\"service_ids\":[\"$S_PARCEL\"]}"
walkins() { local i; for i in $(seq 1 "$1"); do
  curl -s -o /dev/null -X POST "$NODE/api/v1/windows/$W1/walkin" "${A[@]}" "${J[@]}" \
    -H "Idempotency-Key: nd-$RUN-$2-$i" -d "{\"service_id\":\"$S_PARCEL\"}"; done; }
# «Очередь приближается» шлётся только клиентам в первых позициях (0..3). Если окно всю дорогу простаивает,
# каждая новая фаза walkins встаёт позади невызванных прошлых — новые талоны из фазы «up» перестают попадать
# в это окно и circuit breaker не набирает достаточно вызовов, чтобы закрыться. Разбираем очередь между фазами.
drain_queue() { local i id; for i in $(seq 1 20); do
  id=$(curl -s -X POST "$NODE/api/v1/windows/$W1/call-next" "${A[@]}" | jq -r '.ticket_id // empty')
  [ -n "$id" ] || break
  curl -s -o /dev/null -X POST "$NODE/api/v1/tickets/$id/complete" "${A[@]}" "${J[@]}" -d '{}'; done; }
drain_queue                                     # чистое начало: убрать хвост прошлых прогонов
BEFORE=$(status); echo "до: $BEFORE"
SENT0=$(echo "$BEFORE" | jq '.counts.sent // 0'); DLQ0=$(echo "$BEFORE" | jq '.counts.dlq_open // 0')

echo "== 1. Отказ провайдера"
chaos error
walkins 4 down                                  # позиции 0..3 → «очередь приближается» (4.7)
CALLED=$(curl -s -X POST "$NODE/api/v1/windows/$W1/call-next" "${A[@]}" -w '%{http_code}' -o /tmp/nd-call.json)
[ "$CALLED" = 200 ] && ok "очередь работает: call-next → 200 ($(jq -r .display_number /tmp/nd-call.json))" \
  || bad "call-next при отказе уведомлений" "HTTP $CALLED: $(head -c 300 /tmp/nd-call.json)"
TID=$(jq -r .ticket_id /tmp/nd-call.json)
[ "$TID" != null ] && curl -s -o /dev/null -X POST "$NODE/api/v1/tickets/$TID/complete" "${A[@]}" "${J[@]}" -d '{}'
poll "circuit breaker OPEN" 120 '.circuit_breaker.state == "OPEN"'
poll "DLQ растёт (dlq_open > '"$DLQ0"')" 300 ".counts.dlq_open > $DLQ0"

echo "== 2. Восстановление"
chaos ok
drain_queue                                     # позиции 0..3 свободны — новые талоны сразу попадут в окно уведомлений
echo "  ждём перехода CB в HALF_OPEN (wait-in-open-state 30 с)"; sleep 31
walkins 8 up                                    # с запасом: часть может не попасть в позиции 0..3, если окно уже занято
poll "новые уведомления доставлены (sent > $SENT0)" 120 ".counts.sent > $SENT0"
poll "circuit breaker CLOSED" 150 '.circuit_breaker.state == "CLOSED"'

echo "== 3. Повтор из DLQ"
IDS=$(curl -s "$CENTER/api/v1/admin/dlq?kind=notification&status=open" | jq -r '.[].id')
N=0; D=0
for id in $IDS; do N=$((N+1))
  R=$(curl -s -X POST "$CENTER/api/v1/admin/dlq/$id/retry" | jq -r .result); [ "$R" = delivered ] && D=$((D+1)); done
[ "$N" -gt 0 ] && [ "$D" = "$N" ] && ok "повтор из DLQ: $D из $N delivered" || bad "повтор из DLQ" "delivered $D из $N"
echo "после: $(status)"
echo "notify-mock: $(curl -s "$NOTIFY/messages?limit=1" | jq -c '{received, accepted, unique}')"
echo "PASS=$PASS FAIL=$FAIL"; [ "$FAIL" = 0 ]
