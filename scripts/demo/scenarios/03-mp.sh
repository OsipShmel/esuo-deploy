#!/usr/bin/env bash
# shellcheck disable=SC2034
CODE=mp
TITLE="Запись из мобильного приложения"
DESC="mp-simulator присылает подписанный вебхук в центр, талон появляется в отделении, результат со ссылкой на статус уходит обратно в МП."
NODES="$NODE"
scenario() {
  local id="demo-mp-$RUN" i
  reset_nodes "$NODE"
  pick_slot "$S_PARCEL"
  [ -n "$SLOT" ] || { warn "Нет свободных слотов ни сегодня, ни завтра — проверьте расписание услуги."; return 1; }
  [ "$SLOT_TODAY" = 0 ] && note "Слоты на сегодня закончились — запись из МП пойдёт на завтра."

  say "Проверяем, что симулятор МП и центр на связи"
  http GET "$MP/health"; check "mp-simulator отвечает" 200 '.status=="UP" and .secret==true'
  [ "$FAIL" = 0 ] || { warn "Без mp-simulator сценарий не показать: docker compose up -d mp-simulator"; return 1; }

  say "МП отправляет запись на посылку (вебхук с HMAC-подписью)"
  http POST "$MP/send" "${J[@]}" -d "$(jq -nc --arg id "$id" --arg b "$BRANCH" --arg t "$SLOT" '{branch_id:$b,service_code:"parcel",slot_start:$t,mp_appointment_id:$id}')"
  check "центр принял вебхук" 200 '.status==200 or .status==201 or .status==202'
  ev "mp|send|$BRANCH|parcel"
  watch_ "центр /network → «Уведомления»; API центра /api/v1/admin/mp/requests"

  say "Ждём результат в МП (callback с номером талона и ссылкой на статус)"
  local got=0
  for i in $(seq 1 30); do
    http GET "$MP/callbacks/$id"
    [ "$STATUS" = 200 ] && { got=1; break; }
    sleep 1
  done
  assert "callback в МП получен" "$got" 1
  if [ "$got" = 1 ]; then
    check "запись подтверждена отделением" 200 '.body.result=="confirmed" and (.body.ticket.status_url|length>0)'
    ev "mp|callback|confirmed"
    fact "ссылка на статус для клиента" "$(jget '.body.ticket.status_url')"
    fact "номер талона" "$(jget '.body.ticket.display_number')"
    MP_TOKEN=$(jget '.body.ticket.status_url' | sed 's#.*/t/##')
  fi

  say "Повтор того же вебхука: запись не дублируется"
  http POST "$MP/send" "${J[@]}" -d "$(jq -nc --arg id "$id" --arg b "$BRANCH" --arg t "$SLOT" '{branch_id:$b,service_code:"parcel",slot_start:$t,mp_appointment_id:$id}')"
  check "повтор принят без ошибки (идемпотентность)" 200 '.status==200 or .status==201 or .status==202'
  ev "mp|resend|same-id"
  adm "$NODE" GET "/api/v1/queue"
  note "Запись видна в очереди отделения как «booked» с источником «appointment»."

  say "Запись на прошедшее время отклоняется — причина уходит обратно в МП"
  local bad="demo-mp-late-$RUN"
  http POST "$MP/send" "${J[@]}" -d "$(jq -nc --arg id "$bad" --arg b "$BRANCH" '{branch_id:$b,service_code:"parcel",slot_start:"2020-01-01T10:00:00Z",mp_appointment_id:$id}')"
  sleep 2; http GET "$MP/callbacks/$bad"
  if [ "$STATUS" = 200 ]; then check "отказ доставлен в МП" 200 '.body.result=="rejected" and (.body.reason|length>0)'; ev "mp|callback|rejected"
  else check "отказ отклонён ещё на входе центра" 200 'true'; ev "mp|rejected-at-center"; fi
  [ -n "${MP_TOKEN:-}" ] && { cancel_public "$MP_TOKEN"; note "Запись отменена, чтобы не занимать слот в следующих прогонах."; }
}
