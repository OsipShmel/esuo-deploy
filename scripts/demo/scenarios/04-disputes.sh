#!/usr/bin/env bash
# shellcheck disable=SC2034
CODE=disputes
TITLE="Спорные ситуации: закрытие окна, перенаправление, неявка"
DESC="Закрытие окна с клиентом (graceful и force), перенаправление на другую услугу, повторные вызовы и неявка, возврат в хвост (S1–S16 дизайн-дока)."
NODES="$NODE"
scenario() {
  local i
  reset_nodes "$NODE"

  say "Открываем два окна; в очереди пять посылок"
  open_window "$NODE" "$W1" "\"$S_PARCEL\",\"$S_SEND\""
  open_window "$NODE" "$W2" "\"$S_PARCEL\",\"$S_FIN\""
  for i in 1 2 3 4 5; do arrive_qr "$NODE" "d$i" "$S_PARCEL" default; done
  pause 2

  say "Окно 1 вызывает d1 и начинает обслуживание, затем руководитель закрывает окно мягко (graceful)"
  call_next "$NODE" "$W1"; assert "вызван первый пришедший" "$CALLED" d1
  pause; tcmd "$NODE" d1 start; pause
  close_window "$NODE" "$W1" graceful
  check "окно перешло в «закрывается» (клиента не бросаем)" 200 '.status=="closing"'
  watch_ "экран оператора: «Окно закрывается после текущего клиента»"
  pause 2; tcmd "$NODE" d1 complete
  op "$NODE" GET "/api/v1/windows"
  assert "после завершения клиента окно закрылось" "$(echo "$BODY" | jq -r --arg id "$W1" '.[]|select(.id==$id)|.status')" closed

  say "Окно 2 вызывает d2, затем окно закрывают принудительно (force): клиент возвращается в голову очереди"
  call_next "$NODE" "$W2"; assert "вызван d2" "$CALLED" d2
  pause; close_window "$NODE" "$W2" force
  check "окно закрыто" 200 '.status=="closed"'
  assert "d2 снова ждёт" "$(status_of d2)" waiting
  op "$NODE" GET "/api/v1/queue?status=waiting"
  assert "d2 — первый в очереди, не потерял место" "$(label_of "$(echo "$BODY" | jq -r '.[0].ticket_id')")" d2
  pause 2

  say "Открываем оба окна снова. d2 нужна другая услуга: перенаправление на платежи"
  open_window "$NODE" "$W1" "\"$S_PARCEL\",\"$S_SEND\""
  open_window "$NODE" "$W2" "\"$S_PARCEL\",\"$S_FIN\""
  call_next "$NODE" "$W1"; assert "окно 1 вызывает d2 первым" "$CALLED" d2
  pause; tcmd "$NODE" d2 redirect "$(jq -nc --arg s "$S_FIN" '{service_id:$s,reason:"нужен денежный перевод"}')"
  check "перенаправление принято" 200 '.status=="waiting" and .service.id=="'"$S_FIN"'"'
  call_next "$NODE" "$W2"; assert "платёжное окно берёт перенаправленного d2 первым" "$CALLED" d2
  why_called "$NODE" d2
  pause; tcmd "$NODE" d2 start; pause 0.7; tcmd "$NODE" d2 complete

  say "Неявка: клиент не подходит после повторных вызовов"
  call_next "$NODE" "$W1"; assert "вызван d3" "$CALLED" d3
  pause; tcmd "$NODE" d3 recall; pause; tcmd "$NODE" d3 recall; pause
  tcmd "$NODE" d3 no-show
  check "статус «не подошёл»" 200 '.status=="no_show"'
  watch_ "табло: строка d3 исчезла, оператору предложен следующий"

  say "Клиент отошёл: вернуть в хвост очереди"
  call_next "$NODE" "$W1"; assert "вызван d4" "$CALLED" d4
  pause; tcmd "$NODE" d4 requeue '{"mode":"to_tail"}'
  check "d4 вернулся в очередь" 200 '.status=="waiting"'
  op "$NODE" GET "/api/v1/queue?status=waiting"
  assert "d4 теперь последний" "$(label_of "$(echo "$BODY" | jq -r '.[-1].ticket_id')")" d4

  say "Обслуживаем остаток и убеждаемся, что никого не потеряли"
  local guard=0
  while [ "$guard" -lt 20 ]; do guard=$((guard+1)); serve "$NODE" "$W1" || serve "$NODE" "$W2" || break; done
  assert "d5 обслужен" "$(status_of d5)" done
  assert "d4 обслужен" "$(status_of d4)" done
  assert "d3 остался «не подошёл»" "$(status_of d3)" no_show
  note "Опоздавшая запись (S: convert_to_qr / reject) требует ожидания слота и подтверждена автотестами CheckInIT/PostponeIT."
}
