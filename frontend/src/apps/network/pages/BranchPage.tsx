import { useQuery } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { Loading, ProblemAlert } from '@shared/ui';
import { getBranch, OWN } from '../api';
import { BranchCard } from '../components/BranchCard';

/**
 * Панель отделения (план финала, 8.1): его полная карточка, свежая из базы, раз в 5 секунд. У отделения роя (за
 * региональным хабом) — только графики: узла, который ответил бы на логи и снимок, у него нет.
 */
export function BranchPage() {
  const { id = '' } = useParams();
  const card = useQuery({ queryKey: ['network-branch', id], queryFn: () => getBranch(id), refetchInterval: 5_000 });
  const b = card.data;
  const region = b?.events_via ?? OWN;
  return (
    <div className="stack">
      <h2 className="crumbs">
        <Link to="/network">Сеть</Link>
        {b && <> › <Link to={`/network/regions/${encodeURIComponent(region)}`}>{b.events_via ?? 'Свои отделения'}</Link></>} › {id}
      </h2>
      <ProblemAlert error={card.error} />
      {card.isLoading ? <Loading rows={3} /> : b && <div className="panel-card"><BranchCard b={b} /></div>}
    </div>
  );
}
