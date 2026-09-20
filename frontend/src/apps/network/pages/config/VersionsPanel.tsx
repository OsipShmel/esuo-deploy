import { useQuery } from '@tanstack/react-query';
import { formatDateTime } from '@shared/format/time';
import { EmptyState, Loading, ProblemAlert } from '@shared/ui';
import { getVersions } from '../../api';

/** Журнал публикаций конфигурации: какая версия, кому и кем опубликована. */
export function VersionsPanel({ scope }: { scope: string }) {
  const q = useQuery({ queryKey: ['versions', scope], queryFn: () => getVersions(scope === 'global' ? undefined : scope), refetchInterval: 15_000 });
  return (
    <section className="card stack" aria-label="Журнал версий">
      <h2>Журнал версий</h2>
      {q.isLoading ? <Loading /> : !q.data ? <ProblemAlert error={q.error} /> : q.data.length === 0 ? <EmptyState title="Публикаций пока не было" /> : (
        <div className="table-wrap"><table>
          <thead><tr><th className="num">Версия</th><th>Отделение</th><th>Автор</th><th>Опубликована</th></tr></thead>
          <tbody>{q.data.map((v) => <tr key={`${v.version}-${v.branch_id}`}><td className="num">v{v.version}</td><td>{v.branch_id}</td><td>{v.author ?? '—'}</td><td>{formatDateTime(v.created_at)}</td></tr>)}</tbody>
        </table></div>
      )}
    </section>
  );
}
