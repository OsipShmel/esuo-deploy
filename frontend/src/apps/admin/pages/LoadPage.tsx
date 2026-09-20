import { useMemo } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { formatDateTime, formatTime } from '@shared/format/time';
import { Bar, EmptyState, Loading, LoadLevelBadge, ProblemAlert } from '@shared/ui';
import {
  getConfig, getLoad, getModel, LOAD_CAUSE_TEXT, PROFILE_SOURCE_TEXT, setLoadProfile, trainModel,
  type DurationModel, type LoadState,
} from '../api';
import { useTz } from '../context';

const DEFAULT_PROFILES: Record<string, string> = { normal: 'Обычный день', weekend: 'Выходной', pension: 'Пенсионные выплаты' };
const tone = (level: string) => (level === 'overload' ? 'danger' : level === 'high' ? 'warn' : 'ok');

function causeText(c: NonNullable<LoadState['causes']>[number], windows?: LoadState['windows']): string {
  const base = LOAD_CAUSE_TEXT[c.code] ?? c.code;
  if (c.code === 'few_windows' && windows) return `${base}: ${windows.open} из ${Math.max(windows.planned, windows.needed)}`;
  if (c.code === 'flow_above_forecast' && c.value) return `${base} (×${c.value.toFixed(2).replace('.', ',')})`;
  return base;
}

export function LoadPage() {
  const tz = useTz();
  const load = useQuery({ queryKey: ['load'], queryFn: getLoad, refetchInterval: 10_000, retry: false });

  if (load.isLoading) return <Loading rows={4} />;
  if (load.error) return <ProblemAlert error={load.error} />;
  const l = load.data;

  return (
    <div className="stack">
      {l ? <State l={l} tz={tz} /> : (
        <EmptyState title="Планирование нагрузки на узле пока не включено">
          Здесь появятся уровень нагрузки, причины, окна, прогноз по часам и выбор профиля дня — как только узел начнёт отвечать на <code>/admin/load</code>.
        </EmptyState>
      )}
      <ModelCard />
    </div>
  );
}

function State({ l, tz }: { l: LoadState; tz: string }) {
  return (
    <>
      <section className="card stack">
        <div className="row">
          <h2 style={{ margin: 0 }}>Нагрузка сейчас</h2>
          <LoadLevelBadge level={l.level} />
          {l.level_since && <span className="muted small">с {formatTime(l.level_since, tz)}</span>}
        </div>
        <div className="tiles">
          <div className="tile"><div className="v">{l.clear_time_min != null ? `${l.clear_time_min} мин` : '—'}</div><div className="l">Время разгрузки очереди</div></div>
          <div className="tile"><div className="v">{l.ratio_now != null ? l.ratio_now.toFixed(2).replace('.', ',') : '—'}</div><div className="l">Загрузка сейчас (1,00 — предел)</div></div>
          <div className="tile">
            <div className="v">{l.windows ? `${l.windows.open} / ${l.windows.needed}` : '—'}</div>
            <div className="l">Окон открыто / нужно{l.windows ? ` (по плану ${l.windows.planned})` : ''}</div>
          </div>
        </div>
        {(l.causes?.length ?? 0) > 0 ? (
          <div>
            <h3>Почему так</h3>
            <ul className="cause-list">{l.causes!.map((c) => <li key={c.code}>{causeText(c, l.windows)}</li>)}</ul>
          </div>
        ) : <p className="muted small">Причин для тревоги нет.</p>}
      </section>

      <div className="grid-2">
        <ProfileCard l={l} />
        <section className="card stack">
          <h2>Модель прогноза</h2>
          {l.duration_model
            ? <p>Версия <strong>{l.duration_model.version}</strong> · основа: {l.duration_model.basis === 'model' ? 'обученная модель' : 'средние по услугам'}.</p>
            : <p className="muted">Узел не сообщает, по какой модели считается прогноз.</p>}
          <p className="muted small">Обновлено {formatDateTime(l.generated_at, tz)}.</p>
        </section>
      </div>

      <Forecast l={l} tz={tz} />
    </>
  );
}

function ProfileCard({ l }: { l: LoadState }) {
  const qc = useQueryClient();
  const config = useQuery({ queryKey: ['config'], queryFn: getConfig, staleTime: 60_000 });
  const profiles = useMemo(() => {
    const planned = (config.data?.effective?.planning as { profiles?: Record<string, { name?: string; closed?: boolean }> } | undefined)?.profiles;
    if (!planned) return DEFAULT_PROFILES;
    return Object.fromEntries(Object.entries(planned).map(([code, p]) => [code, p.name ?? code]));
  }, [config.data]);

  const set = useMutation({
    mutationFn: (code: string | null) => setLoadProfile(code),
    onSuccess: (data) => qc.setQueryData(['load'], data),
  });
  const current = l.profile;

  return (
    <section className="card stack">
      <h2>Профиль дня</h2>
      {current
        ? <p><strong>{current.name}</strong> <span className="muted">· {PROFILE_SOURCE_TEXT[current.source] ?? current.source}</span></p>
        : <p className="muted">Профиль не определён.</p>}
      <label className="field">Профиль на сегодня
        <select value={current?.source === 'manual' ? current.code : ''} disabled={set.isPending}
          onChange={(e) => set.mutate(e.target.value || null)}>
          <option value="">По календарю</option>
          {Object.entries(profiles).map(([code, name]) => <option key={code} value={code}>{name}</option>)}
        </select>
      </label>
      <p className="muted small">Ручной выбор действует до конца дня. Нужен, если, например, выплаты пришли не в тот день, что в календаре.</p>
      <ProblemAlert error={set.error} />
    </section>
  );
}

function Forecast({ l, tz }: { l: LoadState; tz: string }) {
  const rows = l.forecast ?? [];
  const nowHour = Date.now();
  return (
    <section className="card stack">
      <h2>Работа и мощность по часам</h2>
      {rows.length === 0 ? <p className="muted">Прогноз на сегодня пока не рассчитан.</p> : (
        <>
          <div className="hours-chart" role="table" aria-label="Прогноз по часам">
            <div className="hours-head" role="row">
              <span>Час</span><span>Работа к мощности</span><span className="num">Работа, мин</span><span className="num">Мощность, мин</span><span>Уровень</span><span className="num">Записи</span><span>Квота записи</span>
            </div>
            {rows.map((f) => {
              const current = new Date(f.hour_start).getTime() <= nowHour && nowHour < new Date(f.hour_start).getTime() + 3_600_000;
              return (
                <div key={f.hour_start} className={`hours-row${current ? ' current' : ''}`} role="row">
                  <span><strong>{formatTime(f.hour_start, tz)}</strong>{current && <span className="muted small"> сейчас</span>}</span>
                  <span className="row"><span style={{ flex: 1 }}><Bar ratio={Math.min(1, f.ratio / 2)} tone={tone(f.level)} /></span><span className="ratio">{f.ratio.toFixed(2).replace('.', ',')}</span></span>
                  <span className="num">{Math.round(f.work_min)}</span>
                  <span className="num">{Math.round(f.capacity_min)}</span>
                  <span><LoadLevelBadge level={f.level} /></span>
                  <span className="num">{f.booked}</span>
                  <span>{f.booking_quota == null ? '—' : f.booking_quota <= 0 ? 'закрыта' : f.booking_quota < 1 ? `${Math.round(f.booking_quota * 100)}%` : 'без ограничений'}</span>
                </div>
              );
            })}
          </div>
          <p className="muted small">Полоса заполнена наполовину — работа равна мощности (1,00). Выше 1,00 очередь не успевает разгружаться.</p>
        </>
      )}
    </section>
  );
}

const METRIC_LABEL: Record<string, string> = {
  mae_sec: 'Ошибка модели (MAE), с', mae: 'Ошибка модели (MAE), с',
  baseline_const_mae_sec: 'Константа 300 с, MAE', baseline_service_mae_sec: 'Среднее по услуге, MAE', baseline_cascade_mae_sec: 'Каскад handling_stats, MAE',
  p90_coverage: 'Покрытие p90', observations: 'Наблюдений', dropped_outliers: 'Отброшено выбросов',
};

function metricValue(v: unknown): string {
  if (typeof v === 'number') return Number.isInteger(v) ? String(v) : v.toFixed(2).replace('.', ',');
  if (v === null || v === undefined) return '—';
  return typeof v === 'object' ? JSON.stringify(v) : String(v);
}

function ModelCard() {
  const tz = useTz();
  const qc = useQueryClient();
  const model = useQuery({ queryKey: ['model'], queryFn: getModel, retry: false });
  const train = useMutation({ mutationFn: trainModel, onSuccess: (data) => qc.setQueryData(['model'], data) });

  if (model.isLoading) return <Loading rows={2} />;
  const m: DurationModel | null | undefined = model.data;
  return (
    <section className="card stack">
      <div className="row">
        <h2 style={{ margin: 0 }}>Модель длительности обслуживания</h2>
        <span className="grow" />
        {m && <button type="button" className="btn" disabled={train.isPending} onClick={() => train.mutate()}>{train.isPending ? 'Обучаем…' : 'Переобучить'}</button>}
      </div>
      <ProblemAlert error={model.error ?? train.error} />
      {!m ? <p className="muted">Модель длительности на узле пока не включена: ожидание считается по средним значениям услуг.</p> : (
        <>
          <p>Версия <strong>{m.version}</strong>{m.status && <> · статус: {m.status}</>}{m.trained_at && <> · обучена {formatDateTime(m.trained_at, tz)}</>}{m.observations != null && <> · наблюдений: {m.observations}</>}</p>
          {m.metrics && Object.keys(m.metrics).length > 0 && (
            <div className="table-wrap"><table>
              <thead><tr><th>Показатель</th><th className="num">Значение</th></tr></thead>
              <tbody>{Object.entries(m.metrics).map(([k, v]) => <tr key={k}><td>{METRIC_LABEL[k] ?? k}</td><td className="num">{metricValue(v)}</td></tr>)}</tbody>
            </table></div>
          )}
          <p className="muted small">Метрики посчитаны на контрольных днях, не участвовавших в обучении.</p>
        </>
      )}
    </section>
  );
}
