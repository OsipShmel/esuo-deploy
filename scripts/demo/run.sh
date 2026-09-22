#!/usr/bin/env bash
# Демо-сценарии для жюри (это не тесты): рабочий день, три источника, МП, спорные ситуации, обрыв связи, аналитика.
#   ./scripts/demo/run.sh doctor                    — проверка стенда перед записью (ничего не меняет)
#   ./scripts/demo/run.sh normal                    — вернуть стенд в обычный режим после pension-ab
#   ./scripts/demo/run.sh list                      — список сценариев
#   ./scripts/demo/run.sh normal-day                — запустить один сценарий (смотрите табло/оператора/сеть на экране)
#   ./scripts/demo/run.sh normal-day --fast         — без пауз (для проверки повторяемости)
#   ./scripts/demo/run.sh pension-ab --rate 120     — скорость демо-часов (×60 по умолчанию; для записи видео — любая)
#   ./scripts/demo/run.sh pension-ab --from 09:00 --to 11:00 — отрезок дня (только pension-ab)
#   ./scripts/demo/run.sh repeat normal-day [3] [--paced] — N прогонов подряд, сравнение хода событий (--paced: с паузами)
#   ./scripts/demo/run.sh all [--with-chaos]        — все сценарии подряд (chaos = обрыв связи и отказ уведомлений, долго)
# Настройки (окружение): NODE, NODE2, CENTER, MP, PACE (пауза, с), SEED (зерно потока), CLOCK_RATE (скорость демо-часов),
#   AB_START/AB_END (отрезок дня pension-ab), AB_SKIP_PREPARE=1 (не готовить стенд заново), DEMO_*_PASSWORD (или .env).
# Результаты: docs/demo/jury/runs.md и docs/demo/jury/runs/*.json — отдельно от результатов тестов (docs/demo/results.md).
set -o pipefail   # без -u: в bash 3.2 (macOS) пустые "${@}" считаются неопределёнными
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DIR="$ROOT/scripts/demo"
command -v jq >/dev/null || { echo "Нужен jq (mac: brew install jq)"; exit 2; }

meta() { grep -m1 "^$2=" "$1" | sed -E "s/^$2=\"?([^\"]*)\"?.*/\1/"; }
scenario_file() { local f; for f in "$DIR"/scenarios/[0-9]*.sh; do [ "$(meta "$f" CODE)" = "$1" ] && { echo "$f"; return 0; }; done; return 1; }

list() {
  local f
  printf 'Сценарии демо (запуск: ./scripts/demo/run.sh КОД):\n\n'
  for f in "$DIR"/scenarios/[0-9]*.sh; do printf '  %-14s %s\n' "$(meta "$f" CODE)" "$(meta "$f" TITLE)"; printf '  %-14s %s\n' "" "$(meta "$f" DESC)"; done
}

run_one() { # выполняется в этом процессе: библиотека создаёт рабочую папку и ловушку очистки
  local code=$1 file; file=$(scenario_file "$code") || { echo "Нет сценария «${code}». ./scripts/demo/run.sh list"; exit 2; }
  # shellcheck source=lib.sh
  . "$DIR/lib.sh"
  # shellcheck source=scenarios/_day.sh
  . "$DIR/scenarios/_day.sh"
  # shellcheck disable=SC1090
  . "$file"
  STARTED=$(date -u +%FT%TZ)
  trap 'demo_clock_restore; rm -rf "$WORK"' EXIT     # демо-часы возвращаем и при Ctrl+C
  printf '%s%s%s\n%s\n' "$C_BOLD" "== $TITLE ==" "$C_RESET" "$DESC"
  # shellcheck disable=SC2086
  preflight $NODES
  scenario
  demo_clock_restore
  finish "$CODE" "$TITLE"
}

case "${1:-list}" in
  list|-h|--help|help) list ;;
  doctor) exec "$DIR/doctor.sh" ;;                          # проверка стенда перед записью
  normal) exec "$DIR/ab-prepare.sh" --undo ;;               # вернуть стенд в обычный режим после pension-ab
  repeat)
    code=${2:?Укажите сценарий: run.sh repeat КОД [N] [--paced]}; n=${3:-3}; sigs=""; okall=1; i=1
    mode="--fast"; [ "${4:-}" = "--paced" ] && mode=""       # --paced: с паузами, как на показе (подпись сравнивается только внутри одного режима)
    while [ "$i" -le "$n" ]; do
      printf '\n%s######## прогон %s из %s ########%s\n' $'\033[1m' "$i" "$n" $'\033[0m'
      sigfile=$(mktemp)
      if [ -n "$mode" ]; then FAST=1 SIG_OUT="$sigfile" "$0" "$code" --fast || okall=0; else FAST=0 SIG_OUT="$sigfile" "$0" "$code" || okall=0; fi
      sigs="$sigs $(cat "$sigfile")"; rm -f "$sigfile"; i=$((i+1))
    done
    uniq_n=$(echo "$sigs" | tr ' ' '\n' | grep -v '^$' | sort -u | wc -l | tr -d ' ')
    rep="$ROOT/docs/demo/jury/repeatability.md"
    [ -n "$mode" ] && modename="быстрый режим" || modename="режим показа (с паузами)"
    if [ "$uniq_n" = 1 ]; then verdict="ход событий одинаков во всех $n прогонах ($modename)"; else verdict="ход событий РАСХОДИТСЯ (${uniq_n} разных подписей, $modename)"; fi
    printf '\n%s\n' "Повторяемость «${code}»: $verdict (подписи:$sigs)"
    mkdir -p "$ROOT/docs/demo/jury"
    [ -f "$rep" ] || printf '# Повторяемость демо-сценариев\n\nТри прогона подряд дают один и тот же ход событий (одинаковая подпись). Создаётся `run.sh repeat`.\n\n| Время (UTC) | Коммит | Сценарий | Прогонов | Итог | Подписи |\n|---|---|---|---|---|---|\n' > "$rep"
    printf '| %s | %s | %s | %s | %s | `%s` |\n' "$(date -u '+%F %H:%M')" "$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo -)" "$code" "$n" "$verdict" "$(echo $sigs)" >> "$rep"
    [ "$uniq_n" = 1 ] && [ "$okall" = 1 ] ;;
  all)
    rc=0; set_="normal-day three-sources mp disputes analytics pension-day pension-ab"
    [ "${2:-}" = "--with-chaos" ] && set_="normal-day three-sources mp disputes link-cut notify-down analytics pension-day pension-ab"
    for code in $set_; do "$0" "$code" || rc=1; done
    exit "$rc" ;;
  *)
    code=$1; shift
    while [ $# -gt 0 ]; do
      case "$1" in
        --fast) export FAST=1 ;;
        --pace) export PACE=$2; shift ;;
        --seed) export SEED=$2; shift ;;
        --rate) export CLOCK_RATE=$2; shift ;;
        --from) export AB_START=$2; shift ;;
        --to)   export AB_END=$2; shift ;;
        *) echo "Неизвестный параметр $1"; exit 2 ;;
      esac; shift
    done
    run_one "$code" ;;
esac