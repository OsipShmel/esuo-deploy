import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError } from '@shared/api/http';
import { EmptyState, Loading, ProblemAlert, WindowStatusChip } from '@shared/ui';
import {
  createEmployee, createService, createWindow, deleteEmployee, deleteService, deleteWindow, listEmployees, listServices,
  listWindows, renameWindow, setWindowServices, updateEmployee, updateService,
  type EmployeeItem, type ServiceItem, type WindowItem,
} from '../api';

type Part = 'services' | 'windows' | 'employees';

export function CatalogPage() {
  const [part, setPart] = useState<Part>('services');
  const [includeInactive, setIncludeInactive] = useState(false);
  return (
    <div className="stack">
      <div className="card toolbar">
        <div className="subtabs" role="group" aria-label="Справочник">
          <button type="button" className="btn" aria-pressed={part === 'services'} onClick={() => setPart('services')}>Услуги</button>
          <button type="button" className="btn" aria-pressed={part === 'windows'} onClick={() => setPart('windows')}>Окна</button>
          <button type="button" className="btn" aria-pressed={part === 'employees'} onClick={() => setPart('employees')}>Сотрудники</button>
        </div>
        <label className="check"><input type="checkbox" checked={includeInactive} onChange={(e) => setIncludeInactive(e.target.checked)} /> Показывать отключённые</label>
      </div>
      {part === 'services' && <Services include={includeInactive} />}
      {part === 'windows' && <Windows include={includeInactive} />}
      {part === 'employees' && <Employees include={includeInactive} />}
    </div>
  );
}

/** Ошибки полей `VALIDATION_ERROR`: список нарушений из `errors[]`. */
function FieldErrors({ error }: { error: unknown }) {
  if (!(error instanceof ApiError) || error.errors.length === 0) return null;
  return <ul className="field-error" style={{ margin: 0, paddingLeft: 20 }}>{error.errors.map((e, i) => <li key={i}>{e.field ? `${e.field}: ` : ''}{e.message}</li>)}</ul>;
}

function useInvalidate(...keys: string[]) {
  const qc = useQueryClient();
  return () => Promise.all(keys.map((k) => qc.invalidateQueries({ queryKey: [k] })));
}

// ------------------------------------------------------------------ услуги

function Services({ include }: { include: boolean }) {
  const list = useQuery({ queryKey: ['services', include], queryFn: () => listServices(include) });
  const invalidate = useInvalidate('services', 'windows', 'queue');
  const [editing, setEditing] = useState<ServiceItem | null>(null);

  const save = useMutation({
    mutationFn: (v: { id?: string; body: Parameters<typeof createService>[0] }) => (v.id ? updateService(v.id, v.body) : createService(v.body)),
    onSuccess: async () => { setEditing(null); await invalidate(); },
  });
  const remove = useMutation({ mutationFn: (id: string) => deleteService(id), onSuccess: invalidate });

  return (
    <div className="grid-2">
      <section className="card stack">
        <h2>Услуги</h2>
        <ProblemAlert error={list.error ?? remove.error} />
        {list.isLoading ? <Loading /> : (list.data ?? []).length === 0 ? <EmptyState title="Услуг нет" /> : (
          <div className="table-wrap"><table>
            <thead><tr><th>Код</th><th>Название</th><th>Префикс</th><th className="num">Слот, мин</th><th className="num">Мест</th><th /></tr></thead>
            <tbody>{(list.data ?? []).map((s) => (
              <tr key={s.id} style={s.active ? undefined : { opacity: 0.6 }}>
                <td>{s.code}</td><td>{s.name}{!s.active && <span className="chip tone-muted" style={{ marginLeft: 8 }}>отключена</span>}</td><td>{s.number_prefix}</td>
                <td className="num">{s.slot_duration_min}</td><td className="num">{s.slot_capacity}</td>
                <td className="row">
                  <button type="button" className="btn btn-sm" onClick={() => setEditing(s)}>Изменить</button>
                  {s.active && <button type="button" className="btn btn-sm btn-danger" disabled={remove.isPending}
                    onClick={() => { if (window.confirm(`Отключить услугу «${s.name}»?`)) remove.mutate(s.id); }}>Отключить</button>}
                </td>
              </tr>
            ))}</tbody>
          </table></div>
        )}
      </section>
      <ServiceForm key={editing?.id ?? 'new'} initial={editing} busy={save.isPending} error={save.error}
        onCancel={() => setEditing(null)} onSubmit={(body) => save.mutate({ id: editing?.id, body })} />
    </div>
  );
}

function ServiceForm({ initial, busy, error, onSubmit, onCancel }: {
  initial: ServiceItem | null; busy: boolean; error: unknown; onSubmit: (b: Parameters<typeof createService>[0]) => void; onCancel: () => void;
}) {
  const [code, setCode] = useState(initial?.code ?? '');
  const [name, setName] = useState(initial?.name ?? '');
  const [prefix, setPrefix] = useState(initial?.number_prefix ?? '');
  const [slot, setSlot] = useState(initial ? String(initial.slot_duration_min) : '');
  const [capacity, setCapacity] = useState(initial ? String(initial.slot_capacity) : '');
  const [active, setActive] = useState(initial?.active ?? true);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    onSubmit({
      ...(initial ? {} : { code: code.trim() }),
      name: name.trim(), number_prefix: prefix.trim(),
      // Пусто — значения по умолчанию берутся из конфигурации отделения.
      ...(slot ? { slot_duration_min: Number(slot) } : {}), ...(capacity ? { slot_capacity: Number(capacity) } : {}),
      ...(initial ? { active } : {}),
    });
  };
  return (
    <form className="card stack" onSubmit={submit}>
      <h2>{initial ? `Услуга «${initial.name}»` : 'Новая услуга'}</h2>
      <div className="form-grid">
        <label className="field">Код (a-z, 0-9, _, -)<input value={code} onChange={(e) => setCode(e.target.value)} disabled={!!initial} required pattern="[a-z0-9_\-]{1,32}" /></label>
        <label className="field">Название<input value={name} onChange={(e) => setName(e.target.value)} required maxLength={120} /></label>
        <label className="field">Префикс номера (1–3)<input value={prefix} onChange={(e) => setPrefix(e.target.value)} required minLength={1} maxLength={3} /></label>
        <label className="field">Слот, мин (по умолчанию из конфигурации)<input type="number" min={1} value={slot} onChange={(e) => setSlot(e.target.value)} /></label>
        <label className="field">Записей на слот<input type="number" min={1} value={capacity} onChange={(e) => setCapacity(e.target.value)} /></label>
        {initial && <label className="check"><input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} /> Активна</label>}
      </div>
      <ProblemAlert error={error} />
      <FieldErrors error={error} />
      <div className="row">
        <button type="submit" className="btn btn-primary" disabled={busy}>{initial ? 'Сохранить' : 'Создать'}</button>
        {initial && <button type="button" className="btn" onClick={onCancel}>Отмена</button>}
      </div>
    </form>
  );
}

// ------------------------------------------------------------------ окна

function Windows({ include }: { include: boolean }) {
  const list = useQuery({ queryKey: ['windows', include], queryFn: () => listWindows(include) });
  const services = useQuery({ queryKey: ['services', false], queryFn: () => listServices(false) });
  const invalidate = useInvalidate('windows', 'queue');
  const [editing, setEditing] = useState<WindowItem | null>(null);

  const save = useMutation({
    mutationFn: async (v: { id?: string; name: string; serviceIds: string[] }) => {
      if (!v.id) return createWindow({ name: v.name, service_ids: v.serviceIds });
      const w = await renameWindow(v.id, v.name);
      return setWindowServices(w.id, v.serviceIds);
    },
    onSuccess: async () => { setEditing(null); await invalidate(); },
  });
  const remove = useMutation({ mutationFn: (id: string) => deleteWindow(id), onSuccess: invalidate });
  const serviceName = (id: string) => (services.data ?? []).find((s) => s.id === id)?.name ?? 'Услуга';

  return (
    <div className="grid-2">
      <section className="card stack">
        <h2>Окна</h2>
        <ProblemAlert error={list.error ?? remove.error} />
        {list.isLoading ? <Loading /> : (list.data ?? []).length === 0 ? <EmptyState title="Окон нет" /> : (
          <div className="table-wrap"><table>
            <thead><tr><th>Окно</th><th>Статус</th><th>Услуги</th><th /></tr></thead>
            <tbody>{(list.data ?? []).map((w) => (
              <tr key={w.id} style={w.active ? undefined : { opacity: 0.6 }}>
                <td>{w.name}{!w.active && <span className="chip tone-muted" style={{ marginLeft: 8 }}>удалено</span>}</td>
                <td><WindowStatusChip status={w.status} /></td>
                <td><div className="pill-list">{w.service_ids.map((id) => <span key={id} className="chip tone-muted">{serviceName(id)}</span>)}</div></td>
                <td className="row">
                  {w.active && <button type="button" className="btn btn-sm" onClick={() => setEditing(w)}>Изменить</button>}
                  {w.active && <button type="button" className="btn btn-sm btn-danger" disabled={remove.isPending}
                    onClick={() => { if (window.confirm(`Удалить окно «${w.name}»?`)) remove.mutate(w.id); }}>Удалить</button>}
                </td>
              </tr>
            ))}</tbody>
          </table></div>
        )}
      </section>
      <WindowForm key={editing?.id ?? 'new'} initial={editing} services={services.data ?? []} busy={save.isPending} error={save.error}
        onCancel={() => setEditing(null)} onSubmit={(name, serviceIds) => save.mutate({ id: editing?.id, name, serviceIds })} />
    </div>
  );
}

function WindowForm({ initial, services, busy, error, onSubmit, onCancel }: {
  initial: WindowItem | null; services: ServiceItem[]; busy: boolean; error: unknown; onSubmit: (name: string, ids: string[]) => void; onCancel: () => void;
}) {
  const [name, setName] = useState(initial?.name ?? '');
  const [ids, setIds] = useState<string[]>(initial?.service_ids ?? []);
  const toggle = (id: string) => setIds((cur) => (cur.includes(id) ? cur.filter((x) => x !== id) : [...cur, id]));
  return (
    <form className="card stack" onSubmit={(e) => { e.preventDefault(); onSubmit(name.trim(), ids); }}>
      <h2>{initial ? `Окно «${initial.name}»` : 'Новое окно'}</h2>
      <label className="field">Название<input value={name} onChange={(e) => setName(e.target.value)} required maxLength={60} /></label>
      <fieldset style={{ border: 0, padding: 0, margin: 0 }}>
        <legend className="muted small">Услуги окна (только активные)</legend>
        {services.map((s) => <label key={s.id} className="check"><input type="checkbox" checked={ids.includes(s.id)} onChange={() => toggle(s.id)} /> {s.name}</label>)}
      </fieldset>
      <ProblemAlert error={error} />
      <FieldErrors error={error} />
      <div className="row">
        <button type="submit" className="btn btn-primary" disabled={busy}>{initial ? 'Сохранить' : 'Создать'}</button>
        {initial && <button type="button" className="btn" onClick={onCancel}>Отмена</button>}
      </div>
    </form>
  );
}

// ------------------------------------------------------------------ сотрудники

function Employees({ include }: { include: boolean }) {
  const list = useQuery({ queryKey: ['employees', include], queryFn: () => listEmployees(include) });
  const invalidate = useInvalidate('employees');
  const [editing, setEditing] = useState<EmployeeItem | null>(null);

  const save = useMutation({
    mutationFn: (v: { id?: string; full_name: string; active: boolean }) =>
      (v.id ? updateEmployee(v.id, { full_name: v.full_name, active: v.active }) : createEmployee({ full_name: v.full_name, active: v.active })),
    onSuccess: async () => { setEditing(null); await invalidate(); },
  });
  const remove = useMutation({ mutationFn: (id: string) => deleteEmployee(id), onSuccess: invalidate });

  return (
    <div className="grid-2">
      <section className="card stack">
        <h2>Сотрудники</h2>
        <ProblemAlert error={list.error ?? remove.error} />
        {list.isLoading ? <Loading /> : (list.data ?? []).length === 0 ? <EmptyState title="Сотрудников нет" /> : (
          <div className="table-wrap"><table>
            <thead><tr><th>ФИО</th><th>Статус</th><th /></tr></thead>
            <tbody>{(list.data ?? []).map((e) => (
              <tr key={e.id} style={e.active ? undefined : { opacity: 0.6 }}>
                <td>{e.full_name}</td><td>{e.active ? 'Работает' : 'Отключён'}</td>
                <td className="row">
                  <button type="button" className="btn btn-sm" onClick={() => setEditing(e)}>Изменить</button>
                  {e.active && <button type="button" className="btn btn-sm btn-danger" disabled={remove.isPending}
                    onClick={() => { if (window.confirm(`Отключить сотрудника «${e.full_name}»?`)) remove.mutate(e.id); }}>Отключить</button>}
                </td>
              </tr>
            ))}</tbody>
          </table></div>
        )}
      </section>
      <EmployeeForm key={editing?.id ?? 'new'} initial={editing} busy={save.isPending} error={save.error}
        onCancel={() => setEditing(null)} onSubmit={(full_name, active) => save.mutate({ id: editing?.id, full_name, active })} />
    </div>
  );
}

function EmployeeForm({ initial, busy, error, onSubmit, onCancel }: {
  initial: EmployeeItem | null; busy: boolean; error: unknown; onSubmit: (name: string, active: boolean) => void; onCancel: () => void;
}) {
  const [name, setName] = useState(initial?.full_name ?? '');
  const [active, setActive] = useState(initial?.active ?? true);
  return (
    <form className="card stack" onSubmit={(e) => { e.preventDefault(); onSubmit(name.trim(), active); }}>
      <h2>{initial ? 'Сотрудник' : 'Новый сотрудник'}</h2>
      <label className="field">ФИО<input value={name} onChange={(e) => setName(e.target.value)} required maxLength={120} /></label>
      {initial && <label className="check"><input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} /> Работает</label>}
      <ProblemAlert error={error} />
      <FieldErrors error={error} />
      <div className="row">
        <button type="submit" className="btn btn-primary" disabled={busy}>{initial ? 'Сохранить' : 'Создать'}</button>
        {initial && <button type="button" className="btn" onClick={onCancel}>Отмена</button>}
      </div>
    </form>
  );
}
