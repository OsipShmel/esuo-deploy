#!/usr/bin/env bash
# Разрыв/восстановление связи отделения с центром в all-in-one стенде. Идемпотентно.
#   ./scripts/chaos/cut-branch-link.sh b01           — разорвать
#   ./scripts/chaos/cut-branch-link.sh b01 restore   — восстановить
#   ./scripts/chaos/cut-branch-link.sh b01 status    — связь, online в центре, события: отделение vs центр
# Механизм: leaf отделения видит центр только через TCP-прокси esuo-link-<b>; разрыв = его остановка.
# (docker network disconnect ненадёжен: на Docker Desktop уже открытое соединение идёт через другую сеть.)
# На стенде из нескольких ПК то же самое — выдернуть кабель / закрыть 7422 на центре.
set -euo pipefail
BRANCH=${1:?branch id, например b01}
ACTION=${2:-cut}
LEAF=esuo-nats-leaf-$BRANCH
LINK=esuo-link-$BRANCH      # TCP-прокси leaf → hub, «кабель» отделения
CENTER=${CENTER:-http://localhost:8081}
HUB_MONITOR=${HUB_MONITOR:-http://localhost:8222}

linked() { [ "$(docker inspect -f '{{.State.Running}}' "$LINK" 2>/dev/null)" = true ]; }

case "$ACTION" in
  cut)
    if linked; then docker stop -t 0 "$LINK" >/dev/null; echo "связь $BRANCH ↔ центр разорвана; очередь отделения работает автономно"
    else echo "связь $BRANCH ↔ центр уже разорвана"; fi ;;
  restore)
    if linked; then echo "связь $BRANCH ↔ центр уже есть"
    else docker start "$LINK" >/dev/null; echo "связь $BRANCH ↔ центр восстановлена (leaf переподключится за ~1 с, докачка событий — до ~1 мин)"; fi ;;
  status)
    ROOT=$(cd "$(dirname "$0")/../.." && pwd)
    [ -f "$ROOT/.env" ] && { set -a; . "$ROOT/.env"; set +a; }
    . "$ROOT/scripts/center-auth.sh"           # обзор сети — закрытый API центра
    linked && echo "кабель: $LINK работает" || echo "кабель: $LINK остановлен (связи с центром нет)"
    command -v jq >/dev/null || exit 0
    center_card "$BRANCH" | jq -r '"центр: online=\(.online), heartbeat \(.seconds_since_heartbeat) с назад"'
    local_n=$(docker exec "$LEAF" wget -qO- "http://localhost:8222/jsz?streams=true" 2>/dev/null \
      | jq '[.account_details[]?.stream_detail[]? | select(.name=="BRANCH_EVT") | .state.messages] | first // 0')
    center_n=$(curl -s "$HUB_MONITOR/jsz?streams=true&config=true" \
      | jq -r --arg api "\$JS.branch-$BRANCH.API" '[.account_details[]?.stream_detail[]? | select(.name=="CENTER_EVT")
            | .config.sources[]? | select(.external.api==$api)] | length')
    echo "события: в BRANCH_EVT отделения $local_n; source в CENTER_EVT подключён: $([ "$center_n" = 1 ] && echo да || echo нет)"
    curl -s "$HUB_MONITOR/jsz?streams=true" | jq -r --arg api "\$JS.branch-$BRANCH.API" \
      '.account_details[]?.stream_detail[]? | select(.name=="CENTER_EVT") | .sources[]? | select(.external.api==$api)
       | if .active < 0 then "докачка в центр: source ещё не активен (после рестарта/переподключения — до ~1 мин)"
         else "докачка в центр: lag=\(.lag) (0 = всё доехало), активность \(.active/1e9|floor) с назад" end' ;;
  *) echo "действие: cut | restore | status"; exit 2 ;;
esac