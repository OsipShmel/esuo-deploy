#!/usr/bin/env bash
# shellcheck disable=SC2034
CODE=analytics
TITLE="Аналитика руководителя на истории"
DESC="Наливаем историю за две недели (детерминированно, SEED) — сводка, отклонения и загрузка окон сразу непустые."
NODES="$NODE"
scenario() {
  local sql="$WORK/history.sql" days=${DAYS:-14}
  command -v docker >/dev/null || { warn "Нужен docker (заливка в PostgreSQL отделения)"; return 1; }
  # load-generator — однофайловая программа на Java 21+ (виртуальные потоки). Если локальная Java старше,
  # запускаем тот же файл в контейнере с Java 25: ставить ничего не нужно, образ скачается один раз.
  local jv=0
  command -v java >/dev/null && jv=$(java -version 2>&1 | grep -m1 ' version ' | sed -E 's/.*version "(1\.)?([0-9]+).*/\2/')
  gen() {
    if [ "${jv:-0}" -ge 21 ] 2>/dev/null; then java "$ROOT/tools/load-generator/LoadGenerator.java" "$@" --out "$sql"
    else docker run --rm -v "$ROOT/tools/load-generator:/src:ro" -v "$WORK:/out" eclipse-temurin:25-jdk \
           java /src/LoadGenerator.java "$@" --out /out/history.sql
    fi
  }
  if ! [ "${jv:-0}" -ge 21 ] 2>/dev/null; then
    note "Локальная Java ${jv:-нет} старше 21 — генератор запускается в docker (eclipse-temurin:25)."
    # Образ качаем заранее и с прогрессом: иначе первый запуск минуты молча висит на скачивании.
    docker image inspect eclipse-temurin:25-jdk >/dev/null 2>&1 || { note "Скачиваю образ один раз (~250 МБ)…"; docker pull eclipse-temurin:25-jdk || return 1; }
  fi

  say "Генерируем историю: $days дней, 60 талонов в день (SEED=${SEED:-42})"
  gen history --days "$days" --per-day 60 --seed "${SEED:-42}" >"$WORK/gen.log" 2>&1
  assert "файл истории создан" "$([ -s "$sql" ] && echo yes || echo no)" yes
  # Причину показываем, а не глотаем: чаще всего в PATH не та Java (нужна 25, `java -version`).
  [ -s "$sql" ] || { warn "load-generator не отработал:"; tail -5 "$WORK/gen.log" | sed 's/^/     /'; return 1; }
  say "Заливаем историю в базу отделения $BRANCH"
  docker exec -i "${PG_CONTAINER:-esuo-postgres-b01}" psql -U "${DB_USER:-esuo}" -d esuo -q < "$sql" >"$WORK/psql.log" 2>&1
  local rc=$?
  assert "история залита" "$rc" 0
  [ "$rc" = 0 ] || { warn "psql:"; tail -5 "$WORK/psql.log" | sed 's/^/     /'; }
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