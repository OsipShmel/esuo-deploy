import { useEffect } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { EmptyState, Loading, LoadLevelBadge, ProblemAlert } from '@shared/ui';
import { useBranchApi } from '../App';
import { CLIENT_PHRASES } from '../phrases';
import { savedTicket } from '../storage';

/** Центр: список отделений (офлайн — серым). Узел: сразу экран услуг своего отделения. */
export function HomePage() {
  const api = useBranchApi();
  const navigate = useNavigate();
  const q = useQuery({ queryKey: ['branches', api.mode], queryFn: () => api.listBranches(), refetchInterval: 30_000 });
  const saved = savedTicket();

  useEffect(() => {
    if (api.mode === 'node' && q.data?.[0] && !saved) navigate(`/b/${q.data[0].id}`, { replace: true });
  }, [api.mode, q.data, saved, navigate]);

  return (
    <div className="stack">
      {saved && (
        <Link className="list-item" to={`/b/${saved.branch}/t/${saved.token}`} style={{ borderColor: 'var(--accent)' }}>
          <span>Вернуться к талону {saved.number}<span className="sub">Ваш талон сохранён на этом устройстве</span></span>
          <span className="chev">›</span>
        </Link>
      )}
      <h1>Выберите отделение</h1>
      <ProblemAlert error={q.error} phrases={CLIENT_PHRASES} />
      {q.isLoading ? <Loading rows={3} /> : (q.data ?? []).length === 0 ? (
        <EmptyState title="Отделений пока нет">Список появится, когда отделения выйдут на связь.</EmptyState>
      ) : (
        <ul className="list">
          {(q.data ?? []).map((b) => (
            <li key={b.id}>
              <Link className="list-item" to={`/b/${b.id}`} aria-disabled={!b.online}>
                <span>{b.name}<span className="sub">{b.online ? b.services.map((s) => s.name).join(' · ') : 'сейчас не на связи'}</span></span>
                {b.online ? <LoadLevelBadge level={b.load_level} /> : <span className="chip tone-muted">офлайн</span>}
              </Link>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
