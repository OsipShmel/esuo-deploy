# Общее для стресс-скриптов отделения (cpu-starve, db-lock, db-freeze, node-kill): адреса, нагрузка, наблюдение.
# Подключается через `. scripts/chaos/lib-stress.sh`; ожидает $BRANCH. All-in-one стенд: b01 → :8080, b02 → :8090.
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
[ -f "$ROOT/.env" ] && { set -a; . "$ROOT/.env"; set +a; }
command -v jq >/dev/null || { echo "Нужен jq"; exit 2; }
case "$BRANCH" in
  b01) NODE=${NODE:-http://localhost:8080} ;;
  b02) NODE=${NODE:-http://localhost:8090} ;;
  *)   NODE=${NODE:?для $BRANCH задайте NODE=http://host:port} ;;
esac
NODE_CT=${NODE_CT:-esuo-node-$BRANCH}
PG_CT=${PG_CT:-esuo-postgres-$BRANCH}
STATE=${TMPDIR:-/tmp}/esuo-chaos-$BRANCH
mkdir -p "$STATE"

# load_start N — N параллельных клиентов дёргают API табло, пока не вызван load_stop
load_start() {
  local i
  load_stop
  for i in $(seq 1 "${1:-8}"); do
    ( while :; do curl -s -o /dev/null --max-time 30 "$NODE/api/v1/public/board"; done ) &
    echo $! >> "$STATE/load.pids"
  done
}
load_stop() {
  [ -f "$STATE/load.pids" ] || return 0
  # shellcheck disable=SC2046
  kill $(cat "$STATE/load.pids") 2>/dev/null
  rm -f "$STATE/load.pids"
}

# watch_center СЕКУНДЫ — раз в 10 с строка: что центр видит об отделении (обзор сети + последняя точка метрик)
watch_center() {
  local until=$(( $(date +%s) + ${1:-60} )) card point
  # shellcheck source=../center-auth.sh
  . "$ROOT/scripts/center-auth.sh" >/dev/null 2>&1 || { echo "нет входа в центр (CENTER_ADMIN_PASSWORD в .env)"; return 1; }
  while [ "$(date +%s)" -lt "$until" ]; do
    card=$(center_curl GET /api/v1/network/overview 2>/dev/null | jq -c --arg b "$BRANCH" '.branches[]? | select(.id==$b)')
    point=$(center_curl GET "/api/v1/branches/$BRANCH/metrics" 2>/dev/null | jq -c 'last // {}')
    printf '%s  %s | CPU %s%% · p95 %s мс · пул: занято %s, ждут %s | %s\n' "$(date +%H:%M:%S)" \
      "$(echo "$card" | jq -r 'if .online then "на связи" else "НЕТ СВЯЗИ" end')" \
      "$(echo "$point" | jq -r '(.sys.cpu_system // null) | if . == null then "?" else (. * 100 | round) end')" \
      "$(echo "$point" | jq -r '.sys.http_p95_ms // "?"')" \
      "$(echo "$point" | jq -r '.sys.db_pool_active // "?"')" \
      "$(echo "$point" | jq -r '.sys.db_pool_pending // "?"')" \
      "$(echo "$card" | jq -r '{offline: "нет связи", db_unavailable: "очередь не обновляется \(.db_unavailable_sec // 0) с",
        no_open_windows: "клиенты ждут, окна закрыты", overdue_tickets: "просроченные талоны", outbox_lag: "события задерживаются",
        overload: "перегрузка", config_pending: "конфигурация не применена", wait_grows_without_tickets: "ожидание растёт"} as $t
      | [(.alerts // [])[].text] + [.anomalies[]? | select(startswith("sys.") | not) | $t[.] // .]
      | if length == 0 then "аномалий нет" else join("; ") end')"
    sleep 10
  done
}

wait_node() {
  local i
  for i in $(seq 1 60); do [ "$(curl -s -o /dev/null -w '%{http_code}' "$NODE/actuator/health")" = 200 ] && return 0; sleep 2; done
  echo "узел $BRANCH не поднялся за 2 мин"; return 1
}
