#!/usr/bin/env bash
# Разрыв/восстановление связи отделения с центром в all-in-one стенде.
#   ./scripts/chaos/cut-branch-link.sh b01          — разорвать
#   ./scripts/chaos/cut-branch-link.sh b01 restore  — восстановить
# На стенде из нескольких ПК то же самое — выдернуть кабель / закрыть 7422 на центре.
set -euo pipefail
BRANCH=${1:?branch id, например b01}
LEAF=esuo-nats-leaf-$BRANCH
if [ "${2:-}" = "restore" ]; then
  docker network connect esuo_wan "$LEAF" && echo "связь $BRANCH ↔ центр восстановлена (leaf переподключится сам за ~1 с)"
else
  docker network disconnect esuo_wan "$LEAF" && echo "связь $BRANCH ↔ центр разорвана; очередь отделения работает автономно"
fi
