#!/usr/bin/env bash
# shellcheck disable=SC2034
CODE=normal-day
TITLE="Обычный рабочий день, два отделения"
DESC="Поток клиентов по профилю обычного дня, три окна на двух узлах: вызовы, обслуживание, обзор сети."
NODES="$NODE $NODE2"
scenario() {
  reset_nodes "$NODE" "$NODE2"
  run_day 18 "parcel:45 send:35 finance:20" 15 3
}
