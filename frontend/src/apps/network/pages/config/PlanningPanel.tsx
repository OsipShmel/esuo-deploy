import { useEffect, useMemo, useState } from 'react';
import { ProblemAlert } from '@shared/ui';
import type { Section } from '../../api';
import { isEmpty } from '../../configModel';

type Profile = Section;
const DAYS: [string, string][] = [['mon', 'Пн'], ['tue', 'Вт'], ['wed', 'Ср'], ['thu', 'Чт'], ['fri', 'Пт'], ['sat', 'Сб'], ['sun', 'Вс']];
const CATEGORIES: [string, string][] = [['default', 'обычные'], ['pensioner', 'пенсионеры']];
const LEVELS: [string, string][] = [['normal', 'нормально'], ['high', 'высокая'], ['overload', 'перегрузка']];
const THRESHOLDS: [string, string][] = [
  ['high_ratio', 'Порог «высокая»: загрузка'], ['overload_ratio', 'Порог «перегрузка»: загрузка'],
  ['high_clear_min', 'Разгрузка для «высокая», мин'], ['overload_clear_min', 'Разгрузка для «перегрузка», мин'],
  ['down_factor', 'Спуск уровня: доля порога'], ['down_hold_min', 'Спуск уровня: держится, мин'], ['up_hold_min', 'Подъём уровня: держится, мин'],
];

const obj = (v: unknown): Section => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Section) : {});
const num = (v: unknown): string => (typeof v === 'number' ? String(v) : typeof v === 'string' ? v : '');
const hourOf = (t: unknown): number => Number(String(t ?? '00:00').slice(0, 2));
const pad = (h: number) => String(h).padStart(2, '0');

/** Профили дня, календарь и пороги нагрузки (`planning`, load-planning.md 3.1). У отделения переопределяется только объём потока. */
export function PlanningPanel({ scope, section, serviceCodes, busy, error, onSave }: {
  scope: string; section: Section | null; serviceCodes: string[]; busy: boolean; error: unknown; onSave: (next: Section | null) => void;
}) {
  const [draft, setDraft] = useState<Section>(section ?? {});
  useEffect(() => setDraft(section ?? {}), [section, scope]);
  const isBranch = scope !== 'global';
  const dirty = JSON.stringify(draft) !== JSON.stringify(section ?? {});
  const profiles = obj(draft.profiles);
  const profileCodes = Object.keys(profiles);
  const patch = (p: Section) => setDraft({ ...draft, ...p });
  const setProfile = (code: string, p: Section) => patch({ profiles: { ...profiles, [code]: { ...obj(profiles[code]), ...p } } });

  const errors = useMemo(() => (isBranch ? [] : validate(draft)), [draft, isBranch]);

  if (isBranch) {
    return (
      <section className="card stack" aria-label="Нагрузка отделения">
        <h2>Поток клиентов отделения {scope}</h2>
        <p className="muted small">Профили дня общие для сети (глобальная конфигурация). Здесь задаётся только объём потока этого отделения.</p>
        <div className="form-grid">
          <label className="field">Клиентов за обычный день
            <input type="number" min={0} value={num(draft.base_arrivals_per_day)} onChange={(e) => setDraft(e.target.value === '' ? omit(draft, 'base_arrivals_per_day') : { ...draft, base_arrivals_per_day: Number(e.target.value) })} />
          </label>
        </div>
        <ProblemAlert error={error} />
        <div className="row">
          <button type="button" className="btn btn-primary" disabled={!dirty || busy} onClick={() => onSave(isEmpty(draft) ? null : draft)}>Опубликовать</button>
          <button type="button" className="btn" disabled={!dirty} onClick={() => setDraft(section ?? {})}>Отменить изменения</button>
        </div>
      </section>
    );
  }

  const cal = Array.isArray(draft.calendar) ? (draft.calendar as { date: string; profile: string }[]) : [];
  const weekdays = obj(draft.weekdays);
  const thresholds = obj(draft.thresholds);
  const levers = obj(draft.levers);
  const quota = obj(levers.booking_quota);

  return (
    <section className="card stack" aria-label="Профили нагрузки">
      <h2>Профили нагрузки (глобальные)</h2>
      <div className="form-grid">
        <label className="field">Клиентов за обычный день (по умолчанию для отделений)
          <input type="number" min={0} value={num(draft.base_arrivals_per_day)} onChange={(e) => patch({ base_arrivals_per_day: Number(e.target.value) })} /></label>
        <label className="field">Целевая загрузка окна (0–1)
          <input type="number" min={0.1} max={1} step={0.05} value={num(draft.utilization_target)} onChange={(e) => patch({ utilization_target: Number(e.target.value) })} /></label>
      </div>

      <h3>Дни недели</h3>
      <div className="form-grid">
        {DAYS.map(([k, l]) => (
          <label key={k} className="field">{l}
            <select value={String(weekdays[k] ?? '')} onChange={(e) => patch({ weekdays: { ...weekdays, [k]: e.target.value } })}>
              <option value="">по умолчанию (normal)</option>{profileCodes.map((c) => <option key={c} value={c}>{String(obj(profiles[c]).name ?? c)}</option>)}
            </select>
          </label>
        ))}
      </div>

      <h3>Календарь</h3>
      {cal.map((row, i) => (
        <div key={i} className="row">
          <input type="date" value={row.date} onChange={(e) => patch({ calendar: cal.map((r, j) => (j === i ? { ...r, date: e.target.value } : r)) })} aria-label="Дата" />
          <select value={row.profile} onChange={(e) => patch({ calendar: cal.map((r, j) => (j === i ? { ...r, profile: e.target.value } : r)) })} aria-label="Профиль">
            {profileCodes.map((c) => <option key={c} value={c}>{String(obj(profiles[c]).name ?? c)}</option>)}
          </select>
          <button type="button" className="btn btn-sm btn-danger" onClick={() => patch({ calendar: cal.filter((_, j) => j !== i) })}>Убрать</button>
        </div>
      ))}
      <div><button type="button" className="btn btn-sm" onClick={() => patch({ calendar: [...cal, { date: '', profile: profileCodes[0] ?? 'normal' }] })}>Добавить дату</button></div>

      <h3>Профили дня</h3>
      {profileCodes.map((code) => (
        <ProfileEditor key={code} code={code} profile={obj(profiles[code])} serviceCodes={serviceCodes}
          onChange={(p) => setProfile(code, p)} onRemove={() => patch({ profiles: omit(profiles, code) })} />
      ))}
      <AddProfile existing={profileCodes} onAdd={(code) => patch({ profiles: { ...profiles, [code]: newProfile() } })} />

      <h3>Пороги уровня нагрузки</h3>
      <div className="form-grid">
        {THRESHOLDS.map(([k, l]) => (
          <label key={k} className="field">{l}<input type="number" step="any" min={0} value={num(thresholds[k])} onChange={(e) => patch({ thresholds: { ...thresholds, [k]: Number(e.target.value) } })} /></label>
        ))}
      </div>

      <h3>Рычаги</h3>
      <div className="form-grid">
        {LEVELS.map(([k, l]) => (
          <label key={k} className="field">Квота записи при уровне «{l}» (0–1)
            <input type="number" min={0} max={1} step={0.1} value={num(quota[k])} onChange={(e) => patch({ levers: { ...levers, booking_quota: { ...quota, [k]: Number(e.target.value) } } })} /></label>
        ))}
        <label className="field">Предупреждать клиентов в зале, начиная с уровня
          <select value={String(levers.warn_walkin_from ?? 'high')} onChange={(e) => patch({ levers: { ...levers, warn_walkin_from: e.target.value } })}>
            {LEVELS.map(([k, l]) => <option key={k} value={k}>{l}</option>)}</select></label>
      </div>

      {errors.length > 0 && <ul className="field-error" role="alert" style={{ margin: 0, paddingLeft: 20 }}>{errors.map((e) => <li key={e}>{e}</li>)}</ul>}
      <ProblemAlert error={error} />
      <div className="row">
        <button type="button" className="btn btn-primary" disabled={!dirty || busy || errors.length > 0} onClick={() => onSave(draft)}>Опубликовать</button>
        <button type="button" className="btn" disabled={!dirty} onClick={() => setDraft(section ?? {})}>Отменить изменения</button>
      </div>
    </section>
  );
}

// ------------------------------------------------------------------ профиль

function ProfileEditor({ code, profile, serviceCodes, onChange, onRemove }: {
  code: string; profile: Profile; serviceCodes: string[]; onChange: (p: Section) => void; onRemove: () => void;
}) {
  const closed = profile.closed === true;
  const from = hourOf(profile.opening_time ?? '09:00');
  const to = hourOf(profile.closing_time ?? '18:00');
  const curve = obj(profile.hourly_curve);
  const hours = Array.from({ length: Math.max(0, to - from) }, (_, i) => pad(from + i));
  const maxW = Math.max(1, ...hours.map((h) => Number(curve[h] ?? 0)));
  const serviceMix = obj(profile.service_mix);
  const categoryMix = obj(profile.category_mix);
  const windows = obj(profile.planned_windows);
  const winRows = Object.entries(windows).sort(([a], [b]) => a.localeCompare(b));
  const services = [...new Set([...serviceCodes, ...Object.keys(serviceMix)])];

  return (
    <div className="profile">
      <div className="row between">
        <strong>{String(profile.name ?? code)} <span className="muted small">({code})</span></strong>
        <button type="button" className="btn btn-sm btn-danger" onClick={onRemove}>Удалить профиль</button>
      </div>
      <div className="form-grid">
        <label className="field">Название<input value={String(profile.name ?? '')} onChange={(e) => onChange({ name: e.target.value })} /></label>
        <label className="check"><input type="checkbox" checked={closed} onChange={(e) => onChange({ closed: e.target.checked })} /> Отделение закрыто</label>
      </div>
      {!closed && (
        <>
          <div className="form-grid">
            <label className="field">Множитель потока<input type="number" step="0.05" min={0} value={num(profile.flow_multiplier)} onChange={(e) => onChange({ flow_multiplier: Number(e.target.value) })} /></label>
            <label className="field">Открытие<input type="time" value={String(profile.opening_time ?? '')} onChange={(e) => onChange({ opening_time: e.target.value })} /></label>
            <label className="field">Закрытие<input type="time" value={String(profile.closing_time ?? '')} onChange={(e) => onChange({ closing_time: e.target.value })} /></label>
          </div>

          <div className="bars" role="group" aria-label="Кривая потока по часам">
            <strong className="small">Поток по часам (вес)</strong>
            {hours.map((h) => (
              <div key={h} className="bar-row">
                <span>{h}:00</span>
                <div className="bar"><span style={{ width: `${Math.round((Number(curve[h] ?? 0) / maxW) * 100)}%` }} /></div>
                <input type="number" min={0} step="0.1" value={num(curve[h])} aria-label={`Вес часа ${h}`}
                  onChange={(e) => onChange({ hourly_curve: { ...curve, [h]: Number(e.target.value) } })} />
              </div>
            ))}
          </div>

          <div className="grid-2">
            <Mix title="Услуги (доли)" entries={services.map((k) => [k, k])} mix={serviceMix} onChange={(m) => onChange({ service_mix: m })} />
            <Mix title="Категории клиентов (доли)" entries={CATEGORIES} mix={categoryMix} onChange={(m) => onChange({ category_mix: m })} />
          </div>

          <div className="stack">
            <strong className="small">Окна по плану (с указанного часа до следующей строки)</strong>
            {winRows.map(([h, n]) => (
              <div key={h} className="row">
                <input type="number" min={0} max={23} value={Number(h)} style={{ width: 90 }} aria-label="С часа"
                  onChange={(e) => { const next = { ...windows }; delete next[h]; next[pad(Number(e.target.value))] = n; onChange({ planned_windows: next }); }} />
                <span>ч →</span>
                <input type="number" min={0} value={num(n)} style={{ width: 90 }} aria-label="Окон"
                  onChange={(e) => onChange({ planned_windows: { ...windows, [h]: Number(e.target.value) } })} />
                <span>окон</span>
                <button type="button" className="btn btn-sm btn-danger" onClick={() => onChange({ planned_windows: omit(windows, h) })}>Убрать</button>
              </div>
            ))}
            <div><button type="button" className="btn btn-sm" onClick={() => onChange({ planned_windows: { ...windows, [pad(winRows.length ? Number(winRows[winRows.length - 1]![0]) + 1 : from)]: 1 } })}>Добавить строку</button></div>
          </div>
        </>
      )}
    </div>
  );
}

function Mix({ title, entries, mix, onChange }: { title: string; entries: [string, string][]; mix: Section; onChange: (m: Section) => void }) {
  const total = entries.reduce((a, [k]) => a + Number(mix[k] ?? 0), 0);
  return (
    <div className="stack">
      <strong className="small">{title}</strong>
      {entries.map(([k, l]) => (
        <label key={k} className="row" style={{ justifyContent: 'space-between' }}>
          <span>{l}</span>
          <span className="row" style={{ gap: 8 }}>
            <input type="number" min={0} step="0.01" style={{ width: 100 }} value={num(mix[k])} onChange={(e) => onChange({ ...mix, [k]: Number(e.target.value) })} aria-label={l} />
            <span className="muted small" style={{ width: 44 }}>{total > 0 ? `${Math.round((Number(mix[k] ?? 0) / total) * 100)}%` : '—'}</span>
          </span>
        </label>
      ))}
    </div>
  );
}

function AddProfile({ existing, onAdd }: { existing: string[]; onAdd: (code: string) => void }) {
  const [code, setCode] = useState('');
  const valid = /^[a-z][a-z0-9_]{1,31}$/.test(code) && !existing.includes(code);
  return (
    <div className="row">
      <input value={code} onChange={(e) => setCode(e.target.value.trim())} placeholder="код профиля, например holiday" aria-label="Код нового профиля" />
      <button type="button" className="btn btn-sm" disabled={!valid} onClick={() => { onAdd(code); setCode(''); }}>Добавить профиль</button>
    </div>
  );
}

const newProfile = (): Profile => ({
  name: 'Новый профиль', flow_multiplier: 1, opening_time: '09:00', closing_time: '18:00',
  hourly_curve: Object.fromEntries(Array.from({ length: 9 }, (_, i) => [pad(9 + i), 1])),
  service_mix: {}, category_mix: { default: 1 }, planned_windows: { '09': 2 },
});

function omit(o: Section, key: string): Section {
  const next = { ...o };
  delete next[key];
  return next;
}

/** Проверки, которые центр не делает: доли и кривая не пустые, часы работы корректны, даты календаря заполнены. */
function validate(draft: Section): string[] {
  const errors: string[] = [];
  const profiles = obj(draft.profiles);
  for (const [code, raw] of Object.entries(profiles)) {
    const p = obj(raw);
    if (p.closed === true) continue;
    const name = String(p.name ?? code);
    if (hourOf(p.opening_time) >= hourOf(p.closing_time)) errors.push(`«${name}»: открытие должно быть раньше закрытия`);
    const sum = (o: unknown) => Object.values(obj(o)).reduce<number>((a, v) => a + Number(v ?? 0), 0);
    if (sum(p.hourly_curve) <= 0) errors.push(`«${name}»: кривая потока по часам пустая`);
    if (sum(p.service_mix) <= 0) errors.push(`«${name}»: доли услуг пустые (сумма должна быть больше 0)`);
    if (sum(p.category_mix) <= 0) errors.push(`«${name}»: доли категорий пустые (сумма должна быть больше 0)`);
  }
  const cal = Array.isArray(draft.calendar) ? (draft.calendar as { date: string }[]) : [];
  if (cal.some((r) => !r.date)) errors.push('В календаре есть строка без даты');
  return errors;
}
