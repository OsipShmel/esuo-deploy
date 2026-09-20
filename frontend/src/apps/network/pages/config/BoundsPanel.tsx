import { useEffect, useState } from 'react';
import { ProblemAlert } from '@shared/ui';
import type { Section } from '../../api';
import { BOUNDABLE, isEmpty } from '../../configModel';

interface Range { min: string; max: string }

const toDraft = (s: Section | null): Record<string, Range> =>
  Object.fromEntries(Object.entries(s ?? {}).map(([k, v]) => {
    const r = v as { min?: number; max?: number };
    return [k, { min: String(r.min ?? ''), max: String(r.max ?? '') }];
  }));

/** Границы локального переопределения (bounds): что руководитель отделения может менять сам. Пусто = менять нельзя. */
export function BoundsPanel({ scope, section, busy, error, onSave }: {
  scope: string; section: Section | null; busy: boolean; error: unknown; onSave: (next: Section | null) => void;
}) {
  const [draft, setDraft] = useState<Record<string, Range>>(toDraft(section));
  useEffect(() => setDraft(toDraft(section)), [section, scope]);

  const problems = (r: Range | undefined): string | null => {
    if (!r || (r.min === '' && r.max === '')) return null;
    if (r.min === '' || r.max === '') return 'Задайте обе границы';
    if (Number(r.min) > Number(r.max)) return 'Минимум больше максимума';
    return null;
  };
  const hasErrors = BOUNDABLE.some((b) => problems(draft[b.key]));

  const build = (): Section | null => {
    const out: Section = {};
    for (const b of BOUNDABLE) {
      const r = draft[b.key];
      if (r && r.min !== '' && r.max !== '') out[b.key] = { min: Number(r.min), max: Number(r.max) };
    }
    return isEmpty(out) && scope !== 'global' ? null : out;
  };
  const dirty = JSON.stringify(build() ?? {}) !== JSON.stringify(section ?? {});

  const update = (key: string, part: keyof Range, value: string) => setDraft({ ...draft, [key]: { min: draft[key]?.min ?? '', max: draft[key]?.max ?? '', [part]: value } });

  return (
    <section className="card stack" aria-label="Границы">
      <h2>{scope === 'global' ? 'Границы для отделений' : `Границы отделения ${scope}`}</h2>
      <p className="muted small">Руководитель отделения может менять только параметры, для которых заданы границы «от — до».</p>
      <div className="table-wrap"><table>
        <thead><tr><th>Параметр</th><th>От</th><th>До</th></tr></thead>
        <tbody>{BOUNDABLE.map((b) => {
          const r = draft[b.key];
          const err = problems(r);
          return (
            <tr key={b.key}>
              <td>{b.label}{err && <div className="field-error" role="alert">{err}</div>}</td>
              <td><input type="number" min={0} value={r?.min ?? ''} onChange={(e) => update(b.key, 'min', e.target.value)} style={{ width: 100 }} aria-label={`${b.label}: от`} /></td>
              <td><input type="number" min={0} value={r?.max ?? ''} onChange={(e) => update(b.key, 'max', e.target.value)} style={{ width: 100 }} aria-label={`${b.label}: до`} /></td>
            </tr>
          );
        })}</tbody>
      </table></div>
      <ProblemAlert error={error} />
      <div className="row">
        <button type="button" className="btn btn-primary" disabled={!dirty || hasErrors || busy} onClick={() => onSave(build())}>Опубликовать</button>
        <button type="button" className="btn" disabled={!dirty} onClick={() => setDraft(toDraft(section))}>Отменить изменения</button>
      </div>
    </section>
  );
}
