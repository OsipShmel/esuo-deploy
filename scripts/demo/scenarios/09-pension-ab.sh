#!/usr/bin/env bash
# shellcheck disable=SC2034
CODE=pension-ab
TITLE="Прогноз против его отсутствия: пенсионный день на двух отделениях"
DESC="b01 без модели и истории (константа 300 с), b02 с историей и моделью. Одна трасса клиентов, смена по прогнозу своего отделения, запись из МП на 10:00 и 15:00."
NODES="$NODE $NODE2"
scenario() {
  if [ "${AB_SKIP_PREPARE:-0}" != 1 ]; then
    say "Готовим стенд: $BRANCH без прогноза, $BRANCH2 с историей и моделью"
    NODE=$NODE NODE2=$NODE2 CENTER=$CENTER BRANCH=$BRANCH BRANCH2=$BRANCH2 "$ROOT/scripts/demo/ab-prepare.sh" \
      || { FAIL=$((FAIL+1)); echo "FAIL подготовка стенда" >> "$CHECKS"; return 1; }
  fi
  command -v python3 >/dev/null || { warn "Нужен python3"; FAIL=$((FAIL+1)); return 1; }
  # Проигрыватель пишет ход событий, проверки и факты в файлы прогона: подпись и runs.md собирает finish.
  NODE=$NODE NODE2=$NODE2 CENTER=$CENTER MP=$MP BRANCH=$BRANCH BRANCH2=$BRANCH2 PACE=$PACE FAST=$FAST \
    python3 "$ROOT/scripts/demo/ab_player.py" --rate "${CLOCK_RATE:-60}" \
      --events "$EVENTS" --checks "$CHECKS" --facts "$FACTS"
  PASS=$(grep -c '^PASS' "$CHECKS" || true); FAIL=$(grep -c '^FAIL' "$CHECKS" || true)
}
