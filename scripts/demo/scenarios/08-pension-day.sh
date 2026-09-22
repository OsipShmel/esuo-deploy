#!/usr/bin/env bash
# shellcheck disable=SC2034
CODE=pension-day
TITLE="Пенсионный день: пик и просрочки"
DESC="Поток в 1,8 раза плотнее, почти половина клиентов — пенсионеры (профиль pension из load-planning.md). Очередь растёт, появляются просрочки и рекомендация открыть окно."
NODES="$NODE $NODE2"
scenario() {
  reset_nodes "$NODE" "$NODE2"
  CLOCK_RATE=${CLOCK_RATE:-180}      # пик должен успеть «состариться»: 1 секунда = 3 минуты
  run_day 24 "parcel:35 send:25 finance:40" 45 6
  say "Итоги пенсионного дня"
  if [ "$FAST" = 1 ]; then
    note "Быстрый прогон без пауз: очередь не успевает «состариться», просрочки в пике не проверяются (в показе с паузами — проверяются)."
  elif [ "$CLOCK_CHANGED" = 1 ]; then
    assert "в пик были просроченные талоны (очередь «состарилась» на демо-часах)" "$([ "${PEAK_OVERDUE:-0}" -gt 0 ] && echo yes || echo no)" yes
    assert "система рекомендовала открыть окно" "${PEAK_RECOMMEND:-}" true
  else
    warn "Без демо-часов (DEMO_MODE=false) очередь не успевает «состариться»: просрочек в пике нет. Включите DEMO_MODE=true."
  fi
  adm "$NODE" GET /api/v1/admin/recommendations
  check "рекомендации руководителя отвечают" 200 'has("recommend_open_window")'
  note "Как система реагирует на перегрузку (уровень, закрытие записи, смена по прогнозу) — сценарий pension-ab."
  adm "$NODE" GET /api/v1/admin/analytics/deviations
  check "отклонения за день отвечают" 200 '.counts != null'
  fact "отклонения за день" "$(echo "$BODY" | jq -c '.counts')"
}
