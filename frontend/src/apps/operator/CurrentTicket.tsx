import { useEffect, useState } from 'react';
import { formatSeconds } from '@shared/format/duration';
import { formatTime } from '@shared/format/time';
import { CATEGORY_LABEL, label } from '@shared/format/labels';
import { SourceBadge, StatusChip } from '@shared/ui';
import type { QueueItem, WindowView } from './api';

export interface TicketActions {
  busy: boolean;
  start: () => void;
  complete: () => void;
  recall: () => void;
  noShow: () => void;
  requeue: () => void;
  redirect: () => void;
  next: () => void;
}

/** Карточка текущего клиента и действия над ним (концепция 4.3). Главное действие — самая крупная кнопка. */
export function CurrentTicket({ window: w, item, actions, tz }: { window: WindowView; item: QueueItem | undefined; actions: TicketActions; tz: string }) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, []);

  const t = w.active_ticket;

  if (!t) {
    return (
      <section className="card current" aria-live="polite">
        <div>
          <h2>Клиента нет</h2>
          <p className="muted">
            {w.status === 'open' ? 'Окно свободно. Вызовите следующего клиента.'
              : w.status === 'closing' ? 'Окно закрывается.'
              : 'Окно закрыто. Откройте его, чтобы вызывать клиентов.'}
          </p>
        </div>
        <div className="actions">
          <button type="button" className="btn btn-primary btn-xl" disabled={w.status !== 'open' || actions.busy} onClick={actions.next}>
            Следующий<kbd>Пробел</kbd>
          </button>
        </div>
      </section>
    );
  }

  const called = t.status === 'called';
  const waitedSec = item ? (now - new Date(item.eligible_from ?? item.issued_at).getTime()) / 1000 : null;
  const sinceCallSec = t.called_at ? (now - new Date(t.called_at).getTime()) / 1000 : null;

  return (
    <section className={`card current ${t.status}`} aria-live="polite">
      <div className="row between">
        <div className="number">{t.display_number}</div>
        <StatusChip status={t.status} text={called ? 'Вызван — ждём клиента' : 'Обслуживается'} />
      </div>
      <div className="meta">
        <strong>{t.service.name}</strong>
        <SourceBadge source={t.source} />
        <span className="chip tone-muted">{label(CATEGORY_LABEL, t.client_category)}</span>
      </div>
      <dl className="facts" style={{ margin: 0 }}>
        <div><dt>Вызван в</dt><dd>{formatTime(t.called_at, tz)}</dd></div>
        <div><dt>{called ? 'Ждём у окна' : 'Идёт обслуживание'}</dt><dd>{sinceCallSec === null ? '—' : formatSeconds(sinceCallSec)}</dd></div>
        <div><dt>Ждал до вызова</dt><dd>{item && item.called_at && waitedSec !== null ? formatSeconds((new Date(item.called_at).getTime() - new Date(item.eligible_from ?? item.issued_at).getTime()) / 1000) : '—'}</dd></div>
        {called && item && <div><dt>Вызовов</dt><dd>{item.call_count}</dd></div>}
      </dl>
      <div className="actions">
        {called ? (
          <>
            <button type="button" className="btn btn-primary btn-xl" disabled={actions.busy} onClick={actions.start}>Начать обслуживание</button>
            <button type="button" className="btn" disabled={actions.busy} onClick={actions.recall}>Повторный вызов</button>
            <button type="button" className="btn btn-danger" disabled={actions.busy} onClick={actions.noShow}>Не подошёл</button>
            <button type="button" className="btn" disabled={actions.busy} onClick={actions.complete}>Завершить<kbd>Enter</kbd></button>
          </>
        ) : (
          <button type="button" className="btn btn-primary btn-xl" disabled={actions.busy} onClick={actions.complete}>Завершить<kbd>Enter</kbd></button>
        )}
        <button type="button" className="btn" disabled={actions.busy} onClick={actions.requeue}>Вернуть в очередь</button>
        <button type="button" className="btn" disabled={actions.busy} onClick={actions.redirect}>Перенаправить</button>
      </div>
    </section>
  );
}
