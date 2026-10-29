import { useQuery } from '@tanstack/react-query';
import { getCenterLine, type CenterLine } from '../api';
import { n } from '../anomalies';

const gb = (mb: number) => (mb / 1024).toFixed(1).replace('.', ',');
const sec = (s: number) => `${String(s).replace('.', ',')} с`;

/**
 * Строка «Центр»: справляется ли сам центр — CPU, сборщик мусора, куча, поток heartbeat и событий,
 * сколько длится сброс и проход правил, что ждёт в памяти. Подробнее — дашборд «ЕСУО · Центр».
 */
export function CenterStrip() {
  const q = useQuery({ queryKey: ['network-center'], queryFn: getCenterLine, refetchInterval: 5_000 });
  const c = q.data;
  return (
    <section className="card center-strip" aria-label="Центр">
      <strong>Центр</strong>
      {!c ? <span className="muted small">{q.isError ? 'нет данных' : '…'}</span> : <Numbers c={c} />}
      {c?.grafana_url && <a className="btn btn-sm" href={c.grafana_url} target="_blank" rel="noreferrer">Графики центра</a>}
    </section>
  );
}

function Numbers({ c }: { c: CenterLine }) {
  const heapPct = c.heap_max_mb > 0 ? c.heap_used_mb / c.heap_max_mb : 0;
  return (
    <span className="svc-line">
      {c.cpu != null && <>CPU {c.cpu}% · </>}
      <span className={c.gc_pct >= 10 ? 'bad' : undefined}>GC {String(c.gc_pct).replace('.', ',')}%</span> ·{' '}
      <span className={heapPct >= 0.85 ? 'bad' : undefined}>куча {gb(c.heap_used_mb)} из {gb(c.heap_max_mb)} ГБ</span> ·{' '}
      heartbeat {n(c.heartbeats_per_sec)}/с · события {n(c.events_per_sec)}/с ·{' '}
      <span className={c.flush_sec >= 20 ? 'bad' : undefined}>сброс {sec(c.flush_sec)}</span> · правила {sec(c.rules_sec)}
      {c.events_pending > 0 && <> · ждут записи {n(c.events_pending)}</>}
    </span>
  );
}
