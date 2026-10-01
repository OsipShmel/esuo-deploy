import { useEffect, useMemo, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { AuthGate } from '@shared/auth/AuthGate';
import { callNext, closeWindow, openWindow } from '@shared/api/commands';
import { getDemoClock, useClockNow } from '@shared/api/demoClock';
import { getBranch, type QueueItem, type WindowView } from '@shared/api/node';
import { useLiveQueue } from '@shared/api/useLiveQueue';
import { formatTime } from '@shared/format/time';
import { A11yToggles, Bar, Clock, ConnectionBanner, DeadlineHint, Icon, LoadLevelBadge, ProblemAlert, SourceBadge, TicketNumber, WindowStatusChip } from '@shared/ui';
import { boardJoinUrl, getBoard } from '../board/api';
import { QrImage } from '@shared/ui/QrImage';
import { getLoad, LOAD_CAUSE_TEXT } from '@shared/api/load';
import { getCenterLink, resetDemo, setClock } from './api';
import { ScenarioHelp } from './ScenarioHelp';
import { ScenarioRunner } from './ScenarioRunner';
import { DecisionLog } from './DecisionLog';
import { LOG_EVENTS, toLogEntry, type LogEntry } from './decisions';

const MAX_LOG = 200;
const RATES = [1, 10, 60, 180];
const DAY_PROFILES: [string, string][] = [['', 'не менять'], ['normal', 'Обычный день'], ['pension', 'Пенсионные выплаты'], ['weekend', 'Выходной']];

type View = 'pult' | 'scenarios';
const viewFromHash = (): View => (window.location.hash === '#scenarios' ? 'scenarios' : 'pult');

export function App() {
  return (
    <AuthGate title="ЕСУО · Демо-пульт" roles={['BRANCH_ADMIN']} forbiddenText="Демо-пульт открывается под учётной записью руководителя." defaultLogin="admin">
      {({ logout }) => <Pult onLogout={logout} />}
    </AuthGate>
  );
}

function Pult({ onLogout }: { onLogout: () => void }) {
  const qc = useQueryClient();
  const branch = useQuery({ queryKey: ['branch'], queryFn: getBranch, staleTime: 300_000 });
  const tz = branch.data?.timezone ?? 'Europe/Moscow';
  const centerParam = useMemo(() => new URLSearchParams(window.location.search).get('center'), []);

  // Журнал: события того же потока, что и очередь. «Пауза» замораживает показ, события продолжают копиться.
  const [entries, setEntries] = useState<LogEntry[]>([]);
  const [paused, setPaused] = useState(false);
  const frozen = useRef<LogEntry[]>([]);
  const live = useLiveQueue('/admin/stream', {
    extraEvents: LOG_EVENTS,
    onEvent: (name, data) => {
      if (name === 'ticket.called' || name.startsWith('window.')) void qc.invalidateQueries({ queryKey: ['board'] });
      const entry = (LOG_EVENTS as readonly string[]).includes(name) ? toLogEntry(name, data, tz) : null;
      if (entry) setEntries((cur) => [entry, ...cur].slice(0, MAX_LOG));
      if (name.startsWith('load.')) void qc.invalidateQueries({ queryKey: ['load'] });
    },
  });
  const board = useQuery({ queryKey: ['board'], queryFn: getBoard, refetchInterval: 15_000 });
  const load = useQuery({ queryKey: ['load'], queryFn: getLoad, refetchInterval: 10_000, retry: false });
  const link = useQuery({ queryKey: ['center-link'], queryFn: getCenterLink, refetchInterval: 10_000, retry: false });
  const clock = useQuery({ queryKey: ['demo-clock'], queryFn: getDemoClock, refetchInterval: 10_000, retry: false });
  const { now } = useClockNow();
  const [resetProfile, setResetProfile] = useState('');
  const [view, setView] = useState<View>(viewFromHash);
  useEffect(() => {
    const onHash = () => setView(viewFromHash());
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);

  const windows = live.windows ?? [];
  const queue = useMemo(() => live.queue ?? [], [live.queue]);
  const openCount = windows.filter((w) => w.status === 'open').length;
  const shownLog = paused ? frozen.current : entries;
  const setPause = (p: boolean) => { if (p) frozen.current = entries; setPaused(p); };

  const command = useMutation({
    mutationFn: (fn: () => Promise<unknown>) => fn(),
    onSuccess: () => live.refresh(),
  });
  const control = useMutation({
    mutationFn: (fn: () => Promise<unknown>) => fn(),
    onSuccess: () => { void qc.invalidateQueries({ queryKey: ['demo-clock'] }); live.refresh(); },
  });
  const reset = useMutation({
    mutationFn: () => resetDemo(resetProfile || null),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['demo-clock'] });
      void qc.invalidateQueries({ queryKey: ['load'] });
      void qc.invalidateQueries({ queryKey: ['board'] });
      live.refresh();
    },
  });

  const byService = useMemo(() => {
    const m = new Map<string, QueueItem[]>();
    for (const t of queue.filter((q) => q.status === 'waiting' || q.status === 'booked')) m.set(t.service_name, [...(m.get(t.service_name) ?? []), t]);
    return [...m.entries()].map(([name, list]) => [name, list.sort((a, b) => (a.due_at ?? '').localeCompare(b.due_at ?? ''))] as const);
  }, [queue]);

  const l = load.data;
  const clockState = clock.data;

  return (
    <div className="pult">
      <header className="pult-head card">
        <div className="row">
          <strong className="title">ЕСУО · {branch.data?.name ?? 'Отделение'}</strong>
          <span className="clock"><Clock tz={tz} seconds /></span>
          <div className="tabs-inline" role="tablist" aria-label="Раздел пульта">
            <button type="button" role="tab" className="btn btn-sm" aria-selected={view === 'pult'} aria-pressed={view === 'pult'} onClick={() => { window.location.hash = ''; }}>Пульт</button>
            <button type="button" role="tab" className="btn btn-sm" aria-selected={view === 'scenarios'} aria-pressed={view === 'scenarios'} onClick={() => { window.location.hash = 'scenarios'; }}>Сценарии</button>
          </div>
          <div className="row" role="group" aria-label="Скорость демо-часов">
            {RATES.map((r) => (
              <button key={r} type="button" className="btn btn-sm" aria-pressed={clockState?.rate === r} disabled={!clockState?.enabled || control.isPending}
                title={clockState?.enabled ? undefined : 'Демо-часы выключены (DEMO_MODE=false)'} onClick={() => control.mutate(() => setClock({ rate: r }))}>×{r}</button>
            ))}
          </div>
          <label className="row" title="Профиль дня, который встанет после сброса (не обязателен)">
            <span className="muted small">Профиль</span>
            <select value={resetProfile} onChange={(e) => setResetProfile(e.target.value)}>
              {DAY_PROFILES.map(([code, title]) => <option key={code} value={code}>{title}</option>)}
            </select>
          </label>
          <button type="button" className="btn btn-sm btn-danger" disabled={reset.isPending}
            title="Очищает очередь, окна и статистику; демо-часы возвращаются на реальное время. Требует DEMO_MODE=true на узле."
            onClick={() => reset.mutate()}>{reset.isPending ? 'Сброс…' : 'Сброс'}</button>
          <span className="grow" />
          <ScenarioHelp />
          <A11yToggles />
          <button type="button" className="btn btn-sm" onClick={onLogout}>Выйти</button>
        </div>
        <div className="row status-line">
          {l ? (
            <>
              <span className="row">Нагрузка <LoadLevelBadge level={l.level} />{l.clear_time_min != null && <span className="muted">разгрузка {l.clear_time_min} мин</span>}</span>
              {l.windows && <span>Окна {l.windows.open}/{l.windows.needed} нужных</span>}
            </>
          ) : <span className="muted">Нагрузка: блок планирования на узле не включён</span>}
          <span>Окна открыто {openCount} из {windows.length}</span>
          <span className="row">Связь с центром {link.data ? (link.data.center_link ? <span className="tone-ok">● есть</span> : <span className="tone-danger">● нет</span>) : <span className="muted">—</span>}</span>
        </div>
        {l?.causes && l.causes.length > 0 && <div className="muted small">{l.causes.map((c) => LOAD_CAUSE_TEXT[c.code] ?? c.code).join('; ')}</div>}
      </header>

      <ConnectionBanner status={live.stream} tz={tz} />
      <ProblemAlert error={live.error ?? command.error ?? control.error ?? reset.error} />

      {view === 'scenarios' && <ScenarioRunner />}

      {view === 'pult' && <div className="pult-grid">
        <section className="card mini-board" aria-label="Табло">
          <h2>Табло</h2>
          <ul className="mini-calls">
            {(board.data?.calls ?? []).slice(0, 6).map((c) => (
              <li key={`${c.display_number}-${c.called_at}`}><TicketNumber value={c.display_number} /><span className="muted">→</span><span>{c.window_name}</span></li>
            ))}
            {(board.data?.calls ?? []).length === 0 && <li className="muted">Вызовов пока нет</li>}
          </ul>
          {board.data && <QrImage value={boardJoinUrl(board.data, centerParam)} size={180} />}
          {board.data?.qr_mode_effective && !centerParam && (
            <span className="muted small">QR ведёт {board.data.qr_mode_effective === 'center' ? 'на центр (интернет)' : 'на узел (Wi-Fi отделения)'}</span>
          )}
        </section>

        <section className="card" aria-label="Очередь">
          <h2>Очередь</h2>
          {byService.length === 0 ? <p className="muted">Очередь пуста</p> : byService.map(([name, list]) => (
            <div key={name} className="q-group">
              <div className="q-title">{name} <span className="muted">({list.length})</span></div>
              <div className="q-chips">
                {list.slice(0, 10).map((t) => (
                  <span key={t.ticket_id} className={`q-chip${t.overdue ? ' overdue' : ''}`} title={t.status === 'booked' ? `запись на ${formatTime(t.slot_start, tz)}` : undefined}>
                    <b>{t.display_number}</b>{t.overdue && <Icon name="alert" size={14} />}<SourceBadge source={t.source} />
                    {t.status === 'waiting' && <DeadlineHint dueAt={t.due_at} now={now} />}
                  </span>
                ))}
                {list.length > 10 && <span className="muted">+{list.length - 10}</span>}
              </div>
            </div>
          ))}
          <h3 style={{ marginTop: 16 }}>Нагрузка по часам (работа / мощность)</h3>
          {l?.forecast?.length ? (
            <div className="hours">
              {l.forecast.map((f) => (
                <div key={f.hour_start} className="hour-row">
                  <span>{formatTime(f.hour_start, tz)}</span>
                  <Bar ratio={Math.min(1, f.ratio / 2)} tone={f.level === 'overload' ? 'danger' : f.level === 'high' ? 'warn' : 'ok'} />
                  <span>{f.ratio.toFixed(1)}</span>
                </div>
              ))}
            </div>
          ) : <p className="muted small">Прогноз по часам появится после включения блока планирования нагрузки на узле.</p>}
        </section>

        <section className="card" aria-label="Окна">
          <h2>Окна</h2>
          <div className="windows">
            {windows.map((w) => (
              <WindowCard key={w.id} w={w} now={now} busy={command.isPending}
                onOpen={() => command.mutate(() => openWindow(w.id, w.service_ids.length ? w.service_ids : (branch.data?.services ?? []).filter((s) => s.active).map((s) => s.id)))}
                onNext={() => command.mutate(() => callNext(w.id))}
                onClose={() => command.mutate(() => closeWindow(w.id, 'graceful'))} />
            ))}
          </div>
        </section>
      </div>}

      {view === 'pult' && <DecisionLog entries={shownLog} paused={paused} onPause={setPause} tz={tz} />}
    </div>
  );
}

function WindowCard({ w, now, busy, onOpen, onNext, onClose }: { w: WindowView; now: number; busy: boolean; onOpen: () => void; onNext: () => void; onClose: () => void }) {
  const t = w.active_ticket;
  const elapsed = t?.called_at ? Math.max(0, Math.round((now - new Date(t.called_at).getTime()) / 1000)) : null;
  return (
    <article className="win">
      <div className="row between"><strong>{w.name}</strong><WindowStatusChip status={w.status} /></div>
      {t ? <div className="row"><TicketNumber value={t.display_number} />{elapsed !== null && <span className="muted">{Math.floor(elapsed / 60)}:{String(elapsed % 60).padStart(2, '0')}</span>}</div>
        : <span className="muted">{w.status === 'open' ? 'Свободно' : '—'}</span>}
      <div className="row">
        {w.status === 'closed' && <button type="button" className="btn btn-sm btn-primary" disabled={busy} onClick={onOpen}>Открыть</button>}
        {w.status === 'open' && <button type="button" className="btn btn-sm btn-primary" disabled={busy || !!t} onClick={onNext}>Следующий</button>}
        {w.status === 'open' && <button type="button" className="btn btn-sm" disabled={busy} onClick={onClose}>Закрыть</button>}
        <label className="check" title="Проигрыватель сценариев пока не реализован на узле"><input type="checkbox" disabled /> авто</label>
      </div>
    </article>
  );
}
