import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { EmptyState, Loading, LoadLevelBadge, ProblemAlert } from '@shared/ui';
import { useBranchApi } from '../App';
import { CLIENT_PHRASES } from '../phrases';
import { savedTicket } from '../storage';

/**
 * Центр: поиск и список отделений (офлайн — серым), первые 50 — в сети их может быть сотни тысяч. Узел: сразу экран
 * услуг своего отделения.
 */
export function HomePage() {
  const api = useBranchApi();
  const navigate = useNavigate();
  const [search, setSearch] = useState('');
  const [term, setTerm] = useState('');
  useEffect(() => { const id = window.setTimeout(() => setTerm(search.trim()), 300); return () => window.clearTimeout(id); }, [search]);
  const q = useQuery({
    queryKey: ['branches', api.mode, term], queryFn: () => api.listBranches(term || undefined),
    refetchInterval: 30_000, placeholderData: keepPreviousData,
  });
  const saved = savedTicket();
  const items = q.data?.items ?? [];

  useEffect(() => {
    if (api.mode === 'node' && q.data?.items[0] && !saved) navigate(`/b/${q.data.items[0].id}`, { replace: true });
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
      {api.mode === 'center' && (
        <input type="search" value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Номер, город или адрес"
          aria-label="Поиск отделения" className="search-input" />
      )}
      <ProblemAlert error={q.error} phrases={CLIENT_PHRASES} />
      {q.isLoading ? <Loading rows={3} /> : items.length === 0 ? (
        term
          ? <EmptyState title="Ничего не нашлось">Проверьте номер или попробуйте часть названия.</EmptyState>
          : <EmptyState title="Отделений пока нет">Список появится, когда отделения выйдут на связь.</EmptyState>
      ) : (
        <ul className="list">
          {items.map((b) => (
            <li key={b.id}>
              <Link className="list-item" to={`/b/${b.id}`} aria-disabled={!b.online}>
                <span>{b.name}<span className="sub">{b.online ? b.services.map((s) => s.name).join(' · ') : 'сейчас не на связи'}</span></span>
                {b.online ? <LoadLevelBadge level={b.load_level} /> : <span className="chip tone-muted">офлайн</span>}
              </Link>
            </li>
          ))}
        </ul>
      )}
      {q.data && q.data.total > items.length && (
        <p className="muted small">Показаны {items.length} из {q.data.total.toLocaleString('ru-RU')} — уточните поиск.</p>
      )}
    </div>
  );
}
