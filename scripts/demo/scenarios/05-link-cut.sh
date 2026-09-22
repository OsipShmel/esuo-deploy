#!/usr/bin/env bash
# shellcheck disable=SC2034
CODE=link-cut
TITLE="Обрыв связи с центром посреди дня"
DESC="Отделение продолжает работать без центра, события копятся и после восстановления связи докачиваются. Лучше всего видно на /network."
NODES="$NODE"
CUT=${CUT_CMD:-"$ROOT/scripts/chaos/cut-branch-link.sh $BRANCH"}
scenario() {
  local i
  reset_nodes "$NODE"
  online() { curl -s "$CENTER/api/v1/network/overview" | jq -r --arg b "$BRANCH" '.branches[]|select(.id==$b)|.online'; }

  say "Начало дня: окно открыто, клиенты идут, центр видит отделение"
  open_window "$NODE" "$W1" "$ALL_SERVICES"
  for i in 1 2 3; do arrive_qr "$NODE" "a$i" "$S_PARCEL" default; done
  serve "$NODE" "$W1"
  assert "центр видит отделение на связи" "$(online)" true
  watch_ "центр /network: карточка $BRANCH «на связи»"
  pause 2

  say "Обрываем связь отделения с центром"
  # shellcheck disable=SC2086
  $CUT cut; ev "chaos|link|cut"
  note "Ждём, пока центр заметит пропажу heartbeat (до ~40 с)…"
  local seen=0
  for i in $(seq 1 30); do [ "$(online)" = false ] && { seen=1; break; }; sleep 2; done
  assert "центр показывает отделение «нет связи»" "$seen" 1
  http GET "$CENTER/api/v1/branches/$BRANCH/slots?service_id=$S_PARCEL&date=$(date +%F)"
  check "клиент из интернета получает понятный отказ 503" 503 '.code=="BRANCH_UNAVAILABLE"'
  watch_ "клиентская страница через центр: «Отделение сейчас не на связи»; /network: красная карточка"

  say "Отделение работает автономно: зал по-прежнему обслуживается"
  for i in 4 5 6 7 8; do arrive_qr "$NODE" "a$i" "$S_PARCEL" default; done
  for i in 1 2 3 4; do serve "$NODE" "$W1" || break; done
  http GET "$NODE/api/v1/public/board"; check "табло отделения работает без центра" 200 '.branch_id=="'"$BRANCH"'"'
  watch_ "табло и оператор (:8080): всё работает; в /network растёт «в очереди на отправку»"

  say "Восстанавливаем связь"
  # shellcheck disable=SC2086
  $CUT restore; ev "chaos|link|restore"
  local back=0
  for i in $(seq 1 40); do [ "$(online)" = true ] && { back=1; break; }; sleep 3; done
  assert "центр снова видит отделение" "$back" 1
  note "Докачка накопленных событий — до ~1 минуты; очередь на отправку в /network должна стать 0."
  local drained=0
  for i in $(seq 1 30); do
    http GET "$CENTER/api/v1/network/overview"
    [ "$(echo "$BODY" | jq -r --arg b "$BRANCH" '[.branches[]|select(.id==$b)|(.outbox_pending // 0)]|first')" = 0 ] && { drained=1; break; }
    sleep 3
  done
  assert "накопленные события доехали (очередь отправки пуста)" "$drained" 1

  say "Дообслуживаем оставшихся"
  local guard=0; while [ "$guard" -lt 20 ]; do guard=$((guard+1)); serve "$NODE" "$W1" || break; done
  assert "клиент, выданный во время обрыва, обслужен (a8)" "$(status_of a8)" done
}
