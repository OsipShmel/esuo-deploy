import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useCallback, useEffect, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { formatDateTime } from '@shared/format/time';
import { EmptyState, Loading, ProblemAlert } from '@shared/ui';
import { getBranchPage, getRegion, OWN, type BranchFilter } from '../api';
import { ANOMALY_TITLE } from '../anomalies';
import { BranchMini } from '../components/BranchCard';
import { Pager } from '../components/Pager';
import { RegionTile } from '../components/RegionTile';

const FILTERS: [BranchFilter, string][] = [['all', 'Все'], ['problems', 'С проблемами'], ['offline', 'Без связи'], ['online', 'На связи']];
const PAGE = 24;

/**
 * Список отделений страницами (план финала, 8.1): регион (`/network/regions/:region`) или вся сеть с фильтром по
 * аномалии (`/network/list?anomaly=…`). На странице не больше 24 компактных карточек: DOM не растёт с сетью.
 */
export function ListPage() {
  const { region } = useParams();
  const [params, setParams] = useSearchParams();
  const anomaly = params.get('anomaly') ?? undefined;
  const filter = (params.get('status') as BranchFilter | null) ?? 'all';
  const offset = Number(params.get('offset') ?? 0);
  const q = params.get('q') ?? '';
  const [search, setSearch] = useState(q);
  const set = useCallback((patch: Record<string, string | undefined>) => setParams((p) => {
    const next = new URLSearchParams(p);
    for (const [k, v] of Object.entries(patch)) { if (v) next.set(k, v); else next.delete(k); }
    return next;
  }, { replace: true }), [setParams]);
  useEffect(() => {
    const id = window.setTimeout(() => { if (search.trim() !== q) set({ q: search.trim() || undefined, offset: undefined }); }, 300);
    return () => window.clearTimeout(id);
  }, [search, q, set]);

  const head = useQuery({ queryKey: ['network-region', region], queryFn: () => getRegion(region!), enabled: !!region, refetchInterval: 5_000 });
  const page = useQuery({
    queryKey: ['network-branches', region, anomaly, q, filter, offset],
    queryFn: () => getBranchPage({ region, anomaly, q: q || undefined, status: filter, offset, limit: PAGE }),
    refetchInterval: 5_000,
    placeholderData: keepPreviousData,
  });
  const p = page.data;
  const title = region ? (region === OWN ? 'Свои отделения' : `Регион ${region}`) : anomaly ? (ANOMALY_TITLE[anomaly] ?? anomaly) : 'Все отделения';

  return (
    <div className="stack">
      <div className="row between">
        <h2 className="crumbs"><Link to="/network">Сеть</Link> › {title}</h2>
        {p && <span className="muted small">обновлено {formatDateTime(p.generated_at)}</span>}
      </div>
      <ProblemAlert error={head.error ?? page.error} />
      {head.data && <RegionTile r={head.data} />}
      {head.data && head.data.worst_branches.length > 0 && (
        <section className="card" aria-label="Худшие отделения региона">
          <h2>Худшие</h2>
          <div className="cards">{head.data.worst_branches.map((b) => <BranchMini key={b.id} b={b} />)}</div>
        </section>
      )}
      <section className="stack" aria-label="Отделения">
        <div className="row net-filter">
          <input type="search" value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Номер или название отделения" aria-label="Поиск отделения" />
          <div className="row" role="group" aria-label="Фильтр">
            {FILTERS.map(([value, name]) => (
              <button key={value} type="button" className="btn btn-sm" aria-pressed={filter === value}
                onClick={() => set({ status: value === 'all' ? undefined : value, offset: undefined })}>{name}</button>
            ))}
          </div>
        </div>
        {page.isPlaceholderData && <p className="muted small" role="status" style={{ margin: 0 }}>Обновляем список…</p>}
        {!p ? <Loading rows={3} /> : p.total === 0 ? <EmptyState title="Ничего не найдено">Измените поиск или фильтр.</EmptyState> : (
          <>
            <div className={`cards${page.isPlaceholderData ? ' stale' : ''}`}>{p.items.map((b) => <BranchMini key={b.id} b={b} />)}</div>
            <Pager offset={p.offset} shown={p.items.length} total={p.total} size={PAGE} onPage={(o) => set({ offset: o ? String(o) : undefined })} />
          </>
        )}
      </section>
    </div>
  );
}
