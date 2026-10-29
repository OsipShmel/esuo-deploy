import { useEffect, useState } from 'react';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { formatDateTime } from '@shared/format/time';
import { Loading, ProblemAlert } from '@shared/ui';
import { getBranchConfig, getBranches, getConfig, putBranch, putGlobal, type BranchConfigStatus, type Published, type Section, type Sections } from '../api';
import { n } from '../anomalies';
import { Pager } from '../components/Pager';
import { useToast } from '../toast';
import { BoundsPanel } from './config/BoundsPanel';
import { PlanningPanel } from './config/PlanningPanel';
import { QueuePanel } from './config/QueuePanel';
import { UsersPanel } from './config/UsersPanel';
import { VersionsPanel } from './config/VersionsPanel';

type Part = 'queue' | 'bounds' | 'planning' | 'users' | 'versions';
const PAGE = 24;
const PARTS: [Part, string][] = [['queue', 'Параметры очереди'], ['bounds', 'Границы'], ['planning', 'Профили нагрузки'], ['users', 'Пользователи'], ['versions', 'Журнал версий']];

/**
 * Конфигурация сети (только менеджер услуг). Публикация заменяет секцию целиком, поэтому у каждой секции своя кнопка.
 * После сохранения статус применения по отделениям опрашивается раз в 3 секунды, пока есть неприменённые версии.
 * Отделения — страницами по 24 с поиском: у сети в 250 000 отделений весь список не приходит.
 */
export function ConfigPage() {
  const qc = useQueryClient();
  const toast = useToast();
  const [scope, setScope] = useState<string>('global');
  const [part, setPart] = useState<Part>('queue');
  const [search, setSearch] = useState('');
  const [q, setQ] = useState('');
  const [offset, setOffset] = useState(0);
  useEffect(() => { const id = window.setTimeout(() => { setQ(search.trim()); setOffset(0); }, 300); return () => window.clearTimeout(id); }, [search]);

  const config = useQuery({
    queryKey: ['config', q, offset], queryFn: () => getConfig({ q: q || undefined, offset, limit: PAGE }),
    placeholderData: keepPreviousData,
    refetchInterval: (query) => (query.state.data?.branches.some((b) => b.pending) ? 3_000 : 15_000),
  });
  const chosen = useQuery({ queryKey: ['config', 'branch', scope], queryFn: () => getBranchConfig(scope), enabled: scope !== 'global' });
  // Коды услуг для профилей нагрузки: каталог у отделений сети один, первой страницы хватает.
  const catalog = useQuery({ queryKey: ['branches', 'catalog'], queryFn: () => getBranches({ limit: 50 }), staleTime: 60_000 });

  const save = useMutation({
    mutationFn: ({ section, value }: { section: keyof Sections; value: Section | null }): Promise<Published> =>
      (scope === 'global' ? putGlobal({ [section]: value }) : putBranch(scope, { [section]: value })),
    onSuccess: (r) => {
      toast(`Опубликована версия v${r.version}${r.published_to.length ? ` → ${publishedTo(r.published_to)}` : ''}`);
      void qc.invalidateQueries({ queryKey: ['config'] });
      void qc.invalidateQueries({ queryKey: ['versions'] });
    },
    onError: () => toast('Не удалось опубликовать конфигурацию', true),
  });

  if (config.isLoading) return <Loading rows={4} />;
  const data = config.data;
  if (!data) return <ProblemAlert error={config.error} />;

  const sections: Sections = scope === 'global' ? data.global : (chosen.data?.overrides ?? { queue: null, bounds: null, planning: null });
  const codes = [...new Set((catalog.data?.items ?? []).flatMap((b) => b.services.map((s) => s.code)))];
  const choose = (id: string) => { setScope(id); if (part === 'users' && id === 'global') setPart('queue'); };

  return (
    <div className="stack">
      <section className="card stack" aria-label="Публикация и применение">
        <h2>Публикация и применение</h2>
        <div className="row net-filter">
          <input type="search" value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Номер или название отделения" aria-label="Поиск отделения" />
        </div>
        <div className={`table-wrap${config.isPlaceholderData ? ' stale' : ''}`}><table>
          <thead><tr><th>Отделение</th><th className="num">Опубликована</th><th className="num">Применена</th><th>Статус</th><th /></tr></thead>
          <tbody>{data.branches.map((b) => (
            <tr key={b.branch_id}>
              <td>{b.branch_id}</td><td className="num">{b.published_version ?? '—'}</td>
              <td className="num">{b.applied_version ?? '—'}{b.applied_at ? <span className="muted small"> · {formatDateTime(b.applied_at)}</span> : null}</td>
              <td>{applyStatus(b)}</td>
              <td><button type="button" className="btn btn-sm" aria-pressed={scope === b.branch_id} onClick={() => choose(b.branch_id)}
                aria-label={`Настроить ${b.branch_id}`}>Настроить</button></td>
            </tr>
          ))}</tbody>
        </table></div>
        {data.total === 0 && <p className="muted small" style={{ margin: 0 }}>Ничего не найдено.</p>}
        <Pager offset={data.offset} shown={data.branches.length} total={data.total} size={PAGE} onPage={setOffset} />
        {data.global.updated_at && <div className="muted small">Глобальная конфигурация изменена {formatDateTime(data.global.updated_at)}{data.global.updated_by ? `, ${data.global.updated_by}` : ''}</div>}
      </section>

      <div className="card toolbar">
        <label className="field">Область
          <select value={scope} onChange={(e) => choose(e.target.value)}>
            <option value="global">Вся сеть (глобальная)</option>
            {scope !== 'global' && <option value={scope}>Отделение {scope} (переопределения)</option>}
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

/** Глобальная публикация уходит всей сети: 250 000 номеров в тосте не нужны. */
function publishedTo(ids: string[]) {
  return ids.length <= 5 ? ids.join(', ') : `${ids.slice(0, 5).join(', ')} и ещё ${n(ids.length - 5)}`;
}

/** «применено» / «ждём отделение» / «отделение не на связи» (постановка network, п. 4). */
function applyStatus(b: BranchConfigStatus) {
  if (b.published_version === null) return <span className="muted">ещё не публиковалась</span>;
  if (!b.pending) return <span className="chip tone-ok">применено</span>;
  if (!b.online) return <span className="chip tone-danger">отделение не на связи</span>;
  return <span className="chip tone-warn">ждём отделение</span>;
}
