import { formatTime } from '@shared/format/time';
import { CATEGORY_LABEL, label } from '@shared/format/labels';
import { DeadlineHint, EmptyState, SourceBadge } from '@shared/ui';
import type { QueueItem, WindowView } from './api';

/** Очередь окна: ожидающие талоны услуг окна (адресные — только своего окна) по дедлайну; записи ждут check-in. */
export function QueuePanel({ window: w, queue, now, tz, busy, onCheckIn }: {
  window: WindowView; queue: QueueItem[]; now: number; tz: string; busy: boolean; onCheckIn: (t: QueueItem) => void;
}) {
  const mine = (t: QueueItem) => w.service_ids.includes(t.service_id) && (!t.target_window_id || t.target_window_id === w.id);
  const waiting = queue.filter((t) => t.status === 'waiting' && mine(t)).sort((a, b) => (a.due_at ?? '').localeCompare(b.due_at ?? ''));
  const booked = queue.filter((t) => t.status === 'booked' && w.service_ids.includes(t.service_id))
    .sort((a, b) => (a.slot_start ?? '').localeCompare(b.slot_start ?? ''));

  return (
    <div className="stack">
      <section className="card" aria-labelledby="qw">
        <h2 id="qw">Очередь окна · {waiting.length}</h2>
        {waiting.length === 0 ? <EmptyState title="Очередь пуста">Новые клиенты появятся здесь сразу.</EmptyState> : (
          <ol className="queue-list">
            {waiting.slice(0, 40).map((t) => (
              <li key={t.ticket_id} className={t.overdue ? 'overdue' : undefined}>
                <span className="num">{t.display_number}</span>
                <span>{t.service_name}</span>
                <DeadlineHint dueAt={t.due_at} now={now} />
                <span className="sub"><SourceBadge source={t.source} />{t.client_category !== 'default' && <span className="chip tone-muted">{label(CATEGORY_LABEL, t.client_category)}</span>}
                  {t.target_window_id && <span className="chip tone-accent">адресный талон</span>}</span>
              </li>
            ))}
          </ol>
        )}
        {waiting.length > 40 && <p className="muted small">Показаны первые 40 из {waiting.length}.</p>}
      </section>

      {booked.length > 0 && (
        <section className="card" aria-labelledby="qb">
          <h2 id="qb">Записи, ещё не пришли · {booked.length}</h2>
          <ol className="queue-list">
            {booked.slice(0, 20).map((t) => (
              <li key={t.ticket_id}>
                <span className="num">{t.display_number}</span>
                <span>{t.service_name} · на {formatTime(t.slot_start, tz)}</span>
                <button type="button" className="btn btn-sm" disabled={busy} onClick={() => onCheckIn(t)}>Пришёл</button>
              </li>
            ))}
          </ol>
        </section>
      )}
    </div>
  );
}
