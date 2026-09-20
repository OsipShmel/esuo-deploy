import { useMemo, useState } from 'react';
import { formatTime } from '@shared/format/time';
import type { LogEntry, LogKind } from './decisions';

const FILTERS: [LogKind | 'all', string][] = [['all', 'Все'], ['calls', 'Вызовы'], ['queue', 'Очередь'], ['system', 'Система']];

/** Журнал решений системы с причинами. Строку можно раскрыть: полный конверт события и кандидаты с дедлайнами и прогнозами. */
export function DecisionLog({ entries, paused, onPause, tz }: { entries: LogEntry[]; paused: boolean; onPause: (p: boolean) => void; tz: string }) {
  const [filter, setFilter] = useState<LogKind | 'all'>('all');
  const [open, setOpen] = useState<string | null>(null);
  const shown = useMemo(() => entries.filter((e) => filter === 'all' || e.kind === filter), [entries, filter]);

  return (
    <section className="log card" aria-label="Журнал решений">
      <div className="log-head">
        <h2>Журнал решений</h2>
        <span className="grow" />
        <div className="subtabs" role="group" aria-label="Фильтр">
          {FILTERS.map(([k, l]) => <button key={k} type="button" className="btn btn-sm" aria-pressed={filter === k} onClick={() => setFilter(k)}>{l}</button>)}
        </div>
        <button type="button" className="btn btn-sm" aria-pressed={paused} onClick={() => onPause(!paused)}>{paused ? 'Продолжить' : 'Пауза'}</button>
      </div>
      {shown.length === 0 ? <p className="muted">Решения появятся здесь, когда система начнёт вызывать клиентов.</p> : (
        <ol className="log-list">
          {shown.map((e) => (
            <li key={e.id} className={`log-item ${e.kind}`}>
              <button type="button" className="log-row" aria-expanded={open === e.id} onClick={() => setOpen(open === e.id ? null : e.id)}>
                <time>{formatTime(e.at, tz)}</time><span>{e.text}</span>
              </button>
              {open === e.id && (
                <div className="log-detail">
                  {e.candidates.length > 0 && (
                    <table>
                      <thead><tr><th>Кандидат</th><th>Дедлайн</th><th>Прогноз</th><th>Оценка</th></tr></thead>
                      <tbody>{e.candidates.map((c, i) => (
                        <tr key={i}><td>{c.display_number ?? '—'}</td><td>{c.due_at ? formatTime(c.due_at, tz) : '—'}</td>
                          <td>{c.predicted_duration_sec !== undefined ? `${Math.round(c.predicted_duration_sec)} с` : '—'}</td>
                          <td>{c.score !== undefined ? c.score.toPrecision(3) : '—'}</td></tr>
                      ))}</tbody>
                    </table>
                  )}
                  <pre>{JSON.stringify(e.envelope, null, 2)}</pre>
                </div>
              )}
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}
