#!/usr/bin/env bash
# Внезапная смерть приложения отделения: kill -9, без штатной остановки.
#   ./scripts/chaos/node-kill.sh b01 [секунд наблюдения=60]  — убить, поднять, смотреть центр
#   ./scripts/chaos/node-kill.sh b01 start                    — убить и не поднимать
#   ./scripts/chaos/node-kill.sh b01 stop                     — поднять и дождаться health
# Ожидаемо: очередь, окна и неотправленные события на месте (всё в PostgreSQL); в обзоре сети
# «Приложение отделения перезапускалось в …»; в Loki — app.started без app.stopping перед ним.
set -uo pipefail
BRANCH=${1:?branch id, например b01}
. "$(dirname "$0")/lib-stress.sh"

kill9() { docker kill -s KILL "$NODE_CT" >/dev/null && echo "$NODE_CT убит (kill -9)"; }
restore() {
  [ "$(docker inspect -f '{{.State.Running}}' "$NODE_CT" 2>/dev/null)" = true ] || docker start "$NODE_CT" >/dev/null
  wait_node && echo "$NODE_CT поднят"
}

case "${2:-run}" in
  start) kill9 ;;
  stop)  restore ;;
  *)     secs=${2:-60}; [ "$secs" = run ] && secs=60
         kill9
         sleep 3
         restore
         watch_center "$secs" ;;
esac
