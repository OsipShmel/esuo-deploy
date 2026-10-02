#!/usr/bin/env bash
# shellcheck disable=SC2034
CODE=monitoring
TITLE="Мониторинг отделений: логи без потерь, метрики и правила"
DESC="Ошибка на отделении видна в Grafana за секунды; ошибки за время обрыва связи доезжают после восстановления со своим временем; полные логи за полчаса — по одной кнопке; центр хранит метрики сервера отделения и снимок диагностики, а правило замечает перезапуск приложения. Нужен профиль monitoring."
NODES="$NODE"
CUT=${CUT_CMD:-"$ROOT/scripts/chaos/cut-branch-link.sh $BRANCH"}
LOKI=${LOKI:-http://localhost:3100}
HUB_MONITOR=${HUB_MONITOR:-http://localhost:8222}
RESTART=${RESTART_CMD:-"docker restart esuo-node-$BRANCH"}   # стенд из нескольких ПК: свой способ перезапустить узел
scenario() {
  local run since restored i m found lag
  run=$(date +%s)
  since=$(( run - 3600 ))000000000
  # loki_count ЗАПРОС — сколько строк Loki отдаёт по запросу LogQL за последний час
  loki_count() {
    curl -s -G "$LOKI/loki/api/v1/query_range" --data-urlencode "query=$1" --data-urlencode "start=$since" \
      --data-urlencode "limit=1000" | jq '[.data.result[]?.values[]?] | length' 2>/dev/null || echo 0
  }
  # loki_ts ТЕКСТ — время строки с этим текстом в Loki (нс), пусто — строки нет
  loki_ts() {
    curl -s -G "$LOKI/loki/api/v1/query_range" --data-urlencode "query={branch=\"$BRANCH\",source=\"stream\"} |= \"$1\"" \
      --data-urlencode "start=$since" --data-urlencode "limit=10" | jq -r '[.data.result[]?.values[]?[0]] | first // empty'
  }
  wait_loki() { local j; for j in $(seq 1 "$2"); do [ -n "$(loki_ts "$1")" ] && { echo "$j"; return 0; }; sleep 1; done; return 1; }
  demo_error() { adm "$NODE" POST /api/v1/admin/demo/log -d "$(jq -nc --arg m "$1" '{level:"error",message:$m}')"; }
  evt_lag() {
    curl -s "$HUB_MONITOR/jsz?streams=true" | jq -r --arg api "\$JS.branch-$BRANCH.API" \
      '[.account_details[]?.stream_detail[]? | select(.name=="CENTER_EVT") | .sources[]? | select(.external.api==$api) | .lag] | first // "нет"'
  }

  [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$LOKI/ready")" = 200 ] \
    || { warn "Loki не отвечает ($LOKI): docker compose --profile monitoring up -d"; return 1; }
  [ "$(docker inspect -f '{{.State.Running}}' "esuo-vector-$BRANCH" 2>/dev/null)" = true ] \
    || { warn "сборщик логов esuo-vector-$BRANCH не запущен: docker compose --profile monitoring up -d"; return 1; }

  say "Отделение пишет ошибку — она появляется в Grafana"
  m="esuo-mon-$run-live"
  demo_error "$m"
  assert "ошибка записана на отделении" "$STATUS" 204
  found=$(wait_loki "$m" 10)
  assert "ошибка видна в Loki за ≤10 с" "$([ -n "$found" ] && echo yes || echo no)" yes
  [ -n "$found" ] && fact "ошибка доехала до Loki, с" "$found"
  ev "monitoring|live-error"
  watch_ "Grafana http://localhost:3000 → Explore → Loki: {branch=\"$BRANCH\", level=\"error\"}"
  pause 2

  say "Связь с центром рвётся — отделение пишет ошибки дальше"
  # shellcheck disable=SC2086
  $CUT cut; ev "chaos|link|cut"
  for i in 1 2 3; do demo_error "esuo-mon-$run-offline-$i"; done
  assert "ошибки во время обрыва записаны" "$STATUS" 204
  note "Строки лежат в BRANCH_LOG на диске отделения и ждут связи."
  sleep 5                                         # обрыв длится заметно: время записи и доставки различимы и при FAST=1

  say "Связь восстановлена — накопленное доезжает со своим временем"
  restored=$(python3 -c 'import time; print(time.time_ns())')
  # shellcheck disable=SC2086
  $CUT restore; ev "chaos|link|restore"
  for i in 1 2 3; do
    m="esuo-mon-$run-offline-$i"
    found=$(wait_loki "$m" 90)
    assert "ошибка $i из обрыва доехала" "$([ -n "$found" ] && echo yes || echo no)" yes
    [ -n "$found" ] && assert "ошибка $i хранит время записи, а не доставки" \
      "$([ "$(loki_ts "$m")" -lt "$restored" ] && echo yes || echo no)" yes
  done
  ev "monitoring|offline-errors-delivered"
  for i in $(seq 1 90); do lag=$(evt_lag); [ "$lag" = 0 ] && break; sleep 1; done
  assert "события отделения тоже догнаны (лаг источника CENTER_EVT = 0)" "$lag" 0

  say "Руководитель сети запрашивает полные логи отделения за полчаса"
  center POST "/api/v1/branches/$BRANCH/diagnostics/logs" -d '{"minutes":30}'
  check "логи выгружены, есть ссылка на Grafana" 200 '.lines > 0 and (.grafana_url | type == "string")'
  local pull lines
  pull=$(jget .pull_id); lines=$(jget .lines)
  fact "строк в выгрузке" "$lines"
  for i in $(seq 1 15); do [ "$(loki_count "{branch=\"$BRANCH\",pull=\"$pull\"}")" -gt 0 ] && break; sleep 1; done
  assert "выгрузка видна в Loki" "$([ "$(loki_count "{branch=\"$BRANCH\",pull=\"$pull\"}")" -gt 0 ] && echo yes || echo no)" yes
  ev "monitoring|pull"
  watch_ "ссылка из ответа API: $(jget .grafana_url | head -c 80)…"
  pause 2

  say "Центр хранит метрики сервера отделения — по ним графики и правила"
  center GET "/api/v1/branches/$BRANCH/metrics"
  check "история метрик за час: в последней точке блок sys и очередь" 200 \
    'length > 0 and (last.sys.cpu_count // 0) > 0 and (last.sys.heap_used_mb != null) and (last.queue.waiting != null)'
  fact "точек метрик за час" "$(jget length)"
  ev "monitoring|metrics"
  watch_ "Grafana → Dashboards → ЕСУО · Отделение ($BRANCH): CPU, память, база, API, связь с центром"
  pause 2

  say "Руководитель сети снимает диагностику: потоки, база, outbox, связь — что внутри прямо сейчас"
  local t0 took sid
  t0=$(python3 -c 'import time; print(time.time())')
  center POST "/api/v1/branches/$BRANCH/diagnostics/snapshots"
  took=$(python3 -c "import time; print(round(time.time() - $t0, 1))")
  check "снимок снят и сохранён: все разделы на месте" 200 \
    '(["app","jvm","db_pool","db","outbox","nats","config","disk","lifecycle"] - (.payload | keys)) == [] and (.id | length) > 0'
  assert "снимок за ≤10 с" "$(awk -v t="$took" 'BEGIN { print (t <= 10) ? "yes" : "no" }')" yes
  sid=$(jget .id)
  fact "снимок, с" "$took"
  fact "размер снимка, КБ" "$(( $(jget .size_bytes) / 1024 ))"
  center GET "/api/v1/branches/$BRANCH/diagnostics/snapshots"
  check "снимок есть в списке снимков отделения" 200 ".[0].id == \"$sid\""
  ev "monitoring|snapshot"
  watch_ "GET /api/v1/diagnostics/snapshots/$sid — снимок целиком"
  pause 2

  say "Приложение отделения перезапускается — правило замечает это по метрикам"
  local alert=""
  # shellcheck disable=SC2086
  $RESTART >/dev/null; ev "chaos|node|restart"
  for i in $(seq 1 60); do [ "$(curl -s -o /dev/null -w '%{http_code}' "$NODE/actuator/health")" = 200 ] && break; sleep 2; done
  for i in $(seq 1 45); do                        # heartbeat → сброс пачкой (5 с) → правила (15 с)
    center GET /api/v1/network/overview
    alert=$(echo "$BODY" | jq -r --arg b "$BRANCH" '.branches[]? | select(.id==$b) | .alerts[]? | select(.code=="sys.restart") | .text' | head -1)
    [ -n "$alert" ] && break; sleep 2
  done
  assert "в обзоре сети аномалия «перезапуск» с понятным текстом" "$([ -n "$alert" ] && echo yes || echo no)" yes
  [ -n "$alert" ] && fact "аномалия" "$alert"
  ev "monitoring|restart-alert"
  watch_ "/network → «Аномалии»: «$BRANCH: $alert»"
}
