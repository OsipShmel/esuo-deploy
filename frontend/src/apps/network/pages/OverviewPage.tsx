import { useMutation, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { formatSeconds } from '@shared/format/duration';
import { formatDateTime } from '@shared/format/time';
import { EmptyState, Icon, Loading, LoadLevelBadge, ProblemAlert, problemText } from '@shared/ui';
import { getOverview, pullLogs, takeSnapshot, type BranchCard, type LogPullResult, type NodeLine, type Snapshot } from '../api';
import { useToast } from '../toast';

/** Тексты аномалий (постановка network, вкладка «Сеть»). */
const ANOMALY: Record<string, (b: BranchCard) => string> = {
  offline: (b) => `${b.id}: нет связи с отделением`,
  wait_grows_without_tickets: (b) => `${b.id}: ожидание растёт, хотя клиентов мало — проверьте окна`,
  overdue_tickets: (b) => `${b.id}: ${b.overdue} просроченных талонов`,
  no_open_windows: (b) => `${b.id}: клиенты ждут, а окна закрыты`,
  outbox_lag: (b) => `${b.id}: события задерживаются в отделении`,
  config_pending: (b) => `${b.id}: конфигурация v${b.config_published_version ?? '?'} ещё не применена`,
  overload: (b) => `${b.id}: перегрузка дольше 15 минут`,
  db_unavailable: (b) => `${b.id}: данные очереди не обновляются ${formatSeconds(b.db_unavailable_sec ?? 0)} — база отделения не отвечает или ПК перегружен; связь есть`,
};
const DANGER = new Set(['offline', 'no_open_windows', 'overload', 'db_unavailable']);
/** Правила мониторинга (`sys.*`) присылают текст сами. */
const alertOf = (b: BranchCard, code: string) => b.alerts?.find((a) => a.code === code);

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
            {anomalies.map(({ b, code }) => {
              const alert = alertOf(b, code);
              return (
                <li key={`${b.id}-${code}`} className={DANGER.has(code) || alert?.severity === 'critical' ? 'danger' : undefined}>
                  <Icon name="alert" /> {ANOMALY[code]?.(b) ?? `${b.id}: ${alert?.text ?? code}`}
                </li>
              );
            })}
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
  const dbStuck = b.db_unavailable_sec != null;
  const suffix = lost ? ' (на момент потери связи)' : dbStuck ? ' (последние известные)' : '';
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
      <NodeRow b={b} />
    </article>
  );
}

/** Ошибки диагностики: центр отвечает за отделение, «запрос не потеряется» тут неправда. */
const DIAG_PHRASES: Record<string, string> = {
  BRANCH_UNAVAILABLE: 'отделение не ответило, повторите позже',
  NETWORK_ERROR: 'нет связи с центром',
};
const diagError = (e: unknown) => { const p = problemText(e, DIAG_PHRASES); return p.detail && p.code !== 'UNKNOWN' ? `${p.title}: ${p.detail}` : p.title; };

const TONE = { critical: 'danger', warning: 'warn', info: 'accent' } as const;

/** Подробные логи — за последние полчаса (постановка B7). */
const PULL_MINUTES = 30;

const gb = (mb: number) => { const v = mb / 1024; return v < 10 ? v.toFixed(1).replace('.', ',') : String(Math.round(v)); };

function nodeNumbers(n: NodeLine): string {
  const parts = [];
  if (n.cpu_pct != null) parts.push(`CPU ${n.cpu_pct}%`);
  if (n.http_p95_ms != null) parts.push(`ответ p95 ${n.http_p95_ms} мс`);
  if (n.disk_free_mb != null && n.disk_total_mb) parts.push(`диск свободно ${gb(n.disk_free_mb)} из ${gb(n.disk_total_mb)} ГБ`);
  if (n.app_version) parts.push(`версия ${n.app_version}`);
  return parts.join(' · ');
}

/** Снимок уходит файлом: в нём сотни строк, читать его удобнее в редакторе. */
function download(s: Snapshot) {
  const url = URL.createObjectURL(new Blob([JSON.stringify(s, null, 2)], { type: 'application/json' }));
  const a = document.createElement('a');
  a.href = url;
  a.download = `snapshot-${s.branch_id}-${s.taken_at.replace(/[:.]/g, '-')}.json`;
  a.click();
  URL.revokeObjectURL(url);
}

/** Строка «Узел»: состояние ПК отделения по правилам мониторинга, пара цифр и диагностика по запросу. */
function NodeRow({ b }: { b: BranchCard }) {
  const toast = useToast();
  const [logs, setLogs] = useState<LogPullResult | null>(null);
  const alerts = b.alerts ?? [];
  const logsM = useMutation({
    mutationFn: () => pullLogs(b.id, PULL_MINUTES),
    onSuccess: (r) => { setLogs(r); toast(`Логи ${b.id} за ${PULL_MINUTES} мин получены: ${r.lines} строк${r.truncated ? ' (не все)' : ''}`); },
    onError: (e) => toast(`Логи ${b.id}: ${diagError(e)}`, true),
  });
  const snapM = useMutation({
    mutationFn: () => takeSnapshot(b.id),
    onSuccess: (s) => { download(s); toast(`Снимок ${b.id} сохранён, ${Math.max(1, Math.round(s.size_bytes / 1024))} КБ`); },
    onError: (e) => toast(`Снимок ${b.id}: ${diagError(e)}`, true),
  });
  const offline = b.online ? undefined : 'Нет связи с отделением';

  return (
    <section className="node-row" aria-label={`Узел ${b.id}`}>
      <div className="row">
        <strong>Узел</strong>
        {alerts.length === 0
          ? <span className="chip tone-ok">{b.node ? 'в норме' : 'нет данных'}</span>
          : alerts.map((a) => (
            <span key={a.code} className={`chip tone-${TONE[a.severity]}`}><Icon name="alert" size={14} /> {a.text}</span>
          ))}
      </div>
      {b.node && <div className="svc-line">{nodeNumbers(b.node)}</div>}
      <div className="row node-actions">
        <button type="button" className="btn btn-sm" disabled={!!offline || logsM.isPending} title={offline} onClick={() => logsM.mutate()}>
          {logsM.isPending ? 'Собираем логи…' : `Подробные логи (${PULL_MINUTES} мин)`}
        </button>
        <button type="button" className="btn btn-sm" disabled={!!offline || snapM.isPending} title={offline} onClick={() => snapM.mutate()}>
          {snapM.isPending ? 'Снимаем…' : 'Снять диагностику'}
        </button>
        {b.dashboard_url && <a className="btn btn-sm" href={b.dashboard_url} target="_blank" rel="noreferrer">Графики</a>}
        {logs?.grafana_url && <a className="small" href={logs.grafana_url} target="_blank" rel="noreferrer">Открыть логи ({logs.lines} строк)</a>}
      </div>
    </section>
  );
}
