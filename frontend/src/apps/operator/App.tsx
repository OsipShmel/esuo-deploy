import { useEffect, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { AuthGate } from '@shared/auth/AuthGate';
import { useLiveQueue } from '@shared/api/useLiveQueue';
import { DEFAULT_TZ } from '@shared/format/time';
import { A11yToggles, ConnectionBanner, EmptyState, Loading, ProblemAlert, WindowStatusChip } from '@shared/ui';
import {
  addWalkin, callNext, closeWindow, getBranch, openWindow, redirectTicket, reportIncident, requeueTicket, ticketCommand,
  type QueueItem, type WindowView,
} from './api';
import { CurrentTicket } from './CurrentTicket';
import { IncidentForm } from './IncidentForm';
import { Modal } from './Modal';
import { QueuePanel } from './QueuePanel';
import { WalkinForm } from './WalkinForm';
import { useCommand } from './useCommand';

export function App() {
  return (
    <AuthGate title="ЕСУО · Оператор окна" roles={['OPERATOR', 'BRANCH_ADMIN']} forbiddenText="Экран оператора открывается под учётной записью оператора или руководителя." defaultLogin="operator">
      {({ logout }) => <Desk onLogout={logout} />}
    </AuthGate>
  );
}

const WINDOW_KEY = 'esuo.operator.window';
const readWindow = (): string | null => { try { return window.localStorage.getItem(WINDOW_KEY); } catch { return null; } };
const writeWindow = (id: string) => { try { window.localStorage.setItem(WINDOW_KEY, id); } catch { /* без сохранения выбор живёт до перезагрузки */ } };

type Dialog = null | 'open' | 'close' | 'requeue' | 'redirect' | 'no-show';

function Desk({ onLogout }: { onLogout: () => void }) {
  const branch = useQuery({ queryKey: ['branch'], queryFn: getBranch, staleTime: 300_000 });
  const live = useLiveQueue('/operator/stream');
  const [windowId, setWindowId] = useState<string | null>(readWindow);
  const [dialog, setDialog] = useState<Dialog>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [now, setNow] = useState(() => Date.now());
  const cmd = useCommand(live.refresh);
  const tz = branch.data?.timezone ?? DEFAULT_TZ;

  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), 15_000);
    return () => window.clearInterval(id);
  }, []);

  const windows = useMemo(() => live.windows ?? [], [live.windows]);
  const w: WindowView | undefined = windows.find((x) => x.id === windowId) ?? undefined;
  const active = w?.active_ticket ?? null;
  const item: QueueItem | undefined = active ? (live.queue ?? []).find((q) => q.ticket_id === active.ticket_id) : undefined;

  // Первое открытие: единственное окно выбираем само.
  useEffect(() => {
    if (!windowId && windows.length === 1 && windows[0]) { setWindowId(windows[0].id); writeWindow(windows[0].id); }
  }, [windowId, windows]);

  // ------------------------------------------------------------------ команды

  const version = active?.version ?? 0;
  const actions = useMemo(() => {
    const t = active;
    const run = cmd.run;
    return {
      busy: cmd.busy,
      next: () => { if (w) void run(async () => { const r = await callNext(w.id); setNotice(`Вызван ${r.display_number}`); }); },
      start: () => { if (t) void run(() => ticketCommand(t.ticket_id, 'start', version)); },
      complete: () => { if (t) void run(async () => { await ticketCommand(t.ticket_id, 'complete', version); setNotice(`${t.display_number} обслужен`); }); },
      recall: () => { if (t) void run(async () => { await ticketCommand(t.ticket_id, 'recall', version); setNotice(`Повторный вызов ${t.display_number}`); }); },
      noShow: () => setDialog('no-show'),
      requeue: () => setDialog('requeue'),
      redirect: () => setDialog('redirect'),
    };
  }, [active, cmd.busy, cmd.run, version, w]);

  // Горячие клавиши (концепция 4.3): пробел — «Следующий», Enter — «Завершить». Не мешаем полям, кнопкам и диалогам.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement | null;
      if (!target || target.closest('input, textarea, select, button, a, dialog, [contenteditable]')) return;
      if (document.querySelector('dialog[open]')) return;
      if (e.code === 'Space' && w?.status === 'open' && !active) { e.preventDefault(); actions.next(); }
      else if (e.key === 'Enter' && active) { e.preventDefault(); actions.complete(); }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [w?.status, active, actions]);

  useEffect(() => {
    if (!notice) return undefined;
    const id = window.setTimeout(() => setNotice(null), 4000);
    return () => window.clearTimeout(id);
  }, [notice]);

  const closeDialog = () => setDialog(null);

  return (
    <>
      <header className="app-header">
        <h1>ЕСУО · Оператор</h1>
        <span className="muted">{branch.data?.name ?? ''}</span>
        <span className="grow" />
        <A11yToggles />
        <button type="button" className="btn btn-sm" onClick={onLogout}>Выйти</button>
      </header>
      <main className="desk">
        <ConnectionBanner status={live.stream} tz={tz} />
        <ProblemAlert error={live.error ?? cmd.error} />
        <div role="status" aria-live="polite">{notice && <div className="banner tone-ok">{notice}</div>}</div>

        <section className="card window-bar" aria-label="Окно">
          <label className="field">Моё окно
            <select value={windowId ?? ''} onChange={(e) => { setWindowId(e.target.value); writeWindow(e.target.value); }}>
              <option value="" disabled>Выберите окно</option>
              {windows.map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}
            </select>
          </label>
          {w && <WindowStatusChip status={w.status} />}
          {w && <span className="muted">Услуги: {serviceNames(w, branch.data?.services)}</span>}
          <span className="grow" />
          {w && w.status !== 'open' && <button type="button" className="btn btn-primary" disabled={cmd.busy || w.status === 'closing'} onClick={() => setDialog('open')}>Открыть окно</button>}
          {w && w.status !== 'closed' && <button type="button" className="btn" disabled={cmd.busy} onClick={() => setDialog('close')}>Закрыть окно</button>}
        </section>

        {live.loading ? <Loading rows={4} /> : !w ? (
          <EmptyState title="Выберите окно">Выберите своё окно вверху страницы, чтобы начать работу.</EmptyState>
        ) : (
          <>
            {w.status === 'closing' && <div className="banner tone-warn" role="status">Окно закрывается: после завершения текущего клиента оно закроется.</div>}
            <div className="workspace">
              <div className="stack">
                <CurrentTicket window={w} item={item} actions={actions} tz={tz} />
                <div className="two">
                  <WalkinForm window={w} branch={branch.data} busy={cmd.busy}
                    onSubmit={async (v, key) => !!(await cmd.run(async () => { const r = await addWalkin(w.id, v, key); setNotice(`Выдан талон ${r.display_number}`); return r; }))} />
                  <IncidentForm busy={cmd.busy}
                    onSubmit={async (v) => !!(await cmd.run(async () => {
                      await reportIncident({ ...v, window_id: w.id, ticket_id: active?.ticket_id });
                      setNotice('Сообщение отправлено руководителю');
                      return true;
                    }))} />
                </div>
              </div>
              <QueuePanel window={w} queue={live.queue ?? []} now={now} tz={tz} busy={cmd.busy}
                onCheckIn={(t) => void cmd.run(async () => { await ticketCommand(t.ticket_id, 'check-in', t.version); setNotice(`${t.display_number} отмечен как пришедший`); })} />
            </div>
          </>
        )}
      </main>

      {w && (
        <>
          <OpenDialog open={dialog === 'open'} window={w} services={branch.data?.services ?? []} onClose={closeDialog}
            onSubmit={(ids) => { closeDialog(); void cmd.run(() => openWindow(w.id, ids)); }} />
          <CloseDialog open={dialog === 'close'} hasActive={!!active} onClose={closeDialog}
            onSubmit={(mode) => { closeDialog(); void cmd.run(() => closeWindow(w.id, mode)); }} />
          <Modal open={dialog === 'no-show'} title="Клиент не подошёл?" onClose={closeDialog}>
            <p>Талон {active?.display_number} будет закрыт как «не подошёл», окно освободится.</p>
            <div className="actions">
              <button type="button" className="btn btn-danger" onClick={() => { closeDialog(); if (active) void cmd.run(() => ticketCommand(active.ticket_id, 'no-show', version)); }}>Да, не подошёл</button>
              <button type="button" className="btn" onClick={closeDialog}>Отмена</button>
            </div>
          </Modal>
          <RequeueDialog open={dialog === 'requeue'} onClose={closeDialog}
            onSubmit={(mode) => { closeDialog(); if (active) void cmd.run(async () => { await requeueTicket(active.ticket_id, mode, version); setNotice(`${active.display_number} возвращён в очередь`); }); }} />
          <RedirectDialog open={dialog === 'redirect'} window={w} windows={windows} services={branch.data?.services ?? []} onClose={closeDialog}
            onSubmit={(body) => { closeDialog(); if (active) void cmd.run(async () => { await redirectTicket(active.ticket_id, body, version); setNotice(`${active.display_number} перенаправлен`); }); }} />
        </>
      )}
    </>
  );
}

function serviceNames(w: WindowView, services: { id: string; name: string }[] | undefined): string {
  const names = w.service_ids.map((id) => services?.find((s) => s.id === id)?.name).filter(Boolean);
  return names.length ? names.join(', ') : '—';
}

// ------------------------------------------------------------------ диалоги

function OpenDialog({ open, window: w, services, onClose, onSubmit }: {
  open: boolean; window: WindowView; services: { id: string; name: string; active: boolean }[]; onClose: () => void; onSubmit: (ids: string[]) => void;
}) {
  const active = services.filter((s) => s.active);
  const [ids, setIds] = useState<string[]>([]);
  useEffect(() => { if (open) setIds(w.service_ids.length ? w.service_ids : active.map((s) => s.id)); }, [open]); // eslint-disable-line react-hooks/exhaustive-deps
  const toggle = (id: string) => setIds((c) => (c.includes(id) ? c.filter((x) => x !== id) : [...c, id]));
  return (
    <Modal open={open} title={`Открыть ${w.name}`} onClose={onClose}>
      <fieldset style={{ border: 0, padding: 0, margin: 0 }}>
        <legend className="muted small">Какие услуги окно обслуживает сейчас</legend>
        {active.map((s) => <label key={s.id} className="check"><input type="checkbox" checked={ids.includes(s.id)} onChange={() => toggle(s.id)} /> {s.name}</label>)}
      </fieldset>
      <div className="actions">
        <button type="button" className="btn btn-primary" disabled={ids.length === 0} onClick={() => onSubmit(ids)}>Открыть</button>
        <button type="button" className="btn" onClick={onClose}>Отмена</button>
      </div>
    </Modal>
  );
}

function CloseDialog({ open, hasActive, onClose, onSubmit }: { open: boolean; hasActive: boolean; onClose: () => void; onSubmit: (m: 'graceful' | 'force') => void }) {
  const [mode, setMode] = useState<'graceful' | 'force'>('graceful');
  return (
    <Modal open={open} title="Закрыть окно" onClose={onClose}>
      {hasActive ? (
        <div className="stack">
          <label className="check"><input type="radio" name="mode" checked={mode === 'graceful'} onChange={() => setMode('graceful')} />
            <span><strong>Дообслужить и закрыть.</strong> Новых клиентов не вызываем, после завершения окно закроется.</span></label>
          <label className="check"><input type="radio" name="mode" checked={mode === 'force'} onChange={() => setMode('force')} />
            <span><strong>Закрыть сейчас.</strong> Текущий клиент вернётся в голову очереди, своё место он не теряет.</span></label>
        </div>
      ) : <p>Окно свободно и закроется сразу.</p>}
      <div className="actions">
        <button type="button" className="btn btn-primary" onClick={() => onSubmit(hasActive ? mode : 'graceful')}>Закрыть окно</button>
        <button type="button" className="btn" onClick={onClose}>Отмена</button>
      </div>
    </Modal>
  );
}

function RequeueDialog({ open, onClose, onSubmit }: { open: boolean; onClose: () => void; onSubmit: (m: 'keep_position' | 'to_tail') => void }) {
  return (
    <Modal open={open} title="Вернуть клиента в очередь" onClose={onClose}>
      <div className="actions" style={{ marginTop: 0 }}>
        <button type="button" className="btn btn-primary" onClick={() => onSubmit('keep_position')}>С сохранением места</button>
        <button type="button" className="btn" onClick={() => onSubmit('to_tail')}>В конец очереди</button>
        <button type="button" className="btn" onClick={onClose}>Отмена</button>
      </div>
      <p className="muted small" style={{ marginTop: 12 }}>«С сохранением места» — клиент останется первым по дедлайну. «В конец» — дедлайн пересчитывается от текущего момента.</p>
    </Modal>
  );
}

function RedirectDialog({ open, window: w, windows, services, onClose, onSubmit }: {
  open: boolean; window: WindowView; windows: WindowView[]; services: { id: string; name: string; active: boolean }[];
  onClose: () => void; onSubmit: (b: { service_id?: string; window_id?: string; reason?: string }) => void;
}) {
  const [service, setService] = useState('');
  const [target, setTarget] = useState('');
  const [reason, setReason] = useState('');
  useEffect(() => { if (open) { setService(''); setTarget(''); setReason(''); } }, [open]);
  // Адресное окно должно оказывать выбранную (или прежнюю) услугу — иначе сервер вернёт SERVICE_NOT_IN_WINDOW.
  const targets = windows.filter((x) => x.id !== w.id && x.status !== 'closed');
  return (
    <Modal open={open} title="Перенаправить клиента" onClose={onClose}>
      <div className="form-grid">
        <label className="field">На другую услугу
          <select value={service} onChange={(e) => setService(e.target.value)}>
            <option value="">Оставить прежнюю</option>
            {services.filter((s) => s.active).map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
          </select>
        </label>
        <label className="field">В конкретное окно
          <select value={target} onChange={(e) => setTarget(e.target.value)}>
            <option value="">Любое подходящее</option>
            {targets.map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}
          </select>
        </label>
        <label className="field">Причина (необязательно)
          <input value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} />
        </label>
      </div>
      <div className="actions">
        <button type="button" className="btn btn-primary" disabled={!service && !target}
          onClick={() => onSubmit({ ...(service ? { service_id: service } : {}), ...(target ? { window_id: target } : {}), ...(reason.trim() ? { reason: reason.trim() } : {}) })}>Перенаправить</button>
        <button type="button" className="btn" onClick={onClose}>Отмена</button>
      </div>
    </Modal>
  );
}
