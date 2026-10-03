#!/usr/bin/env bash
# Нехватка CPU на ПК отделения: узлу оставляем долю ядра и нагружаем его API.
#   ./scripts/chaos/cpu-starve.sh b01 [секунд=90] [доля CPU=0.1]  — урезать, нагрузить, смотреть центр, вернуть
#   ./scripts/chaos/cpu-starve.sh b01 start [доля CPU=0.1]        — урезать и нагрузить, не ждать (для сценариев)
#   ./scripts/chaos/cpu-starve.sh b01 stop                        — снять нагрузку и лимит
# Ожидаемо (DEMO_MODE=true, правила ×0,1): через ~30–60 с в обзоре сети «ПК отделения не справляется: CPU …» и
# «API отделения отвечает медленно»; heartbeat может не успевать читать очередь — «данные очереди не обновляются».
# Лимит снимается до «все ядра хоста»: docker update не умеет вернуть «без лимита» (0).
set -uo pipefail
BRANCH=${1:?branch id, например b01}
. "$(dirname "$0")/lib-stress.sh"

starve() {
  docker update --cpus "${1:-0.1}" "$NODE_CT" >/dev/null || { echo "не удалось ограничить $NODE_CT"; exit 1; }
  load_start 8
  echo "$NODE_CT: CPU ${1:-0.1}, API нагружено 8 клиентами"
}
restore() {
  load_stop
  docker update --cpus "$(docker info --format '{{.NCPU}}')" "$NODE_CT" >/dev/null && echo "$NODE_CT: нагрузка снята, CPU — все ядра хоста"
}

case "${2:-run}" in
  start) starve "${3:-0.1}" ;;
  stop)  restore ;;
  *)     secs=${2:-90}; [ "$secs" = run ] && secs=90
         trap restore EXIT INT TERM
         starve "${3:-0.1}"
         watch_center "$secs" ;;
esac
