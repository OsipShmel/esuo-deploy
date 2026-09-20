import { useEffect, useState } from 'react';
import { ProblemAlert } from '@shared/ui';
import type { Section } from '../../api';
import { deleteIn, getIn, isEmpty, QUEUE_FIELDS, setIn, type FieldDef } from '../../configModel';

/**
 * Параметры очереди. Глобально — полный набор значений. У отделения — только переопределения: пустое поле наследует
 * глобальное значение (оно показано подсказкой), «вернуть к глобальному» удаляет переопределение.
 */
export function QueuePanel({ scope, section, inherited, busy, error, onSave }: {
  scope: string; section: Section | null; inherited: Section | null; busy: boolean; error: unknown; onSave: (next: Section | null) => void;
}) {
  const [draft, setDraft] = useState<Section>(section ?? {});
  useEffect(() => setDraft(section ?? {}), [section, scope]);
  const isBranch = scope !== 'global';
  const dirty = JSON.stringify(draft) !== JSON.stringify(section ?? {});

  const set = (f: FieldDef, raw: string) => {
    if (raw === '') { setDraft(deleteIn(draft, f.path)); return; }
    const value = f.kind === 'int' ? Number(raw) : f.kind === 'decimal' ? Number(raw) : f.kind === 'bool' ? raw === 'true' : raw;
    setDraft(setIn(draft, f.path, value));
  };

  return (
    <section className="card stack" aria-label="Параметры очереди">
      <h2>{isBranch ? `Переопределения отделения ${scope}` : 'Параметры очереди (глобальные)'}</h2>
      {isBranch
        ? <p className="muted small">Заполненное поле заменяет глобальное значение только для этого отделения. Пустое — наследует глобальное.</p>
        : <p className="muted small">Пустое поле — центр это значение не задаёт: отделение использует своё значение по умолчанию (из файла настроек узла). Заполните поле, чтобы задать его для всей сети.</p>}
      <div className="form-grid">
        {QUEUE_FIELDS.map((f) => {
          const own = getIn(draft, f.path);
          const inh = isBranch ? getIn(inherited, f.path) : undefined;
          const shown = own === undefined ? '' : String(own);
          return (
            <div key={f.path.join('.')} className="stack" style={{ gap: 4 }}>
              <label className="field">{f.label}{f.unit ? `, ${f.unit}` : ''}
                {f.kind === 'enum' || f.kind === 'bool' ? (
                  <select value={shown} onChange={(e) => set(f, e.target.value)}>
                    <option value="">{isBranch ? `по умолчанию${inh !== undefined ? ` (${String(inh)})` : ''}` : 'не задано'}</option>
                    {f.kind === 'bool'
                      ? [<option key="t" value="true">Да</option>, <option key="f" value="false">Нет</option>]
                      : f.options?.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
                  </select>
                ) : (
                  <input type="number" inputMode="decimal" step={f.kind === 'decimal' ? 0.1 : 1} min={0} value={shown}
                    placeholder={inh !== undefined ? String(inh) : isBranch ? '' : 'по умолч. узла'} onChange={(e) => set(f, e.target.value)} />
                )}
              </label>
              {isBranch && own !== undefined && (
                <button type="button" className="btn btn-sm" onClick={() => setDraft(deleteIn(draft, f.path))}>Вернуть к глобальному{inh !== undefined ? ` (${String(inh)})` : ''}</button>
              )}
            </div>
          );
        })}
      </div>
      <ProblemAlert error={error} />
      <div className="row">
        <button type="button" className="btn btn-primary" disabled={!dirty || busy} onClick={() => onSave(isBranch && isEmpty(draft) ? null : draft)}>Опубликовать</button>
        <button type="button" className="btn" disabled={!dirty} onClick={() => setDraft(section ?? {})}>Отменить изменения</button>
      </div>
    </section>
  );
}
