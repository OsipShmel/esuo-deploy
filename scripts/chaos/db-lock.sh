#!/usr/bin/env bash
# Зависшая блокировка в базе отделения: кто-то держит таблицу, запросы API встают в очередь и выбирают пул соединений.
#   ./scripts/chaos/db-lock.sh b01 [секунд=60] [таблица=tickets]  — заблокировать, нагрузить, смотреть центр, отпустить
#   ./scripts/chaos/db-lock.sh b01 start [таблица=tickets]        — заблокировать и нагрузить, не ждать
#   ./scripts/chaos/db-lock.sh b01 stop                           — снять нагрузку и блокировку
# Ожидаемо: отделение на связи, в обзоре «данные очереди не обновляются …» и «Узкое место в базе: запросы ждут
# соединения»; «Снять диагностику» показывает в db.blocking, кто кого держит. Блокирующая сессия — application_name
# esuo-chaos-lock, её видно в снимке и в pg_stat_activity.
set -uo pipefail
BRANCH=${1:?branch id, например b01}
. "$(dirname "$0")/lib-stress.sh"
APP=esuo-chaos-lock

lock() {
  local table=${1:-tickets}
  release_lock
  docker exec -e PGAPPNAME=$APP "$PG_CT" sh -c \
    "psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -q -c 'BEGIN; LOCK TABLE $table IN ACCESS EXCLUSIVE MODE; SELECT pg_sleep(3600);'" \
    >/dev/null 2>&1 &
  echo $! > "$STATE/lock.pid"
  sleep 1
  load_start 25
  echo "$PG_CT: таблица $table заблокирована (сессия $APP); API нагружено 25 клиентами"
}
release_lock() {
  docker exec "$PG_CT" sh -c "psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -Atq -c \
    \"SELECT count(pg_terminate_backend(pid)) FROM pg_stat_activity WHERE application_name = '$APP'\"" >/dev/null 2>&1
  [ -f "$STATE/lock.pid" ] && { kill "$(cat "$STATE/lock.pid")" 2>/dev/null; rm -f "$STATE/lock.pid"; }
  return 0
}
restore() {
  load_stop
  release_lock
  echo "$PG_CT: блокировка снята, нагрузка снята"
}

case "${2:-run}" in
  start) lock "${3:-tickets}" ;;
  stop)  restore ;;
  *)     secs=${2:-60}; [ "$secs" = run ] && secs=60
         trap restore EXIT INT TERM
         lock "${3:-tickets}"
         watch_center "$secs" ;;
esac
