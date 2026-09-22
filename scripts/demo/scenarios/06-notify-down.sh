#!/usr/bin/env bash
# shellcheck disable=SC2034
CODE=notify-down
TITLE="Отказ службы уведомлений"
DESC="Очередь живёт, письма копятся в DLQ, breaker размыкается; после восстановления он закрывается, а письма повторяются из DLQ. Около 4 минут."
NODES="$NODE"
scenario() {
  local i
  local status_url="$CENTER/api/v1/admin/notifications/status"
  ns() { curl -s "$status_url"; }
  nchaos() { curl -fsS -X POST "$NOTIFY/chaos" -H 'Content-Type: application/json' -d "{\"mode\":\"$1\"}" >/dev/null; }
  npoll() { # npoll ОПИСАНИЕ СЕКУНД jq-условие-по-status
    local n
    for n in $(seq 1 "$2"); do ns | jq -e "$3" >/dev/null 2>&1 && { assert "$1 (за ${n} с)" ok ok; return 0; }; sleep 1; done
    assert "$1" "не за $2 с: $(ns | head -c 200)" ok
  }
  walkins() { local tag=$1 count=$2 j; for j in $(seq 1 "$count"); do arrive_walkin "$NODE" "$W1" "$tag$j" "$S_PARCEL" default; done; }
  drain_queue() { local g=0; while [ "$g" -lt 30 ]; do g=$((g+1)); serve "$NODE" "$W1" || break; done; }

  http GET "$NOTIFY/health"; [ "$STATUS" = 200 ] || { warn "notify-mock не отвечает ($NOTIFY): docker compose up -d notify-mock"; return 1; }
  reset_nodes "$NODE"

  say "Всё штатно: провайдер уведомлений работает, breaker закрыт"
  nchaos ok
  open_window "$NODE" "$W1" "\"$S_PARCEL\""
  local before dlq0 sent0
  before=$(ns); dlq0=$(echo "$before" | jq '.counts.dlq_open // 0'); sent0=$(echo "$before" | jq '.counts.sent // 0')
  fact "breaker до отказа" "$(echo "$before" | jq -r '.circuit_breaker.state')"
  watch_ "центр /network → «Уведомления»: breaker CLOSED, счётчики, DLQ"
  pause 2

  say "Провайдер «падает»: клиенты встают в очередь — «очередь приближается» пытается отправить письма"
  nchaos error; ev "chaos|notify|error"
  walkins n 4
  serve "$NODE" "$W1"
  npoll "breaker разомкнулся (OPEN)" 120 '.circuit_breaker.state=="OPEN"'
  npoll "письма копятся в DLQ (больше $dlq0)" 300 ".counts.dlq_open > $dlq0"
  watch_ "центр /network → «Уведомления»: красный breaker, растущий DLQ"

  say "Очередь при этом работает: зал обслуживается без уведомлений"
  drain_queue
  op "$NODE" GET "/api/v1/queue?status=waiting"; check "очередь разобрана, зал обслужен" 200 'length==0'
  pause 2

  say "Провайдер восстановлен"
  nchaos ok; ev "chaos|notify|ok"
  note "Breaker ждёт 30 секунд, затем пробует часть писем (HALF_OPEN)…"
  sleep 31
  walkins u 8
  npoll "новые письма доставляются (sent растёт)" 120 ".counts.sent > $sent0"
  # Полуоткрытый breaker пропускает пять пробных писем (application.yml центра); восьми клиентов на это не всегда
  # хватает, и он честно остаётся HALF_OPEN до следующих писем — закрывается на повторе из DLQ (шаг 5).
  npoll "breaker пропускает письма снова (не OPEN)" 90 '.circuit_breaker.state!="OPEN"'
  drain_queue

  say "Руководитель повторяет письма из DLQ (центр → «Уведомления» → «Повторить»)"
  local ids n=0 d=0 id res
  ids=$(curl -s "$CENTER/api/v1/admin/dlq?kind=notification&status=open" | jq -r '.[].id')
  for id in $ids; do
    n=$((n+1)); res=$(curl -s -X POST "$CENTER/api/v1/admin/dlq/$id/retry" | jq -r .result); [ "$res" = delivered ] && d=$((d+1))
  done
  assert "все письма из DLQ доставлены при повторе ($n)" "$d" "$n"
  fact "повторено из DLQ, писем" "$n"
  ev "dlq|retry|all"
  local after; after=$(ns)
  assert "breaker снова закрыт (CLOSED)" "$(echo "$after" | jq -r '.circuit_breaker.state')" CLOSED
  fact "состояние после" "$(echo "$after" | jq -c '{breaker:.circuit_breaker.state, counts:.counts}')"
}