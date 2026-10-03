#!/usr/bin/env bash
# shellcheck disable=SC2034
CODE=health
TITLE="Хаос на ПК отделения: центр видит, что именно сломалось"
DESC="Не хватает CPU — правило в обзоре сети; зависла блокировка в базе — отделение на связи, «очередь не обновляется», правило про пул, снимок показывает, кто держит блокировку; приложение убито kill -9 во время обрыва связи — после восстановления правило «перезапуск», а ошибки до и после убийства доехали со своим временем. В конце всё возвращается. Нужен профиль monitoring и DEMO_MODE=true. Отдельные поломки — scripts/chaos/{cpu-starve,db-lock,db-freeze,node-kill}.sh."
NODES="$NODE"
CHAOS="$ROOT/scripts/chaos"
CUT=${CUT_CMD:-"$CHAOS/cut-branch-link.sh $BRANCH"}
LOKI=${LOKI:-http://localhost:3100}
scenario() {
  local run since i card alert snap took t0
  run=$(date +%s)
  since=$(( run - 3600 ))000000000
  loki_ts() {                                     # время строки с этим текстом в Loki (нс), пусто — строки нет
    curl -s -G "$LOKI/loki/api/v1/query_range" --data-urlencode "query={branch=\"$BRANCH\",source=\"stream\"} |= \"$1\"" \
      --data-urlencode "start=$since" --data-urlencode "limit=10" | jq -r '[.data.result[]?.values[]?[0]] | first // empty'
  }
  demo_error() { adm "$NODE" POST /api/v1/admin/demo/log -d "$(jq -nc --arg m "$1" '{level:"error",message:$m}')"; }
  card() { center GET /api/v1/network/overview; echo "$BODY" | jq -c --arg b "$BRANCH" '.branches[]? | select(.id==$b)'; }
  alert_of() { echo "$1" | jq -r --arg c "$2" '(.alerts // [])[] | select(.code==$c) | .text' | head -1; }
  # wait_card СЕКУНД jq-УСЛОВИЕ — ждёт, пока карточка отделения в обзоре сети станет такой; карточка — в $CARD
  wait_card() { for i in $(seq 1 $(( $1 / 3 ))); do CARD=$(card); echo "$CARD" | jq -e "$2" >/dev/null 2>&1 && return 0; sleep 3; done; return 1; }
  restore_all() {
    "$CHAOS/cpu-starve.sh" "$BRANCH" stop >/dev/null 2>&1
    "$CHAOS/db-lock.sh" "$BRANCH" stop >/dev/null 2>&1
    # shellcheck disable=SC2086
    $CUT restore >/dev/null 2>&1
    "$CHAOS/node-kill.sh" "$BRANCH" stop >/dev/null 2>&1
  }
  # поверх обработчика run.sh (демо-часы, рабочая папка): сначала вернуть стенд, потом его
  local prev_exit
  prev_exit=$(trap -p EXIT); prev_exit=${prev_exit#trap -- \'}; prev_exit=${prev_exit%\' EXIT}
  trap "restore_all; $prev_exit" EXIT
  trap 'restore_all; exit 130' INT TERM

  for i in $(seq 1 10); do                        # после рестарта Loki отвечает 503, пока ingester не готов
    [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$LOKI/ready")" = 200 ] && break; sleep 3
  done
  [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$LOKI/ready")" = 200 ] \
    || { warn "Loki не отвечает ($LOKI): docker compose --profile monitoring up -d"; return 1; }
  [ "$(docker inspect -f '{{.State.Running}}' "esuo-vector-$BRANCH" 2>/dev/null)" = true ] \
    || { warn "сборщик логов esuo-vector-$BRANCH не запущен: docker compose --profile monitoring up -d"; return 1; }

  say "ПК отделения не хватает процессора: узлу оставлена десятая доля ядра, API под нагрузкой"
  "$CHAOS/cpu-starve.sh" "$BRANCH" start 0.1 >/dev/null; ev "chaos|cpu|starve"
  wait_card 150 '(.alerts // []) | any(.code == "sys.cpu")'
  alert=$(alert_of "$CARD" sys.cpu)
  assert "в обзоре сети правило «ПК не справляется»" "$([ -n "$alert" ] && echo yes || echo no)" yes
  [ -n "$alert" ] && fact "правило" "$alert"
  "$CHAOS/cpu-starve.sh" "$BRANCH" stop >/dev/null; ev "chaos|cpu|restore"
  watch_ "/network → карточка $BRANCH, строка «Узел»: CPU и правило; Grafana → ЕСУО · Отделение → CPU"
  pause 2

  say "В базе отделения зависла блокировка: запросы API встали в очередь и заняли весь пул соединений"
  "$CHAOS/db-lock.sh" "$BRANCH" start tickets >/dev/null; ev "chaos|db|lock"
  wait_card 90 '.online and (.anomalies | index("db_unavailable")) and ((.alerts // []) | any(.code == "sys.db_pool"))'
  assert "отделение на связи — сломана база, а не связь" "$(echo "$CARD" | jq -r '.online')" true
  assert "в обзоре сети «данные очереди не обновляются»" "$(echo "$CARD" | jq -r '.anomalies | index("db_unavailable") != null')" true
  alert=$(alert_of "$CARD" sys.db_pool)
  assert "в обзоре сети правило «запросы ждут соединения»" "$([ -n "$alert" ] && echo yes || echo no)" yes
  t0=$(python3 -c 'import time; print(time.time())')
  center POST "/api/v1/branches/$BRANCH/diagnostics/snapshots"
  took=$(python3 -c "import time; print(round(time.time() - $t0, 1))")
  check "снимок при занятом пуле показывает, кто держит блокировку" 200 \
    '(.payload.db.blocking | type == "array" and length > 0) and (.payload.db_pool.waiting > 0)'
  assert "снимок за ≤10 с" "$(awk -v t="$took" 'BEGIN { print (t <= 10) ? "yes" : "no" }')" yes
  fact "ждут соединения" "$(jget .payload.db_pool.waiting)"
  fact "заблокированных запросов" "$(jget '.payload.db.blocking | length')"
  "$CHAOS/db-lock.sh" "$BRANCH" stop >/dev/null; ev "chaos|db|unlock"
  wait_card 60 '.online and (.anomalies | index("db_unavailable") | not)'
  assert "блокировка снята — данные очереди снова свежие" "$(echo "$CARD" | jq -r '.anomalies | index("db_unavailable") == null')" true
  watch_ "/network → «Аномалии»; снимок — кнопка «Снять диагностику», раздел db.blocking"
  pause 2

  say "Связь с центром рвётся, а приложение отделения падает по kill -9"
  # shellcheck disable=SC2086
  $CUT cut >/dev/null; ev "chaos|link|cut"
  demo_error "esuo-health-$run-before-kill"
  assert "ошибка до падения записана" "$STATUS" 204
  "$CHAOS/node-kill.sh" "$BRANCH" start >/dev/null; ev "chaos|node|kill"
  "$CHAOS/node-kill.sh" "$BRANCH" stop >/dev/null
  assert "узел поднялся после kill -9" "$(curl -s -o /dev/null -w '%{http_code}' "$NODE/actuator/health")" 200
  demo_error "esuo-health-$run-after-kill"
  assert "ошибка после подъёма записана" "$STATUS" 204
  sleep 5

  say "Связь восстановлена: логи до и после падения доезжают, правило отмечает перезапуск"
  local restored found
  restored=$(python3 -c 'import time; print(time.time_ns())')
  # shellcheck disable=SC2086
  $CUT restore >/dev/null; ev "chaos|link|restore"
  for m in before-kill:до_падения after-kill:после_подъёма; do
    local key=${m%%:*} label
    label=$(echo "${m#*:}" | tr _ ' ')
    found=""
    for i in $(seq 1 90); do found=$(loki_ts "esuo-health-${run}-${key}"); [ -n "$found" ] && break; sleep 1; done
    assert "ошибка ${label} доехала в Loki" "$([ -n "$found" ] && echo yes || echo no)" yes
    [ -n "$found" ] && assert "ошибка ${label} хранит время записи, а не доставки" "$([ "$found" -lt "$restored" ] && echo yes || echo no)" yes
  done
  wait_card 90 '.online and ((.alerts // []) | any(.code == "sys.restart"))'
  alert=$(alert_of "$CARD" sys.restart)
  assert "в обзоре сети правило «перезапуск»" "$([ -n "$alert" ] && echo yes || echo no)" yes
  [ -n "$alert" ] && fact "правило" "$alert"
  ev "health|after-kill"
  watch_ "Grafana → Explore → Loki: {branch=\"$BRANCH\"} |= \"esuo-health-$run\""

  say "Всё возвращено: процессор, база, связь"
  restore_all; trap - INT TERM; trap -- "$prev_exit" EXIT
  assert "у узла снова все ядра" "$(docker inspect -f '{{.HostConfig.NanoCpus}}' "esuo-node-$BRANCH")" "$(( $(docker info --format '{{.NCPU}}') * 1000000000 ))"
  assert "блокирующей сессии в базе нет" "$(docker exec "esuo-postgres-$BRANCH" sh -c \
    "psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -Atc \"SELECT count(*) FROM pg_stat_activity WHERE application_name = 'esuo-chaos-lock'\"")" 0
  wait_card 60 '.online'
  assert "отделение на связи" "$(echo "$CARD" | jq -r '.online')" true
  ev "health|restored"
}
