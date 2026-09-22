#!/usr/bin/env bash
# shellcheck disable=SC2034
CODE=analytics
TITLE="Аналитика руководителя на истории"
DESC="Наливаем историю за две недели (детерминированно, SEED) — сводка, отклонения и загрузка окон сразу непустые."
NODES="$NODE"
scenario() {
  local sql="$WORK/history.sql" days=${DAYS:-14}
  command -v java >/dev/null || { warn "Нужна Java 25 (запуск tools/load-generator)"; return 1; }
  command -v docker >/dev/null || { warn "Нужен docker (заливка в PostgreSQL отделения)"; return 1; }

  say "Генерируем историю: $days дней, 60 талонов в день (SEED=${SEED:-42})"
  java "$ROOT/tools/load-generator/LoadGenerator.java" history --days "$days" --per-day 60 --seed "${SEED:-42}" --out "$sql" >/dev/null 2>&1
  assert "файл истории создан" "$([ -s "$sql" ] && echo yes || echo no)" yes
  say "Заливаем историю в базу отделения $BRANCH"
  docker exec -i "${PG_CONTAINER:-esuo-postgres-b01}" psql -U "${DB_USER:-esuo}" -d esuo -q < "$sql" >/dev/null 2>&1
  assert "история залита" "$?" 0
  ev "history|$days|60|${SEED:-42}"

  say "Руководитель открывает «Аналитику»"
  local from to; to=$(date +%F); from=$(date -v-"$days"d +%F 2>/dev/null || date -d "-$days day" +%F)
  adm "$NODE" GET "/api/v1/admin/analytics/summary?from=$from&to=$to"
  check "сводка за период непустая" 200 '.totals.issued > 0 and (.by_service|length)>0'
  fact "выдано талонов" "$(jget .totals.issued)"; fact "среднее ожидание, с" "$(jget '.totals.avg_wait_sec')"
  adm "$NODE" GET "/api/v1/admin/analytics/windows?from=$from&to=$to"
  check "загрузка окон посчитана" 200 'length>0 and .[0].utilization!=null'
  adm "$NODE" GET "/api/v1/admin/analytics/deviations?from=$from&to=$to"
  check "отклонения найдены (просрочки, медленные окна…)" 200 '(.counts|to_entries|map(.value)|add) > 0'
  fact "отклонения по типам" "$(echo "$BODY" | jq -c '.counts')"
  watch_ ":8080/admin → «Аналитика» с периодом $from — $to; «Отклонения» и «Загрузка окон»"
}
