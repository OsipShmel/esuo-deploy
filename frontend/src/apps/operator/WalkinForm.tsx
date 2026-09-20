import { useRef, useState, type FormEvent } from 'react';
import { CATEGORY_LABEL, label } from '@shared/format/labels';
import type { BranchInfo, WindowView } from './api';

/** Живая очередь (walk-in): талон клиенту без телефона. `Idempotency-Key` — на одну отправку, повтор безопасен. */
export function WalkinForm({ window: w, branch, busy, onSubmit }: {
  window: WindowView; branch: BranchInfo | undefined;
  busy: boolean; onSubmit: (v: { service_id: string; client_category: string; call_now: boolean }, key: string) => Promise<boolean>;
}) {
  const services = (branch?.services ?? []).filter((s) => s.active && w.service_ids.includes(s.id));
  const categories = branch?.categories ?? ['default'];
  const [service, setService] = useState('');
  const [category, setCategory] = useState('default');
  const [callNow, setCallNow] = useState(false);
  const key = useRef(crypto.randomUUID());
  const serviceId = service || services[0]?.id || '';
  const canCallNow = w.status === 'open' && !w.active_ticket;

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!serviceId) return;
    const ok = await onSubmit({ service_id: serviceId, client_category: category, call_now: callNow && canCallNow }, key.current);
    if (ok) { key.current = crypto.randomUUID(); setCallNow(false); }
  }

  return (
    <form className="card form-grid" onSubmit={submit} aria-labelledby="walkin-title">
      <h2 id="walkin-title" style={{ margin: 0 }}>Живая очередь</h2>
      {services.length === 0 ? <p className="muted">У окна нет активных услуг.</p> : (
        <>
          <label className="field">Услуга
            <select value={serviceId} onChange={(e) => setService(e.target.value)}>
              {services.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
            </select>
          </label>
          <label className="field">Категория клиента
            <select value={category} onChange={(e) => setCategory(e.target.value)}>
              {categories.map((c) => <option key={c} value={c}>{label(CATEGORY_LABEL, c)}</option>)}
            </select>
          </label>
          <label className="check"><input type="checkbox" checked={callNow && canCallNow} disabled={!canCallNow} onChange={(e) => setCallNow(e.target.checked)} />
            Вызвать сразу (окно свободно)</label>
          <button type="submit" className="btn btn-primary" disabled={busy}>Добавить клиента</button>
        </>
      )}
    </form>
  );
}
