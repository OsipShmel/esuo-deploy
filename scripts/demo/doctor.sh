#!/usr/bin/env bash
# Проверка стенда перед записью демо: инструменты, .env, центр, обе ноды, демо-часы, входы, моки, данные A/B.
#   ./scripts/demo/run.sh doctor      (или напрямую ./scripts/demo/doctor.sh)
# Ничего не меняет. Красная строка — то, что сорвёт запись; жёлтая — сорвёт один сценарий.
set -u
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
[ -f "$ROOT/.env" ] && { set -a; . "$ROOT/.env"; set +a; }
NODE=${NODE:-http://localhost:8080}; NODE2=${NODE2:-http://localhost:8090}; CENTER=${CENTER:-http://localhost:8081}
MP=${MP:-http://localhost:8096}; NOTIFY=${NOTIFY:-http://localhost:8095}
OP_PASS=${DEMO_OPERATOR_PASSWORD:-operator123}; ADMIN_PASS=${DEMO_ADMIN_PASSWORD:-admin123}
. "$ROOT/scripts/center-auth.sh"     # закрытый API центра

G=$'\033[32m'; R=$'\033[31m'; Y=$'\033[33m'; B=$'\033[1m'; D=$'\033[2m'; Z=$'\033[0m'
BAD=0; WARN=0
ok()   { printf '  %s✔%s %s\n' "$G" "$Z" "$*"; }
bad()  { printf '  %s✘ %s%s\n' "$R" "$*" "$Z"; BAD=$((BAD+1)); }
warn() { printf '  %s! %s%s\n' "$Y" "$*" "$Z"; WARN=$((WARN+1)); }
hdr()  { printf '\n%s%s%s\n' "$B" "$*" "$Z"; }
code() { curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$@"; }
login() { curl -s --max-time 5 -X POST "$1/api/v1/auth/login" -H 'Content-Type: application/json' \
           -d "{\"login\":\"$2\",\"password\":\"$3\"}" | jq -r '.access_token // empty' 2>/dev/null; }

hdr "Инструменты"
for t in curl jq python3 docker; do
  if command -v "$t" >/dev/null; then ok "$t"; else bad "нет $t (mac: brew install $t)"; fi
done
if command -v python3 >/dev/null && ! python3 -c 'import sys; sys.exit(sys.version_info < (3, 9))'; then
  bad "python3 старше 3.9 — проигрывателю pension-ab нужен zoneinfo"
fi
if command -v java >/dev/null; then
  jv=$(java -version 2>&1 | grep -m1 ' version ' | sed -E 's/.*version "(1\.)?([0-9]+).*/\2/')
  if [ "${jv:-0}" -ge 21 ] 2>/dev/null; then ok "java $jv"; else ok "java ${jv:-?}: analytics запустит генератор истории в docker (eclipse-temurin:25)"; fi
else ok "java нет: analytics запустит генератор истории в docker (eclipse-temurin:25)"; fi

hdr ".env"
if [ ! -f "$ROOT/.env" ]; then bad "нет .env — cp .env.example .env и заполните"
else
  [ "${DEMO_MODE:-false}" = true ] && ok "DEMO_MODE=true" \
    || bad "DEMO_MODE не true: без демо-часов сценарии не ускоряются, pension-ab не запустится (поправьте и docker compose up -d)"
  [ "${DEMO_DATA:-true}" = true ] && ok "DEMO_DATA=true" || bad "DEMO_DATA не true: нет демо-справочников и пользователей"
fi

hdr "Стенд"
[ "$(code "$CENTER/actuator/health")" = 200 ] && ok "центр $CENTER" || bad "центр $CENTER не отвечает — docker compose up -d center-app (логи: docker logs esuo-center-app)"
for n in "$NODE" "$NODE2"; do
  if [ "$(code "$n/actuator/health")" != 200 ]; then bad "нода $n не отвечает — docker compose ps"; continue; fi
  clock=$(curl -s --max-time 5 "$n/api/v1/public/demo/clock" | jq -r .enabled 2>/dev/null)
  [ "$clock" = true ] && ok "нода $n, демо-часы включены" || bad "нода $n: демо-часы выключены (DEMO_MODE на ноде false — пересоздайте контейнер)"
  [ -n "$(login "$n" operator "$OP_PASS")" ] && [ -n "$(login "$n" admin "$ADMIN_PASS")" ] \
    && ok "нода $n: вход оператором и руководителем" || bad "нода $n: не входит operator/admin — пароли в .env"
done
[ -n "$CENTER_TOKEN" ] && ok "центр: вход оператором сети ($CENTER_ADMIN_LOGIN)" \
  || bad "центр: не входит $CENTER_ADMIN_LOGIN — задайте CENTER_ADMIN_PASSWORD в .env (как в .env.example) и пересоздайте center-app"
online=$(center_curl GET /api/v1/network/overview --max-time 5 | jq '[.branches[]?|select(.online==true)]|length' 2>/dev/null)
[ "${online:-0}" -ge 2 ] && ok "центр видит оба отделения на связи" || bad "центр видит на связи ${online:-0} отделений из 2 — подождите heartbeat (~30 с); связь остановлена? ./scripts/chaos/cut-branch-link.sh b01 status"
[ "$(code "$MP/health")" = 200 ] && ok "mp-simulator $MP" || warn "mp-simulator не отвечает: не запустятся mp и запись в pension-ab"
[ "$(code "$NOTIFY/health")" = 200 ] && ok "notify-mock $NOTIFY" || warn "notify-mock не отвечает: не запустится notify-down"

if docker ps --format '{{.Names}}' 2>/dev/null | grep -qx esuo-loki; then
  hdr "Мониторинг (профиль monitoring)"
  LOKI=${LOKI:-http://localhost:3100}; GRAFANA=${GRAFANA:-http://localhost:3000}
  # /ready отвечает 503 с причиной, пока кольцо Loki не готово (старт, короткие паузы) — три попытки, причину показываем
  loki_ready=""; for i in 1 2 3; do loki_ready=$(curl -s --max-time 5 -w ' (HTTP %{http_code})' "$LOKI/ready"); case "$loki_ready" in *"HTTP 200"*) break;; esac; sleep 2; done
  case "$loki_ready" in *"HTTP 200"*) ok "Loki $LOKI";;
    *"HTTP 000"*) bad "Loki $LOKI не отвечает — docker compose --profile monitoring up -d loki";;
    *) bad "Loki $LOKI не готов: $loki_ready — docker logs esuo-loki";; esac
  [ "$(code "$GRAFANA/api/health")" = 200 ] && ok "Grafana $GRAFANA" || bad "Grafana $GRAFANA не отвечает — docker logs esuo-grafana"
  for d in esuo-branch esuo-network; do      # без анонимного входа (GRAFANA_ANONYMOUS=false) — 401: проверка не про это
    case "$(code "$GRAFANA/api/dashboards/uid/$d")" in 200) ok "дашборд $d";; 401) warn "дашборд $d: Grafana без анонимного просмотра, не проверить";;
      *) bad "нет дашборда $d — docker compose --profile monitoring up -d --force-recreate grafana";; esac
  done
  [ "$(docker exec esuo-postgres-center psql -U "${DB_USER:-esuo}" -d esuo_center -Atc "SELECT count(*) FROM pg_roles WHERE rolname='grafana_ro'" 2>/dev/null)" = 1 ] \
    && ok "роль grafana_ro для панелей метрик" || bad "нет роли grafana_ro — задайте GRAFANA_DB_PASSWORD и перезапустите center-app"
  for v in esuo-vector-b01 esuo-vector-b02; do
    [ "$(docker inspect -f '{{.State.Running}}' "$v" 2>/dev/null)" = true ] && ok "сборщик логов $v" \
      || bad "сборщик логов $v не запущен — docker compose --profile monitoring up -d"
  done
fi

hdr "Данные для pension-ab"
SC="$ROOT/infrastructure/demo/scenarios"
[ -s "$SC/history.sql" ] && ok "history.sql" || bad "нет history.sql — cd python-ai-service && uv run esuo-ml generate --end-day 2026-09-20"
[ -s "$SC/model.json" ] && ok "model.json ($(jq -r .model.version "$SC/model.json" 2>/dev/null))" || bad "нет model.json"
[ -s "$SC/scenario-pension.json" ] && ok "scenario-pension.json" || bad "нет scenario-pension.json"
for c in "${PG_B01:-esuo-postgres-b01}" "${PG_B02:-esuo-postgres-b02}"; do
  docker inspect "$c" >/dev/null 2>&1 && ok "контейнер $c" || bad "нет контейнера $c (другие имена — PG_B01/PG_B02)"
done

echo
if [ "$BAD" = 0 ]; then printf '%sМожно записывать.%s' "$B$G" "$Z"; [ "$WARN" = 0 ] || printf ' %s(%s предупреждений — см. жёлтые строки)%s' "$Y" "$WARN" "$Z"; echo
else printf '%sНе готово: %s проблем(ы) — красные строки выше.%s\n' "$B$R" "$BAD" "$Z"; fi
[ "$BAD" = 0 ]