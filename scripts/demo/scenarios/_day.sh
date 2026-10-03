#!/usr/bin/env bash
# Проигрыватель рабочего дня двух отделений (сценарии 01 и 08). Трасса — детерминированная: зерно SEED, доли услуг и
# пенсионеров, число приходов за «раунд». Один раунд: приходят клиенты, затем каждое открытое окно обслуживает одного.
# shellcheck disable=SC2154

# run_day ПРИХОДОВ ДОЛИ_УСЛУГ ДОЛЯ_ПЕНСИОНЕРОВ_% ПРИХОДОВ_В_РАУНД
run_day() {
  local total=$1 mix=$2 pens=$3 per_round=$4 i=0 round=0 peak=0 len svc cat node label n
  RSEED=${SEED:-42}

  if ! demo_clock_set 09:30 "${CLOCK_RATE:-60}"; then
    warn "Демо-часы выключены (DEMO_MODE=false): время идёт в реальном темпе, очередь не успеет «состариться» — просрочек не будет."
  fi
  say "Открываем окна: отделение $BRANCH — два окна, отделение $BRANCH2 — одно"
  open_window "$NODE" "$W1" "\"$S_PARCEL\",\"$S_SEND\""
  open_window "$NODE" "$W2" "\"$S_PARCEL\",\"$S_FIN\""
  open_window "$NODE2" "$W1" "$ALL_SERVICES"
  watch_ "табло и экран оператора (:8080/board, :8080/operator), обзор сети (центр /network)"
  pause 2

  say "Рабочий день: $total клиентов, по $per_round за раунд (QR в зале; каждый пятый — через центр из интернета)"
  local peak_node=$NODE
  while [ "$i" -lt "$total" ]; do
    round=$((round+1)); printf '\n   %s— раунд %s —%s\n' "$C_DIM" "$round" "$C_RESET"
    n=0
    while [ "$n" -lt "$per_round" ] && [ "$i" -lt "$total" ]; do
      i=$((i+1)); n=$((n+1)); label=$(printf 'c%02d' "$i")
      pick_service "$mix"; svc=$PICK_SVC; pick_category "$pens"; cat=$PICK_CAT
      node=$NODE; [ $((i % 3)) = 0 ] && node=$NODE2
      arrive_qr "$node" "$label" "$svc" "$cat" $([ $((i % 5)) = 0 ] && echo 1 || echo 0)
    done
    len=$(queue_len "$NODE"); [ "$len" -gt "$peak" ] && peak=$len
    pause
    serve "$NODE" "$W1" || true
    serve "$NODE" "$W2" || true
    serve "$NODE2" "$W1" || true
  done
  fact "пик очереди в $BRANCH, клиентов" "$peak"

  say "Пик: что видит руководитель, пока очередь ещё не разобрана"
  op "$NODE" GET "/api/v1/queue"
  PEAK_OVERDUE=$(echo "$BODY" | jq '[.[]|select(.overdue==true)]|length' 2>/dev/null || echo 0)
  fact "просрочено в очереди $BRANCH в момент пика" "${PEAK_OVERDUE:-0}"
  adm "$NODE" GET /api/v1/admin/recommendations
  PEAK_RECOMMEND=$(jget '.recommend_open_window')
  fact "рекомендация «открыть окно» в момент пика" "$PEAK_RECOMMEND"
  watch_ "админка (:8080/admin): баннер «Откройте окно», просроченные талоны красным; оператор: очередь с пометками"
  pause 2

  say "Приход закончился — окна дообслуживают очередь"
  local guard=0 progress
  while [ "$guard" -lt 60 ]; do
    guard=$((guard+1)); progress=0
    serve "$NODE" "$W1" && progress=1
    serve "$NODE" "$W2" && progress=1
    serve "$NODE2" "$W1" && progress=1
    [ "$progress" = 0 ] && break
  done

  say "Проверяем итоги дня"
  local done_n=0 bad_n=0 st
  for label in $(cut -f1 "$LABELS"); do
    st=$(status_of "$label")
    if [ "$st" = done ]; then done_n=$((done_n+1)); else bad_n=$((bad_n+1)); warn "$label: статус $st"; fi
  done
  assert "все выданные талоны обслужены ($total)" "$done_n" "$total"
  fact "обслужено в $BRANCH и $BRANCH2" "$done_n"
  adm "$NODE" GET /api/v1/admin/analytics/summary
  check "аналитика руководителя $BRANCH отвечает" 200 '.totals.issued > 0'
  fact "среднее ожидание в $BRANCH, с (по всему дню отделения)" "$(jget '.totals.avg_wait_sec // "—"')"
  center GET "/api/v1/network/branches?status=online&limit=200&q=$BRANCH"; local first=$BODY
  center GET "/api/v1/network/branches?status=online&limit=200&q=$BRANCH2"
  BODY=$(jq -c --slurp --arg a "$BRANCH" --arg b "$BRANCH2" '{online: [.[].items[]? | select(.id==$a or .id==$b)]}' <(echo "$first") <(echo "$BODY"))
  check "центр видит оба отделения на связи" 200 '[.online[].id]|unique|length>=2'
  watch_ "аналитика: :8080/admin → «Аналитика»; сеть: центр /network"
}
