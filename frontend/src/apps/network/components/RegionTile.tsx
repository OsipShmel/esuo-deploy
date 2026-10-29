import { Link } from 'react-router-dom';
import { OWN, type RegionSums } from '../api';
import { n } from '../anomalies';

/** Плитка региона: сколько, на связи, проблем, очередь у отделений на связи, худшее отделение. */
export function RegionTile({ r, link = false }: { r: RegionSums; link?: boolean }) {
  const name = r.events_via ?? 'Свои отделения';
  const body = (
    <>
      <div className="row between"><strong>{name}</strong><span className="muted small">{n(r.total)}</span></div>
      <div className="svc-line">на связи {n(r.online)}{r.total > r.online ? <span className="bad"> · без связи {n(r.total - r.online)}</span> : null}</div>
      <div className="svc-line">ждут {n(r.waiting)} · просрочены {n(r.overdue)} · окон открыто {n(r.windows_open)}</div>
      <div className={`svc-line${r.problems > 0 ? ' warn' : ''}`}>
        {r.problems > 0 ? <>проблем {n(r.problems)}{r.worst ? ` · хуже всех ${r.worst}` : ''}</> : 'проблем нет'}
      </div>
    </>
  );
  return link
    ? <Link className="card region-tile" to={`/network/regions/${encodeURIComponent(r.events_via ?? OWN)}`} aria-label={`Регион ${name}`}>{body}</Link>
    : <section className="card region-tile" aria-label={`Регион ${name}`}>{body}</section>;
}
