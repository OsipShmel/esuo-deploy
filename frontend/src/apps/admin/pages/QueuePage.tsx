import { useEffect, useMemo, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useLiveQueue } from '@shared/api/useLiveQueue';
import { formatSeconds } from '@shared/format/duration';
import { formatTime } from '@shared/format/time';
import { CATEGORY_LABEL, label } from '@shared/format/labels';
import { ConnectionBanner, DeadlineHint, EmptyState, Loading, ProblemAlert, SourceBadge, StatusChip, TicketNumber, WindowStatusChip } from '@shared/ui';
import type { QueueItem } from '../api';
import { useTz } from '../context';

export function QueuePage() {
  const tz = useTz();
  const [now, setNow] = useState(() => Date.now());
  const [serviceFilter, setServiceFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('');

  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), 15_000);
    return () => window.clearInterval(id);
  }, []);

  const qc = useQueryClient();
  const live = useLiveQueue('/admin/stream', { onEvent: () => void qc.invalidateQueries({ queryKey: ['recommendations'] }) });
  const stream = live.stream;
  const queue = { data: live.queue, isLoading: live.loading, error: live.error };
  const windows = { data: live.windows, isLoading: live.loading, error: null as unknown };

  const items = useMemo(
    () => (queue.data ?? []).filter((t) => (!serviceFilter || t.service_id === serviceFilter) && (!statusFilter || t.status === statusFilter)),
    [queue.data, serviceFilter, statusFilter],
  );
  const services = useMemo(() => {
    const m = new Map<string, string>();
    for (const t of queue.data ?? []) m.set(t.service_id, t.service_name);
    return [...m.entries()];
  }, [queue.data]);
  const windowName = (id: string | null) => (windows.data ?? []).find((w) => w.id === id)?.name ?? '—';

  return (
    <div className="stack">
      <ConnectionBanner status={stream} tz={tz} />
      <ProblemAlert error={queue.error ?? windows.error} />

      <section aria-labelledby="w-title">
        <h2 id="w-title">Окна</h2>
        {windows.isLoading ? <Loading /> : (windows.data ?? []).length === 0 ? <EmptyState title="Окон нет" /> : (
          <div className="window-cards">
            {(windows.data ?? []).map((w) => (
              <article key={w.id} className="card window-card">
                <div className="row between"><h3 style={{ margin: 0 }}>{w.name}</h3><WindowStatusChip status={w.status} /></div>
                {w.active_ticket ? (
                  <div className="stack" style={{ gap: 4 }}>
                    <div className="row"><TicketNumber value={w.active_ticket.display_number} /><StatusChip status={w.active_ticket.status} text={w.active_ticket.status === 'called' ? `Вызван в ${w.name}` : undefined} /></div>
                    <div className="muted small">{w.active_ticket.service.name} · <SourceBadge source={w.active_ticket.source} /></div>
                    {w.active_ticket.called_at && <div className="muted small">с {formatTime(w.active_ticket.called_at, tz)}</div>}
                  </div>
                ) : <span className="muted">{w.status === 'open' ? 'Свободно' : 'Клиента нет'}</span>}
              </article>
            ))}
          </div>
        )}
      </section>

      <section aria-labelledby="q-title" className="card">
        <div className="row between" style={{ marginBottom: 12 }}>
          <h2 id="q-title" style={{ margin: 0 }}>Очередь · {items.length}</h2>
          <div className="toolbar">
            <label className="field">Услуга
              <select value={serviceFilter} onChange={(e) => setServiceFilter(e.target.value)}>
                <option value="">Все</option>
                {services.map(([id, name]) => <option key={id} value={id}>{name}</option>)}
              </select>
            </label>
            <label className="field">Статус
              <select value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)}>
                <option value="">Все</option>
                <option value="booked">Записан</option><option value="waiting">Ожидает</option>
                <option value="called">Вызван</option><option value="serving">Обслуживается</option>
              </select>
            </label>
          </div>
        </div>
        {queue.isLoading ? <Loading rows={5} /> : items.length === 0 ? (
          <EmptyState title="Очередь пуста">Новые талоны появятся здесь сразу.</EmptyState>
        ) : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>Номер</th><th>Услуга</th><th>Источник</th><th>Категория</th><th>Статус</th><th>Дедлайн</th><th>Окно</th><th className="num">Позиция</th><th>Ждёт</th></tr></thead>
              <tbody>
                {items.map((t) => <QueueRow key={t.ticket_id} t={t} now={now} windowName={windowName} />)}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}

function QueueRow({ t, now, windowName }: { t: QueueItem; now: number; windowName: (id: string | null) => string }) {
  const tz = useTz();
  const waitingSec = Math.max(0, (now - new Date(t.eligible_from ?? t.issued_at).getTime()) / 1000);
  const active = t.status === 'called' || t.status === 'serving';
  return (
    <tr className={t.overdue ? 'overdue' : undefined}>
      <td><TicketNumber value={t.display_number} /></td>
      <td>{t.service_name}</td>
      <td><SourceBadge source={t.source} /></td>
      <td>{label(CATEGORY_LABEL, t.client_category)}</td>
      <td><StatusChip status={t.status} /></td>
      <td>{t.status === 'booked' ? <span className="muted">запись на {formatTime(t.slot_start, tz)}</span> : active ? <span className="muted">—</span> : <DeadlineHint dueAt={t.due_at} now={now} />}</td>
      <td>{active ? windowName(t.window_id) : t.target_window_id ? <span title="Адресный талон">→ {windowName(t.target_window_id)}</span> : '—'}</td>
      <td className="num">{t.position ?? '—'}</td>
      <td>{t.status === 'waiting' ? formatSeconds(waitingSec) : '—'}</td>
    </tr>
  );
}
