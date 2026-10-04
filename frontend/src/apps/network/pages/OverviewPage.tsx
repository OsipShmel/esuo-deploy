import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { formatDateTime } from '@shared/format/time';
import { EmptyState, Icon, Loading, LoadLevelBadge, ProblemAlert } from '@shared/ui';
import { getBranchPage, getSummary, OWN, type BranchCard as Card } from '../api';
import { ANOMALY, ANOMALY_TITLE, DANGER, n, plural } from '../anomalies';
import { BranchCard } from '../components/BranchCard';
import { CenterStrip } from '../components/CenterStrip';
import { RegionTile } from '../components/RegionTile';

/** Худшие отделения на главной. */
const TOP = 10;
/** Своих отделений (настоящие узлы) до стольких — полными карточками на главной, больше — списком. */
const OWN_CARDS = 50;

/** Правила мониторинга (`sys.*`) присылают текст сами. */
const alertOf = (b: Card, code: string) => b.alerts?.find((a) => a.code === code);

/**
 * Главная экрана сети (план финала, 8.1): только агрегаты — счётчики, строка «Центр», аномалии по типам, плитки
 * регионов, худшие отделения; свои отделения (единицы) — полными карточками. Ничего здесь не растёт с сетью: списки
 * отделений — на страницах регионов, по 24. Опрос раз в 5 секунд: у центра для этого экрана нет живого потока.
 */
export function OverviewPage() {
  const summary = useQuery({ queryKey: ['network-summary'], queryFn: () => getSummary(TOP), refetchInterval: 5_000 });
  const own = summary.data?.regions.find((r) => r.events_via === null);
  const ownCards = useQuery({
    queryKey: ['network-branches', OWN, 'cards'],
    queryFn: () => getBranchPage({ region: OWN, limit: OWN_CARDS }),
    enabled: !!own && own.total <= OWN_CARDS,
    refetchInterval: 5_000,
  });

  if (summary.isLoading) return <Loading rows={4} />;
  if (!summary.data) return <ProblemAlert error={summary.error} />;
  const s = summary.data;
  const codes = Object.entries(s.anomalies).sort((a, b) => b[1] - a[1]);
  const regions = s.regions.filter((r) => r.events_via !== null);
  const lines = s.problems.flatMap((b) => b.anomalies.map((code) => ({ b, code })));

  return (
    <div className="stack">
      <ProblemAlert error={summary.error ?? ownCards.error} />
      <div className="row between">
        <h2 style={{ margin: 0 }}>На связи {n(s.branches_online)} из {n(s.branches_total)}</h2>
        <span className="muted small">обновлено {formatDateTime(s.generated_at)}</span>
      </div>
      {s.branches_total > 0 && (
        <div className="row net-counters">
          <Link to="/network/list?status=offline" className={`chip tone-${s.branches_offline > 0 ? 'danger' : 'ok'}`}>без связи {n(s.branches_offline)}</Link>
          <Link to="/network/list?status=problems" className={`chip tone-${s.problem_branches_total > 0 ? 'warn' : 'ok'}`}>с проблемами {n(s.problem_branches_total)}</Link>
          {Object.entries(s.load_levels).filter(([level]) => level !== 'unknown').map(([level, count]) => (
            <span key={level} className="row" style={{ gap: 4 }}><LoadLevelBadge level={level} /> {n(count)}</span>
          ))}
        </div>
      )}
      <CenterStrip />

      {codes.length > 0 && (
        <section className="card" aria-label="Аномалии">
          <h2>Аномалии · {n(s.problem_branches_total)} {plural(s.problem_branches_total, 'отделение', 'отделения', 'отделений')}</h2>
          <div className="row net-counters" style={{ marginBottom: 12 }}>
            {codes.map(([code, count]) => (
              <Link key={code} to={`/network/list?anomaly=${encodeURIComponent(code)}`}
                className={`chip tone-${DANGER.has(code) ? 'danger' : 'warn'}`}>{ANOMALY_TITLE[code] ?? code} — {n(count)}</Link>
            ))}
          </div>
          <ul className="anomalies" aria-label="Худшие отделения">
            {lines.map(({ b, code }) => {
              const alert = alertOf(b, code);
              return (
                <li key={`${b.id}-${code}`} className={DANGER.has(code) || alert?.severity === 'critical' ? 'danger' : undefined}>
                  <Icon name="alert" /> {ANOMALY[code]?.(b) ?? `${b.id}: ${alert?.text ?? code}`}
                  <Link className="btn btn-sm" style={{ marginLeft: 'auto' }} to={`/network/b/${b.id}`}>Открыть</Link>
                </li>
              );
            })}
          </ul>
          {s.problem_branches_total > s.problems.length && (
            <p className="muted small" style={{ margin: '8px 0 0' }}>
              Показаны {s.problems.length} худших из {n(s.problem_branches_total)}. Остальные — по клику на тип аномалии.
            </p>
          )}
        </section>
      )}

      {regions.length > 0 && (
        <section className="stack" aria-label="Регионы">
          <h2 style={{ margin: 0 }}>Регионы</h2>
          <div className="region-tiles">{regions.map((r) => <RegionTile key={r.events_via} r={r} link />)}</div>
        </section>
      )}

      {s.branches_total === 0 ? <EmptyState title="Отделений пока нет">Отделение появится после первого heartbeat.</EmptyState> : own && (
        <section className="stack" aria-label="Свои отделения">
          {regions.length > 0 && <h2 style={{ margin: 0 }}>Отделения вне регионов</h2>}
          {own.total > OWN_CARDS
            ? <RegionTile r={own} link />
            : !ownCards.data ? <Loading rows={2} /> : (
              <div className="cards">{ownCards.data.items.map((b) => <BranchCard key={b.id} b={b} />)}</div>
            )}
        </section>
      )}
    </div>
  );
}
