import { useState } from 'react';

/** Сценарии для жюри запускаются скриптами `scripts/demo` (не тестами); пульт показывает их и готовые команды. */
const SCENARIOS: { code: string; title: string; what: string; watch: string }[] = [
  { code: 'normal-day', title: 'Обычный рабочий день', what: 'Поток клиентов, три окна на двух отделениях', watch: 'табло, оператор, обзор сети' },
  { code: 'three-sources', title: 'Три источника талонов', what: 'Запись через сайт, QR, живая очередь — одна очередь', watch: 'оператор, страница клиента' },
  { code: 'mp', title: 'Запись из мобильного приложения', what: 'Подписанный вебхук → талон → ответ в МП', watch: 'центр: уведомления и запросы МП' },
  { code: 'disputes', title: 'Спорные ситуации', what: 'Закрытие окна, перенаправление, неявка, возврат в хвост', watch: 'табло, оператор' },
  { code: 'link-cut', title: 'Обрыв связи с центром', what: 'Отделение работает автономно, события докачиваются', watch: 'обзор сети, табло' },
  { code: 'notify-down', title: 'Отказ уведомлений', what: 'Письма уходят в DLQ, затем повтор', watch: 'центр: уведомления' },
  { code: 'analytics', title: 'Аналитика на истории', what: 'История за две недели — отчёты сразу непустые', watch: 'админка → аналитика' },
  { code: 'pension-day', title: 'Пенсионный день', what: 'Пик потока, просрочки, рекомендация открыть окно', watch: 'админка, оператор' },
];

export function ScenarioHelp() {
  const [copied, setCopied] = useState<string | null>(null);
  const copy = async (text: string, code: string) => {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(code);
      window.setTimeout(() => setCopied((c) => (c === code ? null : c)), 1500);
    } catch {
      setCopied(null);                              // по HTTP буфер обмена недоступен: команду можно выделить и скопировать вручную
    }
  };
  return (
    <details className="scenarios">
      <summary className="btn btn-sm">Сценарии для показа</summary>
      <div className="scenarios-body card">
        <p className="muted small">
          Сценарии запускаются кнопкой на вкладке «Сценарии» (если на стенде поднят demo-runner) или в терминале и идут по
          шагам, а на экране пульта всё видно. Результаты и подпись хода событий
          пишутся отдельно от тестов: <code>docs/demo/jury/runs.md</code>. Повторяемость: <code>repeat КОД 3</code>.
        </p>
        <ul className="scenario-list">
          {SCENARIOS.map((s) => {
            const cmd = `./scripts/demo/run.sh ${s.code}`;
            return (
              <li key={s.code}>
                <div><strong>{s.title}</strong><div className="muted small">{s.what} · смотрите: {s.watch}</div></div>
                <code className="cmd" tabIndex={0}>{cmd}</code>
                <button type="button" className="btn btn-sm" onClick={() => void copy(cmd, s.code)}>{copied === s.code ? 'Скопировано' : 'Копировать'}</button>
              </li>
            );
          })}
        </ul>
        <p className="muted small">Все подряд: <code>./scripts/demo/run.sh all</code> (с обрывом связи и отказом уведомлений — <code>all --with-chaos</code>).</p>
      </div>
    </details>
  );
}
