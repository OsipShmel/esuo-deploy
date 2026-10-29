#!/usr/bin/env bash
# Общая библиотека демо-сценариев (не тесты): рассказ по шагам, повторяемый ход событий, отдельные результаты.
# Подключается из run.sh; совместима с bash 3.2 (macOS): без ассоциативных массивов.
# shellcheck disable=SC2034,SC2154

NODE=${NODE:-http://localhost:8080}                 # отделение b01
NODE2=${NODE2:-http://localhost:8090}               # отделение b02
CENTER=${CENTER:-http://localhost:8081}
MP=${MP:-http://localhost:8096}                     # mp-simulator
NOTIFY=${NOTIFY:-http://localhost:8095}
BRANCH=${BRANCH:-b01}
BRANCH2=${BRANCH2:-b02}
PACE=${PACE:-1.2}                                   # секунд между заметными шагами; FAST=1 — без пауз
FAST=${FAST:-0}

S_PARCEL=00000000-0000-4000-8000-000000000001
S_SEND=00000000-0000-4000-8000-000000000004
S_FIN=00000000-0000-4000-8000-000000000005
W1=00000000-0000-4000-8000-000000000003
W2=00000000-0000-4000-8000-000000000007
ALL_SERVICES="\"$S_PARCEL\",\"$S_SEND\",\"$S_FIN\""

[ -f "$ROOT/.env" ] && { set -a; . "$ROOT/.env"; set +a; }
OP_PASS=${DEMO_OPERATOR_PASSWORD:-operator123}
ADMIN_PASS=${DEMO_ADMIN_PASSWORD:-admin123}
J=(-H 'Content-Type: application/json')
. "$ROOT/scripts/center-auth.sh"                    # закрытый API центра: CENTER_TOKEN, center_curl

# ------------------------------------------------------------------ вывод

C_RESET=$'\033[0m'; C_BOLD=$'\033[1m'; C_DIM=$'\033[2m'; C_GREEN=$'\033[32m'; C_RED=$'\033[31m'; C_CYAN=$'\033[36m'; C_YELLOW=$'\033[33m'
STEP_NO=0
say()   { STEP_NO=$((STEP_NO+1)); printf '\n%s%s. %s%s\n' "$C_BOLD$C_CYAN" "$STEP_NO" "$*" "$C_RESET"; }
watch_() { printf '   %sСмотрите: %s%s\n' "$C_DIM" "$*" "$C_RESET"; }
note()  { printf '   %s%s%s\n' "$C_DIM" "$*" "$C_RESET"; }
warn()  { printf '   %s%s%s\n' "$C_YELLOW" "$*" "$C_RESET"; }
pause() { [ "$FAST" = 1 ] && return 0; sleep "$(awk -v p="$PACE" -v k="${1:-1}" 'BEGIN{print p*k}')"; }

# ------------------------------------------------------------------ рабочая папка прогона

WORK=$(mktemp -d "${TMPDIR:-/tmp}/esuo-demo.XXXXXX")
EVENTS="$WORK/events.log"; CHECKS="$WORK/checks.log"; FACTS="$WORK/facts.log"; LABELS="$WORK/labels.tsv"
: > "$EVENTS"; : > "$CHECKS"; : > "$FACTS"; : > "$LABELS"
RUN=$(date +%s)
PASS=0; FAIL=0
trap 'rm -rf "$WORK"' EXIT

# ev — событие, входящее в «ход событий»: по нему сравниваются три прогона (без номеров талонов и времени)
ev()   { echo "$*" >> "$EVENTS"; }
fact() { echo "$1=$2" >> "$FACTS"; note "· $1: $2"; }
check() { # check ОПИСАНИЕ КОД-ОЖИДАЕМЫЙ [jq-условие по $BODY]
  local name=$1 want=$2 cond=${3:-true}
  if [ "$STATUS" = "$want" ] && echo "$BODY" | jq -e "$cond" >/dev/null 2>&1; then
    PASS=$((PASS+1)); printf '   %s✔ %s%s\n' "$C_GREEN" "$name" "$C_RESET"; echo "PASS $name" >> "$CHECKS"
  else
    FAIL=$((FAIL+1)); printf '   %s✘ %s — ждали %s, пришло %s: %s%s\n' "$C_RED" "$name" "$want" "$STATUS" "$(echo "$BODY" | head -c 200)" "$C_RESET"
    echo "FAIL $name (статус $STATUS)" >> "$CHECKS"
  fi
}
assert() { # assert ОПИСАНИЕ ЗНАЧЕНИЕ ОЖИДАЕМОЕ
  if [ "$2" = "$3" ]; then PASS=$((PASS+1)); printf '   %s✔ %s%s\n' "$C_GREEN" "$1" "$C_RESET"; echo "PASS $1" >> "$CHECKS"
  else FAIL=$((FAIL+1)); printf '   %s✘ %s — ждали «%s», получили «%s»%s\n' "$C_RED" "$1" "$3" "$2" "$C_RESET"; echo "FAIL $1 (ждали $3, получили $2)" >> "$CHECKS"; fi
}

# ------------------------------------------------------------------ HTTP

http() { # http МЕТОД URL [curl-аргументы...] → $STATUS $BODY
  BODY=$(curl -s -X "$1" "$2" -w $'\n%{http_code}' "${@:3}")
  STATUS=${BODY##*$'\n'}; BODY=${BODY%$'\n'*}
}
jget() { echo "$BODY" | jq -r "$1"; }

token() { # token НОДА ЛОГИН ПАРОЛЬ → JWT (кеш в рабочей папке)
  local f="$WORK/tok.$(printf '%s%s' "$1" "$2" | shasum | cut -c1-10)"
  [ -s "$f" ] || curl -s -X POST "$1/api/v1/auth/login" "${J[@]}" -d "$(jq -nc --arg l "$2" --arg p "$3" '{login:$l,password:$p}')" | jq -r '.access_token // empty' > "$f"
  cat "$f"
}
op()  { local node=$1 m=$2 p=$3; shift 3; http "$m" "$node$p" -H "Authorization: Bearer $(token "$node" operator "$OP_PASS")" "${J[@]}" "$@"; }
adm() { local node=$1 m=$2 p=$3; shift 3; http "$m" "$node$p" -H "Authorization: Bearer $(token "$node" admin "$ADMIN_PASS")" "${J[@]}" "$@"; }
center() { local m=$1 p=$2; shift 2; [ -n "${CENTER_TOKEN:-}" ] || center_login || true  # center МЕТОД ПУТЬ — закрытый API центра
  http "$m" "$CENTER$p" -H "Authorization: Bearer ${CENTER_TOKEN:-}" "${J[@]}" "$@"; }

preflight() { # preflight НОДА... — узлы отвечают, вход работает
  local n
  for n in "$@"; do
    http GET "$n/actuator/health"
    [ "$STATUS" = 200 ] || { echo "Узел $n не отвечает (HTTP $STATUS). Запущен ли стенд? docker compose up -d --build"; exit 2; }
    [ -n "$(token "$n" operator "$OP_PASS")" ] || { echo "Не удалось войти оператором на $n: проверьте DEMO_OPERATOR_PASSWORD в .env"; exit 2; }
    [ -n "$(token "$n" admin "$ADMIN_PASS")" ] || { echo "Не удалось войти руководителем на $n: проверьте DEMO_ADMIN_PASSWORD в .env"; exit 2; }
  done
}

# ------------------------------------------------------------------ талоны и метки

label_set() { printf '%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "$4" >> "$LABELS"; }        # метка, узел, ticket_id, token
label_col() { awk -F'\t' -v l="$1" -v c="$2" '$1==l {print $c; exit}' "$LABELS"; }
tid()   { label_col "$1" 3; }
ttok()  { label_col "$1" 4; }
tnode() { label_col "$1" 2; }
label_of() { awk -F'\t' -v i="$1" '$3==i {print $1; exit}' "$LABELS"; }

CAT_RU() { case "$1" in pensioner) echo "пенсионер";; *) echo "обычный";; esac; }
SVC_RU() { case "$1" in "$S_PARCEL") echo "посылка";; "$S_SEND") echo "отправка";; "$S_FIN") echo "платежи";; *) echo "услуга";; esac; }

arrive_qr() { # arrive_qr НОДА МЕТКА УСЛУГА КАТЕГОРИЯ [ЦЕНТР=0] — клиент отсканировал QR в зале (или через центр)
  local node=$1 label=$2 svc=$3 cat=${4:-default} via_center=${5:-0} branch
  branch=$BRANCH; [ "$node" = "$NODE2" ] && branch=$BRANCH2
  local body; body=$(jq -nc --arg q "demo:$branch" --arg s "$svc" --arg d "demo-$RUN-$label" --arg c "$cat" '{qr_code:$q,service_id:$s,device_id:$d,client_category:$c}')
  if [ "$via_center" = 1 ]; then http POST "$CENTER/api/v1/branches/$branch/qr-tickets" "${J[@]}" -H "Idempotency-Key: demo-$RUN-$label" -d "$body"
  else http POST "$node/api/v1/public/qr-tickets" "${J[@]}" -H "Idempotency-Key: demo-$RUN-$label" -d "$body"; fi
  if [ "$STATUS" = 201 ] || [ "$STATUS" = 200 ]; then
    label_set "$label" "$node" "$(jget .ticket_id)" "$(jget .token)"
    ev "arrive|$branch|$label|qr|$(SVC_RU "$svc")|$cat"
    printf '   %s%s%s  %s — %s (%s), талон %s\n' "$C_BOLD" "$label" "$C_RESET" "QR" "$(SVC_RU "$svc")" "$(CAT_RU "$cat")" "$(jget .display_number)"
  else
    FAIL=$((FAIL+1)); printf '   %s✘ талон %s не выдан: %s %s%s\n' "$C_RED" "$label" "$STATUS" "$(echo "$BODY" | head -c 160)" "$C_RESET"; echo "FAIL выдача $label" >> "$CHECKS"
  fi
}

arrive_walkin() { # arrive_walkin НОДА ОКНО МЕТКА УСЛУГА КАТЕГОРИЯ — оператор выдал талон живой очереди
  local node=$1 win=$2 label=$3 svc=$4 cat=${5:-default} branch
  branch=$BRANCH; [ "$node" = "$NODE2" ] && branch=$BRANCH2
  op "$node" POST "/api/v1/windows/$win/walkin" -H "Idempotency-Key: demo-$RUN-$label" -d "$(jq -nc --arg s "$svc" --arg c "$cat" '{service_id:$s,client_category:$c}')"
  if [ "$STATUS" = 201 ] || [ "$STATUS" = 200 ]; then
    label_set "$label" "$node" "$(jget .ticket_id)" "$(jget .token)"
    ev "arrive|$branch|$label|walkin|$(SVC_RU "$svc")|$cat"
    printf '   %s%s%s  %s — %s (%s), талон %s\n' "$C_BOLD" "$label" "$C_RESET" "у оператора" "$(SVC_RU "$svc")" "$(CAT_RU "$cat")" "$(jget .display_number)"
  else
    FAIL=$((FAIL+1)); printf '   %s✘ walk-in %s не выдан: %s %s%s\n' "$C_RED" "$label" "$STATUS" "$(echo "$BODY" | head -c 160)" "$C_RESET"; echo "FAIL выдача $label" >> "$CHECKS"
  fi
}

win_name() { case "$1" in "$W1") echo "Окно 1";; "$W2") echo "Окно 2";; *) echo "окно";; esac; }
node_branch() { [ "$1" = "$NODE2" ] && echo "$BRANCH2" || echo "$BRANCH"; }

open_window() { # open_window НОДА ОКНО УСЛУГИ-через-запятую-в-кавычках
  op "$1" POST "/api/v1/windows/$2/open" -d "{\"service_ids\":[$3]}"
  check "$(win_name "$2") ($(node_branch "$1")) открыто" 200 '.status=="open"'
  ev "window|$(node_branch "$1")|$(win_name "$2")|open"
}
close_window() { # close_window НОДА ОКНО РЕЖИМ
  op "$1" POST "/api/v1/windows/$2/close" -d "{\"mode\":\"$3\"}"
  ev "window|$(node_branch "$1")|$(win_name "$2")|close-$3"
}

call_next() { # call_next НОДА ОКНО → метка вызванного клиента в $CALLED (пусто, если очередь пуста)
  op "$1" POST "/api/v1/windows/$2/call-next"
  CALLED=""
  if [ "$STATUS" = 200 ]; then
    local id evl; id=$(jget .ticket_id); CALLED=$(label_of "$id"); evl=$CALLED
    if [ -z "$CALLED" ]; then CALLED="ext-${id:0:6}"; evl=ext; label_set "$CALLED" "$1" "$id" ""; fi   # клиент не из этого прогона
    ev "call|$(node_branch "$1")|$(win_name "$2")|$evl"
    printf '   %s%s вызывает %s%s (талон %s)\n' "$C_BOLD" "$(win_name "$2")" "$CALLED" "$C_RESET" "$(jget .display_number)"
  fi
}
why_called() { # why_called НОДА МЕТКА — «решение системы»: причина вызова из журнала талона
  op "$1" GET "/api/v1/tickets/$(tid "$2")/events"
  local r; r=$(echo "$BODY" | jq -r '[.[]|select(.event_type=="ticket.called")]|last|.data|"\(.reason // "?"); кандидатов: \(.candidates|length)"' 2>/dev/null)
  note "Почему выбран $2: $r"
}
tcmd() { # tcmd НОДА МЕТКА КОМАНДА [тело-json] — start/complete/recall/no-show/requeue/redirect
  local node=$1 label=$2 cmd=$3 body=${4:-}
  if [ -n "$body" ]; then op "$node" POST "/api/v1/tickets/$(tid "$label")/$cmd" -d "$body"
  else op "$node" POST "/api/v1/tickets/$(tid "$label")/$cmd"; fi
  ev "cmd|$(node_branch "$node")|$label|$cmd"
}
serve() { # serve НОДА ОКНО — вызвать следующего, начать, завершить (три заметных шага)
  call_next "$1" "$2"; [ -n "$CALLED" ] || return 1
  pause; tcmd "$1" "$CALLED" start; pause 0.7; tcmd "$1" "$CALLED" complete
  return 0
}
status_of() { http GET "$(tnode "$1")/api/v1/public/tickets/$(ttok "$1")"; jget .status; }

drain() { # drain НОДА — привести отделение в чистое состояние: закрыть окна, обслужить всё оставшееся
  local node=$1 i
  op "$node" POST "/api/v1/windows/$W1/close" -d '{"mode":"force"}'
  op "$node" POST "/api/v1/windows/$W2/close" -d '{"mode":"force"}'
  op "$node" POST "/api/v1/windows/$W1/open" -d "{\"service_ids\":[$ALL_SERVICES]}"
  for i in $(seq 1 300); do
    op "$node" POST "/api/v1/windows/$W1/call-next"; [ "$STATUS" = 200 ] || break
    local id; id=$(jget .ticket_id)
    op "$node" POST "/api/v1/tickets/$id/start"; op "$node" POST "/api/v1/tickets/$id/complete"
  done
  op "$node" POST "/api/v1/windows/$W1/close" -d '{"mode":"force"}'
}
reset_nodes() { local n; note "Очищаем отделения от прошлых прогонов (закрываем окна, обслуживаем остатки)…"; for n in "$@"; do drain "$n"; done; }

# псевдослучайная последовательность: одинаковая при одинаковом зерне, не зависит от версии bash.
# Функции пишут результат в глобальные переменные (R, PICK_SVC, PICK_CAT), а не в stdout: подоболочка $(...) теряла бы состояние.
RSEED=${SEED:-42}
rnd() { RSEED=$(( (RSEED * 1103515245 + 12345) & 2147483647 )); R=$(( (RSEED >> 8) % $1 )); }

pick_service() { # pick_service «parcel:45 send:35 finance:20» → PICK_SVC (id услуги)
  local mix=$1 total=0 acc=0 part name w
  for part in $mix; do total=$((total + ${part#*:})); done
  rnd "$total"
  for part in $mix; do
    name=${part%%:*}; w=${part#*:}; acc=$((acc + w))
    if [ "$R" -lt "$acc" ]; then case "$name" in parcel) PICK_SVC=$S_PARCEL;; send) PICK_SVC=$S_SEND;; finance) PICK_SVC=$S_FIN;; esac; return; fi
  done
}
pick_category() { rnd 100; if [ "$R" -lt "$1" ]; then PICK_CAT=pensioner; else PICK_CAT=default; fi; }   # $1 — доля пенсионеров, %

# ------------------------------------------------------------------ демо-часы (нужен DEMO_MODE=true на узлах)

CLOCK_CHANGED=0
demo_clock_on() { http GET "$NODE/api/v1/public/demo/clock"; [ "$(jget .enabled)" = true ]; }
demo_clock_set() { # demo_clock_set ЧЧ:ММ СКОРОСТЬ — «сегодня в ЧЧ:ММ по времени отделения» на узлах сценария; без DEMO_MODE ничего не делает
  demo_clock_on || return 1
  local tz iso n
  http GET "$NODE/api/v1/public/branch"; tz=$(jget '.timezone // "Europe/Moscow"')
  iso=$(python3 -c 'import sys,datetime,zoneinfo
tz=zoneinfo.ZoneInfo(sys.argv[1]); h,m=map(int,sys.argv[2].split(":"))
d=datetime.datetime.now(tz).replace(hour=h,minute=m,second=0,microsecond=0)
print(d.astimezone(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"))' "$tz" "$1" 2>/dev/null) || return 1
  [ -n "$iso" ] || return 1
  demo_clock_at "$iso" "$2" && note "Демо-часы: сегодня $1 по времени отделения, скорость ×$2 (1 секунда = $2 с)."
}
demo_clock_at() { # demo_clock_at ISO-UTC СКОРОСТЬ — поставить демо-часы на всех узлах сценария
  local n
  # shellcheck disable=SC2086
  for n in $NODES; do adm "$n" PUT /api/v1/admin/demo/clock -d "{\"now\":\"$1\",\"rate\":$2}"; done
  CLOCK_CHANGED=1
}
iso_minus() { # iso_minus ISO-UTC МИНУТЫ → момент на N минут раньше (для «подходит время слота»)
  python3 -c 'import sys,datetime
d=datetime.datetime.strptime(sys.argv[1],"%Y-%m-%dT%H:%M:%SZ")-datetime.timedelta(minutes=int(sys.argv[2]))
print(d.strftime("%Y-%m-%dT%H:%M:%SZ"))' "$1" "$2"
}
demo_clock_restore() { # вернуть реальное время: сценарий не должен оставлять узлы «в 10:05 ×60»
  [ "$CLOCK_CHANGED" = 1 ] || return 0
  local n
  # shellcheck disable=SC2086
  for n in $NODES; do adm "$n" PUT /api/v1/admin/demo/clock -d "{\"now\":\"$(date -u +%FT%TZ)\",\"rate\":1}" >/dev/null 2>&1; done
  CLOCK_CHANGED=0
}

pick_slot() { # pick_slot УСЛУГА → SLOT (начало), SLOT_TODAY (1 — слот сегодня, можно подтвердить приход; 0 — завтра)
  local svc=$1 tz d n idx
  http GET "$NODE/api/v1/public/branch"; tz=$(jget '.timezone // "Europe/Moscow"')
  d=$(TZ=$tz date +%F)
  http GET "$CENTER/api/v1/branches/$BRANCH/slots?service_id=$svc&date=$d"
  SLOT=$(echo "$BODY" | jq -r 'if type=="array" then (.[0].slot_start // empty) else empty end' 2>/dev/null); SLOT_TODAY=1
  if [ -z "$SLOT" ]; then
    d=$(TZ=$tz date -v+1d +%F 2>/dev/null || TZ=$tz date -d tomorrow +%F)
    http GET "$CENTER/api/v1/branches/$BRANCH/slots?service_id=$svc&date=$d"
    n=$(echo "$BODY" | jq 'if type=="array" then length else 0 end' 2>/dev/null || echo 0)
    if [ "${n:-0}" -gt 0 ]; then idx=$(( RUN % n )); SLOT=$(echo "$BODY" | jq -r ".[$idx].slot_start"); fi   # разные слоты в разных прогонах
    SLOT_TODAY=0
  fi
}
cancel_public() { # cancel_public ТОКЕН — отмена записи клиентом (освобождает слот)
  http POST "$NODE/api/v1/public/tickets/$1/cancel" "${J[@]}"
}

queue_len() { op "$1" GET "/api/v1/queue?status=waiting"; echo "$BODY" | jq 'length' 2>/dev/null || echo 0; }

# ------------------------------------------------------------------ результаты

git_commit() { git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo "нет-git"; }
signature() { shasum -a 256 "$EVENTS" | cut -c1-12; }

finish() { # finish КОД ЗАГОЛОВОК — итог, JSON прогона и строка в reports/demo/runs.md
  local code=$1 title=$2 sig ts out dir
  sig=$(signature); ts=$(date -u +%Y%m%dT%H%M%SZ)
  dir="$ROOT/reports/demo/runs"; mkdir -p "$dir"
  out="$dir/$code-$ts.json"
  jq -n --arg code "$code" --arg title "$title" --arg started "$STARTED" --arg finished "$(date -u +%FT%TZ)" --arg commit "$(git_commit)" \
        --arg sig "$sig" --argjson pass "$PASS" --argjson fail "$FAIL" \
        --slurpfile events <(jq -R -s 'split("\n")|map(select(length>0))' "$EVENTS") \
        --slurpfile checks <(jq -R -s 'split("\n")|map(select(length>0))' "$CHECKS") \
        --slurpfile facts <(jq -R -s 'split("\n")|map(select(length>0))|map(split("=")|{key:.[0],value:(.[1:]|join("="))})|from_entries' "$FACTS") \
        '{scenario:$code,title:$title,started:$started,finished:$finished,commit:$commit,pass:$pass,fail:$fail,signature:$sig,facts:$facts[0],checks:$checks[0],events:$events[0]}' > "$out"
  local runs="$ROOT/reports/demo/runs.md"
  [ -f "$runs" ] || printf '# Прогоны демо-сценариев\n\nСоздаётся скриптами `scripts/demo` (не тестами). Подпись — хеш хода событий: у повторных прогонов она совпадает.\n\n| Время (UTC) | Коммит | Сценарий | Проверки | Подпись хода событий | Файл |\n|---|---|---|---|---|---|\n' > "$runs"
  printf '| %s | %s | %s | %s/%s | `%s` | `runs/%s` |\n' "$(date -u '+%F %H:%M')" "$(git_commit)" "$code" "$PASS" "$((PASS+FAIL))" "$sig" "$(basename "$out")" >> "$runs"
  echo "$sig" > "${SIG_OUT:-/dev/null}"
  printf '\n%s== Итог: %s ==%s\n' "$C_BOLD" "$title" "$C_RESET"
  if [ "$FAIL" = 0 ]; then printf '%sПроверок пройдено: %s%s\n' "$C_GREEN" "$PASS" "$C_RESET"; else printf '%sПровалено: %s из %s%s\n' "$C_RED" "$FAIL" "$((PASS+FAIL))" "$C_RESET"; fi
  note "Подпись хода событий: $sig (одинаковая у повторных прогонов)"
  note "Файл прогона: ${out#"$ROOT"/}"
  [ "$FAIL" = 0 ]
}
