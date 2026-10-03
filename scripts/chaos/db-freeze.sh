#!/usr/bin/env bash
# Замёрзшая база отделения: сервер PostgreSQL не отвечает вообще (docker pause), соединения висят.
#   ./scripts/chaos/db-freeze.sh b01 [секунд=40]  — заморозить, смотреть центр, разморозить
#   ./scripts/chaos/db-freeze.sh b01 start        — заморозить, не ждать
#   ./scripts/chaos/db-freeze.sh b01 stop         — разморозить
# Ожидаемо: отделение на связи, в обзоре «данные очереди не обновляются …»; снимок диагностики приходит за ≤ 10 с —
# разделы базы с ошибкой, пул, потоки JVM и NATS на месте. После разморозки всё возвращается само.
set -uo pipefail
BRANCH=${1:?branch id, например b01}
. "$(dirname "$0")/lib-stress.sh"

freeze() { docker pause "$PG_CT" >/dev/null && echo "$PG_CT заморожена"; }
restore() {
  [ "$(docker inspect -f '{{.State.Paused}}' "$PG_CT" 2>/dev/null)" = true ] && docker unpause "$PG_CT" >/dev/null
  echo "$PG_CT разморожена"
}

case "${2:-run}" in
  start) freeze ;;
  stop)  restore ;;
  *)     secs=${2:-40}; [ "$secs" = run ] && secs=40
         trap restore EXIT INT TERM
         freeze
         watch_center "$secs" ;;
esac
