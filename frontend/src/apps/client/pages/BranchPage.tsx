import { Link, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { EmptyState, Loading, LoadLevelBadge, ProblemAlert } from '@shared/ui';
import { useBranchApi } from '../App';
import { CLIENT_PHRASES } from '../phrases';
import { savedTicket } from '../storage';

/** Отделение: услуги и «Записаться на время». Талон в очередь берётся по QR (страница `join`) — здесь только запись. */
export function BranchPage() {
  const { b = '' } = useParams();
  const api = useBranchApi();
  const q = useQuery({ queryKey: ['info', b, api.mode], queryFn: () => api.info(b), refetchInterval: 30_000 });
  const saved = savedTicket();

  if (q.isLoading) return <Loading rows={4} />;
  if (!q.data) return <ProblemAlert error={q.error} phrases={CLIENT_PHRASES} />;
  const info = q.data;
  const load = info.load;

  return (
    <div className="stack">
      {saved && saved.branch === info.id && (
        <Link className="list-item" to={`/b/${saved.branch}/t/${saved.token}`} style={{ borderColor: 'var(--accent)' }}>
          <span>Мой талон {saved.number}<span className="sub">Открыть статус</span></span><span className="chev">›</span>
        </Link>
      )}
      <div>
        <h1 style={{ marginBottom: 4 }}>{info.name}</h1>
        {load && <div className="row"><LoadLevelBadge level={load.level} />
          {load.expected_wait_min != null && load.level !== 'normal' && <span className="muted">ожидание около {load.expected_wait_min} мин</span>}</div>}
      </div>
      <h2>Записаться на время</h2>
      {info.services.length === 0 ? <EmptyState title="Услуг нет" /> : (
        <ul className="list">
          {info.services.map((s) => (
            <li key={s.id}>
              <Link className="list-item" to={`/b/${info.id}/book?service=${s.id}`}>
                <span>{s.name}{s.slot_duration_min ? <span className="sub">слот {s.slot_duration_min} мин</span> : null}</span>
                <span className="chev">›</span>
              </Link>
            </li>
          ))}
        </ul>
      )}
      <p className="muted small">Пришли в отделение без записи? Отсканируйте QR-код на табло — мы выдадим электронный талон.</p>
    </div>
  );
}
