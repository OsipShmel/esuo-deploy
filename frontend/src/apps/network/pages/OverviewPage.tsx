import { useQuery } from '@tanstack/react-query';
import { formatSeconds } from '@shared/format/duration';
import { formatDateTime } from '@shared/format/time';
import { EmptyState, Icon, Loading, LoadLevelBadge, ProblemAlert } from '@shared/ui';
import { getOverview, type BranchCard } from '../api';

/** Тексты аномалий (постановка network, вкладка «Сеть»). */
const ANOMALY: Record<string, (b: BranchCard) => string> = {
  offline: (b) => `${b.id}: нет связи с отделением`,
  wait_grows_without_tickets: (b) => `${b.id}: ожидание растёт, хотя клиентов мало — проверьте окна`,
  overdue_tickets: (b) => `${b.id}: ${b.overdue} просроченных талонов`,
  no_open_windows: (b) => `${b.id}: клиенты ждут, а окна закрыты`,
  outbox_lag: (b) => `${b.id}: события задерживаются в отделении`,
  config_pending: (b) => `${b.id}: конфигурация v${b.config_published_version ?? '?'} ещё не применена`,
  overload: (b) => `${b.id}: перегрузка дольше 15 минут`,
};
const DANGER = new Set(['offline', 'no_open_windows', 'overload']);

/** Опрос раз в 5 секунд: у центра для этого экрана нет живого потока (постановка network). */
export function OverviewPage() {
  const q = useQuery({ queryKey: ['overview'], queryFn: getOverview, refetchInterval: 5_000 });
  if (q.isLoading) return <Loading rows={4} />;
  if (!q.data) return <ProblemAlert error={q.error} />;
  const o = q.data;
  const anomalies = o.branches.flatMap((b) => b.anomalies.map((code) => ({ b, code })));

  return (
    <div className="stack">
      <ProblemAlert error={q.error} />
      <div className="row between">
        <h2 style={{ margin: 0 }}>На связи {o.branches_online} из {o.branches_total}</h2>
        <span className="muted small">обновлено {formatDateTime(o.generated_at)}</span>
      </div>

      {anomalies.length > 0 && (
        <section className="card" aria-label="Аномалии">
          <h2>Аномалии · {anomalies.length}</h2>
          <ul className="anomalies">
            {anomalies.map(({ b, code }) => (
              <li key={`${b.id}-${code}`} className={DANGER.has(code) ? 'danger' : undefined}>
                <Icon name="alert" /> {ANOMALY[code]?.(b) ?? `${b.id}: ${code}`}
              </li>
            ))}
          </ul>
        </section>
      )}

      {o.branches.length === 0 ? <EmptyState title="Отделений пока нет">Отделение появится после первого heartbeat.</EmptyState> : (
        <div className="cards">{o.branches.map((b) => <Card key={b.id} b={b} />)}</div>
      )}
    </div>
  );
}

function Card({ b }: { b: BranchCard }) {
  const lost = !b.online;
  const suffix = lost ? ' (на момент потери связи)' : '';
  const pending = b.config_published_version !== null && b.config_applied_version !== null && b.config_published_version > b.config_applied_version;
  return (
    <article className={`card branch-card${lost ? ' offline' : ''}`}>
      <div className="row between">
        <h2>{b.id} · {b.name}</h2>
        <span className={`chip tone-${lost ? 'muted' : 'ok'}`}>
          {lost ? `нет связи ${Math.max(1, Math.round(b.seconds_since_heartbeat / 60))} мин` : 'на связи'}
        </span>
      </div>
      <div className="row"><LoadLevelBadge level={b.load_level} />
        {b.load_level && b.clear_time_min != null && <span className="muted small">разгрузка через {b.clear_time_min} мин</span>}
        {b.day_profile && <span className="chip tone-muted">{b.day_profile}</span>}</div>
      <dl className="metrics" style={{ margin: 0 }}>
        <div><dt>Ожидают{suffix}</dt><dd>{b.waiting}</dd></div>
        <div><dt>Просрочены</dt><dd className={b.overdue > 0 ? 'bad' : undefined}>{b.overdue > 0 && <Icon name="alert" size={16} />} {b.overdue}</dd></div>
        <div><dt>Самое долгое ожидание</dt><dd>{formatSeconds(b.oldest_wait_sec)}</dd></div>
        <div><dt>Окна</dt><dd>{b.windows_open} из {b.windows_total}</dd></div>
      </dl>
      <div className="svc-line">
        события {b.events_attached ? 'подключены' : 'не подключены'} · в очереди на отправку {b.outbox_pending} · конфигурация{' '}
        {pending ? `${b.config_applied_version} → ${b.config_published_version} применяется` : (b.config_applied_version ?? b.config_version)}
        {lost && <> · последняя связь {formatDateTime(b.last_heartbeat_at)}</>}
      </div>
    </article>
  );
}
