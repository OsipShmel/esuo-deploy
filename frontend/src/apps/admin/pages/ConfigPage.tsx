import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError } from '@shared/api/http';
import { EmptyState, Loading, ProblemAlert } from '@shared/ui';
import { getConfig, putConfig, type BranchConfig, type ConfigValue } from '../api';

/** Человеческие названия параметров; неизвестные ключи показываются как есть. */
const TITLES: Record<string, string> = {
  slack_min: 'Окно близких дедлайнов, мин',
  call_timeout_sec: 'Время подойти к окну, сек',
  target_wait_min: 'Целевое ожидание, мин',
  early_checkin_min: 'Допуск записи раньше слота, мин',
  grace_late_min: 'Опоздание без потери приоритета, мин',
};
const SOURCE: Record<string, string> = { qr: 'QR', walkin: 'Живая очередь', appointment: 'Запись' };
const CATEGORY: Record<string, string> = { default: 'обычный', pensioner: 'пенсионер', special: 'спец-категория' };

interface Leaf { path: string[]; central: number | null; effective: number | null }

const get = (obj: unknown, path: string[]): unknown =>
  path.reduce<unknown>((o, k) => (o && typeof o === 'object' ? (o as Record<string, unknown>)[k] : undefined), obj);
const num = (v: unknown): number | null => (typeof v === 'number' ? v : null);

/** Листья числового значения по ключу с границами: вложенные (`target_wait_min` → источник → категория) разворачиваются. */
function leaves(root: string, cfg: BranchConfig): Leaf[] {
  const walk = (path: string[]): Leaf[] => {
    const eff = get(cfg.effective, path);
    if (eff && typeof eff === 'object') return Object.keys(eff).flatMap((k) => walk([...path, k]));
    return typeof eff === 'number' ? [{ path, central: num(get(cfg.central, path)), effective: eff }] : [];
  };
  return walk([root]);
}

function toNested(entries: [string[], number][]): Record<string, ConfigValue> {
  const out: Record<string, ConfigValue> = {};
  for (const [path, value] of entries) {
    let cur = out;
    path.slice(0, -1).forEach((k) => {
      const next = (cur[k] ?? {}) as Record<string, ConfigValue>;
      cur[k] = next;
      cur = next;
    });
    cur[path[path.length - 1]!] = value;
  }
  return out;
}

export function ConfigPage() {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['config'], queryFn: getConfig });
  const [values, setValues] = useState<Record<string, string>>({});
  const [saved, setSaved] = useState(false);

  const cfg = q.data;
  const allLeaves = useMemo(() => (cfg ? Object.keys(cfg.bounds).flatMap((k) => leaves(k, cfg)) : []), [cfg]);

  useEffect(() => {
    if (!cfg) return;
    setValues(Object.fromEntries(allLeaves.map((l) => [l.path.join('.'), String(l.effective ?? '')])));
  }, [cfg, allLeaves]);

  const save = useMutation({
    mutationFn: (overrides: Record<string, ConfigValue>) => putConfig(overrides),
    onSuccess: (data) => { qc.setQueryData(['config'], data); setSaved(true); },
  });

  if (q.isLoading) return <Loading rows={4} />;
  if (!cfg) return <ProblemAlert error={q.error} />;
  if (Object.keys(cfg.bounds).length === 0) {
    return <EmptyState title="Центр не разрешил локальные изменения">Границы для параметров не заданы — настройки менять нельзя.</EmptyState>;
  }

  const boundFor = (path: string[]) => cfg.bounds[path[0]!]!;
  const errorFor = (l: Leaf): string | null => {
    const raw = values[l.path.join('.')] ?? '';
    const v = Number(raw);
    const b = boundFor(l.path);
    if (raw.trim() === '' || Number.isNaN(v)) return 'Введите число';
    if (!Number.isInteger(v)) return 'Нужно целое число';
    if (v < b.min || v > b.max) return `От ${b.min} до ${b.max}`;
    return null;
  };
  const hasErrors = allLeaves.some((l) => errorFor(l));

  // Локальными считаются только значения, отличающиеся от центральных: PUT заменяет переопределения целиком.
  const overrides = allLeaves
    .filter((l) => !errorFor(l) && Number(values[l.path.join('.')]) !== l.central)
    .map((l): [string[], number] => [l.path, Number(values[l.path.join('.')])]);

  const title = (l: Leaf) => {
    const [root, ...rest] = l.path;
    const base = TITLES[root!] ?? root!;
    return rest.length ? `${base}: ${rest.map((k, i) => (i === 0 ? SOURCE[k] : CATEGORY[k]) ?? k).join(', ')}` : base;
  };

  const backendFieldError = save.error instanceof ApiError && save.error.code === 'CONFIG_OUT_OF_BOUNDS' ? save.error.detail : null;

  return (
    <section className="card stack">
      <div className="row between">
        <h2 style={{ margin: 0 }}>Настройки очереди в границах центра</h2>
        <span className="muted small">Версия центра {cfg.central_version} · действующая {cfg.effective_version}</span>
      </div>
      <p className="muted">Менять можно только параметры, для которых центр задал границы. Остальные правила очереди задаёт центр.</p>
      <div className="table-wrap"><table>
        <thead><tr><th>Параметр</th><th>Центр</th><th>Локально</th><th>Допустимо</th><th /></tr></thead>
        <tbody>{allLeaves.map((l) => {
          const key = l.path.join('.');
          const err = errorFor(l);
          const b = boundFor(l.path);
          const isLocal = Number(values[key]) !== l.central;
          return (
            <tr key={key}>
              <td>{title(l)}{isLocal && !err && <span className="chip tone-accent" style={{ marginLeft: 8 }}>изменено</span>}</td>
              <td>{l.central ?? '—'}</td>
              <td>
                <input type="number" inputMode="numeric" step={1} min={b.min} max={b.max} style={{ width: 110 }} value={values[key] ?? ''}
                  aria-label={title(l)} aria-invalid={!!err}
                  onChange={(e) => { setSaved(false); setValues({ ...values, [key]: e.target.value }); }} />
                {err && <div className="field-error" role="alert">{err}</div>}
              </td>
              <td className="muted">от {b.min} до {b.max}</td>
              <td><button type="button" className="btn btn-sm" disabled={!isLocal}
                onClick={() => { setSaved(false); setValues({ ...values, [key]: String(l.central ?? '') }); }}>Сбросить к центральному</button></td>
            </tr>
          );
        })}</tbody>
      </table></div>
      {backendFieldError ? <div className="banner tone-danger" role="alert">{backendFieldError}</div> : <ProblemAlert error={save.error} />}
      {saved && <div className="banner tone-ok" role="status">Сохранено. Действующая версия {cfg.effective_version}.</div>}
      <div className="row">
        <button type="button" className="btn btn-primary" disabled={hasErrors || save.isPending}
          onClick={() => save.mutate(toNested(overrides))}>Сохранить</button>
        <button type="button" className="btn" disabled={save.isPending || Object.keys(cfg.local_overrides).length === 0}
          onClick={() => save.mutate({})}>Снять все локальные изменения</button>
      </div>
    </section>
  );
}
