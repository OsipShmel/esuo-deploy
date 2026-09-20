import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { formatDateTime } from '@shared/format/time';
import { Loading, ProblemAlert } from '@shared/ui';
import { getBranches, getConfig, putBranch, putGlobal, type BranchConfigStatus, type Published, type Section, type Sections } from '../api';
import { useToast } from '../toast';
import { BoundsPanel } from './config/BoundsPanel';
import { PlanningPanel } from './config/PlanningPanel';
import { QueuePanel } from './config/QueuePanel';
import { UsersPanel } from './config/UsersPanel';
import { VersionsPanel } from './config/VersionsPanel';

type Part = 'queue' | 'bounds' | 'planning' | 'users' | 'versions';
const PARTS: [Part, string][] = [['queue', 'Параметры очереди'], ['bounds', 'Границы'], ['planning', 'Профили нагрузки'], ['users', 'Пользователи'], ['versions', 'Журнал версий']];

/**
 * Конфигурация сети (только менеджер услуг). Публикация заменяет секцию целиком, поэтому у каждой секции своя кнопка.
 * После сохранения статус применения по отделениям опрашивается раз в 3 секунды, пока есть неприменённые версии.
 */
export function ConfigPage() {
  const qc = useQueryClient();
  const toast = useToast();
  const [scope, setScope] = useState<string>('global');
  const [part, setPart] = useState<Part>('queue');

  const config = useQuery({
    queryKey: ['config'], queryFn: getConfig,
    refetchInterval: (query) => (query.state.data?.branches.some((b) => b.pending) ? 3_000 : 15_000),
  });
  const branches = useQuery({ queryKey: ['branches'], queryFn: getBranches, refetchInterval: 15_000 });

  const save = useMutation({
    mutationFn: ({ section, value }: { section: keyof Sections; value: Section | null }): Promise<Published> =>
      (scope === 'global' ? putGlobal({ [section]: value }) : putBranch(scope, { [section]: value })),
    onSuccess: (r) => {
      toast(`Опубликована версия v${r.version}${r.published_to.length ? ` → ${r.published_to.join(', ')}` : ''}`);
      void qc.invalidateQueries({ queryKey: ['config'] });
      void qc.invalidateQueries({ queryKey: ['versions'] });
    },
    onError: () => toast('Не удалось опубликовать конфигурацию', true),
  });

  if (config.isLoading) return <Loading rows={4} />;
  const data = config.data;
  if (!data) return <ProblemAlert error={config.error} />;

  const branchIds = data.branches.map((b) => b.branch_id);
  const status = data.branches.find((b) => b.branch_id === scope);
  const sections: Sections = scope === 'global' ? data.global : (status?.overrides ?? { queue: null, bounds: null, planning: null });
  const online = new Map((branches.data ?? []).map((b) => [b.id, b.online]));
  const codes = [...new Set((branches.data ?? []).flatMap((b) => b.services.map((s) => s.code)))];

  return (
    <div className="stack">
      <section className="card stack" aria-label="Публикация и применение">
        <h2>Публикация и применение</h2>
        <div className="table-wrap"><table>
          <thead><tr><th>Отделение</th><th className="num">Опубликована</th><th className="num">Применена</th><th>Статус</th></tr></thead>
          <tbody>{data.branches.map((b) => (
            <tr key={b.branch_id}>
              <td>{b.branch_id}</td><td className="num">{b.published_version ?? '—'}</td>
              <td className="num">{b.applied_version ?? '—'}{b.applied_at ? <span className="muted small"> · {formatDateTime(b.applied_at)}</span> : null}</td>
              <td>{applyStatus(b, online.get(b.branch_id))}</td>
            </tr>
          ))}</tbody>
        </table></div>
        {data.global.updated_at && <div className="muted small">Глобальная конфигурация изменена {formatDateTime(data.global.updated_at)}{data.global.updated_by ? `, ${data.global.updated_by}` : ''}</div>}
      </section>

      <div className="card toolbar">
        <label className="field">Область
          <select value={scope} onChange={(e) => { setScope(e.target.value); if (e.target.value === 'global' && part === 'users') setPart('queue'); }}>
            <option value="global">Вся сеть (глобальная)</option>
            {branchIds.map((id) => <option key={id} value={id}>Отделение {id} (переопределения)</option>)}
          </select>
        </label>
        <div className="subtabs" role="group" aria-label="Раздел конфигурации">
          {PARTS.filter(([p]) => p !== 'users' || scope !== 'global').map(([p, title]) => (
            <button key={p} type="button" className="btn" aria-pressed={part === p} onClick={() => setPart(p)}>{title}</button>
          ))}
        </div>
      </div>

      {part === 'queue' && <QueuePanel scope={scope} section={sections.queue} inherited={data.global.queue} busy={save.isPending} error={save.error}
        onSave={(value) => save.mutate({ section: 'queue', value })} />}
      {part === 'bounds' && <BoundsPanel scope={scope} section={sections.bounds} busy={save.isPending} error={save.error}
        onSave={(value) => save.mutate({ section: 'bounds', value })} />}
      {part === 'planning' && <PlanningPanel scope={scope} section={sections.planning} serviceCodes={codes} busy={save.isPending} error={save.error}
        onSave={(value) => save.mutate({ section: 'planning', value })} />}
      {part === 'users' && scope !== 'global' && <UsersPanel branch={scope} />}
      {part === 'versions' && <VersionsPanel scope={scope} />}
    </div>
  );
}

/** «применено» / «ждём отделение» / «отделение не на связи» (постановка network, п. 4). */
function applyStatus(b: BranchConfigStatus, online: boolean | undefined) {
  if (b.published_version === null) return <span className="muted">ещё не публиковалась</span>;
  if (!b.pending) return <span className="chip tone-ok">применено</span>;
  if (online === false) return <span className="chip tone-danger">отделение не на связи</span>;
  return <span className="chip tone-warn">ждём отделение</span>;
}
