import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError } from '@shared/api/http';
import { useClockNow } from '@shared/api/demoClock';
import { useEventStream } from '@shared/sse/useEventStream';
import { formatTime } from '@shared/format/time';
import { ConnectionBanner, EmptyState, EtaText, Loading, ProblemAlert, SourceBadge, StatusChip } from '@shared/ui';
import { useBranchApi } from '../App';
import { beep, notificationsSupported, systemNotify, vibrate } from '../alerts';
import { CheckInPanel } from '../CheckInPanel';
import type { ClientTicket } from '../branchApi';
import { CLIENT_PHRASES } from '../phrases';
import { forgetTicket, saveTicket } from '../storage';

const EVENTS = ['ticket.called', 'ticket.recalled', 'notification.requested', 'branch_unavailable'] as const;
const TERMINAL = new Set(['done', 'cancelled', 'no_show']);
const POSTPONE_OPTIONS = [10, 15, 30];

function people(n: number): string {
  const m10 = n % 10;
  const m100 = n % 100;
  const word = m10 === 1 && m100 !== 11 ? 'человек' : m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14) ? 'человека' : 'человек';
  return `${n} ${word}`;
}

/** Главный экран: статус талона в реальном времени (SSE со снапшотом, при обрыве — опрос раз в 10 с). */
export function TicketPage() {
  const { b = '', token = '' } = useParams();
  const api = useBranchApi();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const { now } = useClockNow(15_000);

  const [branchOffline, setBranchOffline] = useState(false);
  const [dismissedCall, setDismissedCall] = useState<string | null>(null);
  const [panel, setPanelRaw] = useState<null | 'postpone' | 'cancel' | 'checkin'>(null);
  const [notifyAsked, setNotifyAsked] = useState(false);
  const [action, setAction] = useState<{ busy: boolean; error: unknown }>({ busy: false, error: null });
  /** Смена панели убирает ошибку прошлого действия: она относилась не к новой панели. */
  const setPanel = (next: null | 'postpone' | 'cancel' | 'checkin') => { setPanelRaw(next); setAction((a) => (a.busy ? a : { busy: false, error: null })); };
  const lastStatus = useRef<string | null>(null);

  const key = ['ticket', b, token, api.mode];
  const info = useQuery({ queryKey: ['info', b, api.mode], queryFn: () => api.info(b), staleTime: 300_000 });
  const tz = info.data?.timezone ?? 'Europe/Moscow';

  const stream = useEventStream({
    url: `${api.streamUrl(b, token)}`,
    events: EVENTS,
    onSnapshot: (data) => { setBranchOffline(false); qc.setQueryData(key, data as ClientTicket); },
    onEvent: (name, data) => {
      if (name === 'branch_unavailable') { setBranchOffline(true); return; }
      if (name === 'ticket.recalled') {
        const w = windowName(data);
        vibrate(); beep(); systemNotify('Вас вызывают', w ? `Подойдите к окну ${w}` : 'Подойдите к окну');
        setDismissedCall(null);
      }
    },
  });
  const q = useQuery({
    queryKey: key,
    queryFn: () => api.ticket(b, token),
    refetchInterval: stream.state === 'polling' ? 10_000 : false,
    retry: (count, e) => !(e instanceof ApiError && e.code === 'TICKET_NOT_FOUND') && count < 1,
  });
  const t = q.data;

  // Сохраняем токен для возврата к талону; чужой или устаревший токен — забываем.
  useEffect(() => { if (t) saveTicket({ branch: b, token, number: t.display_number }); }, [t?.display_number, b, token]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { if (q.error instanceof ApiError && q.error.code === 'TICKET_NOT_FOUND') forgetTicket(); }, [q.error]);

  // Вызов: вибрация, звук, системное уведомление — один раз при переходе в `called`.
  useEffect(() => {
    if (!t) return;
    if (t.status === 'called' && lastStatus.current !== 'called') {
      vibrate(); beep(); systemNotify('Вас вызывают', t.window ? `Подойдите к окну ${t.window.name}` : 'Подойдите к окну');
    }
    lastStatus.current = t.status;
  }, [t?.status]); // eslint-disable-line react-hooks/exhaustive-deps

  async function run(fn: () => Promise<ClientTicket>) {
    setAction({ busy: true, error: null });
    try {
      const next = await fn();
      qc.setQueryData(key, next);
      setPanel(null);
      setAction({ busy: false, error: null });
    } catch (e) {
      setAction({ busy: false, error: e });
      if (e instanceof ApiError && e.code === 'INVALID_STATE_TRANSITION') void q.refetch();
    }
  }

  if (q.isLoading) return <Loading rows={5} />;
  if (!t) {
    return (
      <div className="stack">
        <ProblemAlert error={q.error} phrases={CLIENT_PHRASES} />
        <EmptyState title="Талон не найден"><Link to="/">На главную</Link></EmptyState>
      </div>
    );
  }

  const called = t.status === 'called';
  const terminal = TERMINAL.has(t.status);
  const farOff = t.eta !== null && new Date(t.eta).getTime() > Date.now() + 5 * 60_000;
  const soon = t.status === 'waiting' && t.position !== null && t.position <= 3 && !farOff;
  const callShown = called && dismissedCall !== (t.called_at ?? '');

  return (
    <div className="stack">
      <ConnectionBanner status={stream} tz={tz} />
      {branchOffline && <div className="banner tone-warn" role="status">Отделение не на связи. Ваш талон сохранён.</div>}

      {callShown && (
        <div className="callscreen" role="alertdialog" aria-live="assertive" aria-label="Вас вызывают">
          <div className="big">Подойдите к окну</div>
          <div className="number">{t.window?.name.replace(/^Окно\s*/i, '') || '—'}</div>
          <div className="big">{t.display_number}</div>
          <button type="button" className="btn btn-block" onClick={() => setDismissedCall(t.called_at ?? '')}>Иду</button>
        </div>
      )}

      <section className="card ticket-card" aria-live="polite">
        <div className="number">{t.display_number}</div>
        <div><StatusChip status={t.status} text={statusText(t, tz)} /></div>
        <div className="muted">{t.service.name} · <SourceBadge source={t.source} /></div>
        {!terminal && t.status === 'waiting' && (
          <>
            {t.position !== null && <div className="headline">{t.position === 0 ? 'Вы следующий' : `Перед вами ${people(t.position)}`}</div>}
            <div className="eta">Вызов: <EtaText eta={t.eta} confidence={t.eta_confidence} slotStart={t.slot_start} now={now} tz={tz} /></div>
          </>
        )}
        {t.status === 'booked' && <div className="eta"><EtaText eta={t.eta} confidence="slot" slotStart={t.slot_start} now={now} tz={tz} /></div>}
        {t.status === 'serving' && <div className="headline">Вас обслуживает окно {t.window?.name ?? ''}</div>}
        {called && !callShown && <div className="headline">Подойдите к {t.window?.name ?? 'окну'}</div>}
        {terminal && <div className="headline">{terminalText(t.status)}</div>}
      </section>

      {soon && <div className="banner tone-ok" role="status">Скоро ваша очередь — подходите к залу.</div>}

      {panel !== 'checkin' && <ProblemAlert error={action.error} phrases={CLIENT_PHRASES} />}

      {!terminal && (
        <>
          {t.status === 'booked' && panel !== 'checkin' && (
            <button type="button" className="btn btn-primary btn-block" onClick={() => setPanel('checkin')}>Я в отделении</button>
          )}
          {panel === 'checkin' && (
            <CheckInPanel busy={action.busy} error={action.error} onClose={() => setPanel(null)}
              onSubmit={(code) => void run(() => api.checkIn(b, token, code))} />
          )}

          {t.can_postpone && panel !== 'postpone' && <button type="button" className="btn btn-block" onClick={() => setPanel('postpone')}>Отложить</button>}
          {panel === 'postpone' && (
            <div className="card stack">
              <h2>Отложить на</h2>
              <div className="row">
                {POSTPONE_OPTIONS.map((m) => <button key={m} type="button" className="btn" disabled={action.busy} onClick={() => void run(() => api.postpone(b, token, m))}>{m} мин</button>)}
                <button type="button" className="btn" onClick={() => setPanel(null)}>Не откладывать</button>
              </div>
              <p className="muted small">Ваше место в очереди сохранится, но вызвать вас смогут не раньше выбранного времени.</p>
            </div>
          )}

          {t.can_cancel && panel !== 'cancel' && <button type="button" className="btn btn-danger btn-block" onClick={() => setPanel('cancel')}>Отменить талон</button>}
          {panel === 'cancel' && (
            <div className="card stack">
              <h2>Отменить талон {t.display_number}?</h2>
              <div className="row">
                <button type="button" className="btn btn-danger" disabled={action.busy} onClick={() => void run(() => api.cancel(b, token))}>Да, отменить</button>
                <button type="button" className="btn" onClick={() => setPanel(null)}>Нет</button>
              </div>
            </div>
          )}

          {notificationsSupported() && !notifyAsked && Notification.permission === 'default' && (
            <button type="button" className="btn btn-block" onClick={() => void Notification.requestPermission().then(() => setNotifyAsked(true))}>Включить уведомления о вызове</button>
          )}
          <p className="muted small" style={{ textAlign: 'center' }}>Не закрывайте страницу: мы сообщим, когда вас вызовут. Если закроете — вернитесь по этой же ссылке.</p>
        </>
      )}

      {terminal && (
        <button type="button" className="btn btn-primary btn-block" onClick={() => { forgetTicket(); navigate(`/b/${b}`); }}>Новый талон</button>
      )}
    </div>
  );
}

function statusText(t: ClientTicket, tz: string): string | undefined {
  if (t.status === 'booked') return `Записан на ${formatTime(t.slot_start, tz)}`;
  if (t.status === 'called') return `Вызван в ${t.window?.name ?? 'окно'}`;
  return undefined;
}

function terminalText(status: string): string {
  if (status === 'done') return 'Обслуживание завершено. Спасибо!';
  if (status === 'cancelled') return 'Талон отменён.';
  return 'Вы не подошли к окну, талон закрыт.';
}

function windowName(data: unknown): string {
  const d = data as { window_name?: string; data?: { window_name?: string } } | null;
  return d?.window_name ?? d?.data?.window_name ?? '';
}
