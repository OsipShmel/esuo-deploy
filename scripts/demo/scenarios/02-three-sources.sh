#!/usr/bin/env bash
# shellcheck disable=SC2034
CODE=three-sources
TITLE="Три источника талонов в одной очереди"
DESC="Запись из интернета через центр, QR из интернета/зала и живая очередь у оператора: одна очередь, одни правила."
NODES="$NODE"
scenario() {
  reset_nodes "$NODE"
  local slot="" tomorrow_used=0 svc="$S_PARCEL"

  demo_clock_set 10:05 1 || true          # с демо-часами сценарий не зависит от времени суток: слоты на сегодня есть
  say "Открываем окно на посылки и отправку"
  open_window "$NODE" "$W1" "\"$S_PARCEL\",\"$S_SEND\""

  say "Источник 1: предварительная запись через сайт (центр)"
  pick_slot "$svc"; slot=$SLOT
  if [ -z "$slot" ]; then warn "Нет свободных слотов ни сегодня, ни завтра — проверьте расписание услуги."; return 1; fi
  if [ "$SLOT_TODAY" = 0 ]; then
    warn "На сегодня слотов уже нет (отделение закрыто) — записываем на завтра; приход по записи покажем только днём."
    tomorrow_used=1
  fi
  http POST "$CENTER/api/v1/branches/$BRANCH/appointments" "${J[@]}" -H "Idempotency-Key: demo-$RUN-web" -d "$(jq -nc --arg s "$svc" --arg t "$slot" '{service_id:$s,slot_start:$t}')"
  check "запись через центр создана (источник appointment)" 201 '.status=="booked" and .source=="appointment"'
  label_set c-web "$NODE" "$(jget .ticket_id)" "$(jget .token)"; ev "arrive|$BRANCH|c-web|appointment|посылка|default"
  watch_ "клиентская страница: $CENTER/b/$BRANCH → «Мой талон»"
  pause

  if [ "$SLOT_TODAY" = 1 ] && demo_clock_on; then
    demo_clock_at "$(iso_minus "$SLOT" 14)" 1 && note "Время идёт: до слота записи 14 минут — клиент по записи уже может прийти."
  fi

  say "Источник 2: клиент отсканировал QR и взял талон с телефона (через центр)"
  arrive_qr "$NODE" c-qr "$S_PARCEL" default 1
  pause

  say "Источник 3: оператор выдал талон живой очереди"
  arrive_walkin "$NODE" "$W1" c-desk "$S_SEND" default
  pause

  if [ "$tomorrow_used" = 0 ]; then
    say "Клиент по записи пришёл в отделение и подтверждает приход"
    http POST "$NODE/api/v1/public/tickets/$(ttok c-web)/check-in" "${J[@]}" -d "{\"qr_code\":\"demo:$BRANCH\"}"
    check "check-in по записи принят" 200 '.status=="waiting"'
    ev "cmd|$BRANCH|c-web|check-in"
  fi

  say "Все три — в одной очереди"
  op "$NODE" GET "/api/v1/queue"
  if [ "$tomorrow_used" = 0 ]; then check "в очереди рядом талон записи, QR и живой" 200 '[.[]|.source]|unique|length==3'
  else check "в очереди рядом QR и живой (запись на завтра ждёт своего дня)" 200 '[.[]|.source]|unique|length>=2'; fi
  fact "источники в очереди" "$(echo "$BODY" | jq -c '[.[]|.source]|unique')"
  watch_ "экран оператора: очередь с иконками источников"
  pause

  say "Окно вызывает по одним правилам (EDF с допуском по дедлайнам)"
  local n
  for n in 1 2 3; do
    call_next "$NODE" "$W1"; [ -n "$CALLED" ] || break
    why_called "$NODE" "$CALLED"
    pause; tcmd "$NODE" "$CALLED" start; pause 0.7; tcmd "$NODE" "$CALLED" complete
  done
  note "«Причина вызова» и список кандидатов берутся из журнала ticket.called: система объясняет своё решение."
  # убираем за собой: необслуженная запись не должна занимать слот и очередь в следующих прогонах
  if [ "$(status_of c-web)" != done ]; then cancel_public "$(ttok c-web)"; note "Запись не была вызвана и отменена, чтобы не занимать слот."; fi
}
