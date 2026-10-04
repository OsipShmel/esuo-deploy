import { n } from '../anomalies';

/** Листание страницами: «‹ Назад · 1–24 из 62 500 · Вперёд ›»; одна страница — ничего. */
export function Pager({ offset, shown, total, size, onPage }: { offset: number; shown: number; total: number; onPage: (offset: number) => void; size: number }) {
  if (total <= size) return null;
  return (
    <div className="row net-pager">
      <button type="button" className="btn btn-sm" disabled={offset === 0} onClick={() => onPage(Math.max(0, offset - size))}>‹ Назад</button>
      <span className="muted small">{n(offset + 1)}–{n(offset + shown)} из {n(total)}</span>
      <button type="button" className="btn btn-sm" disabled={offset + size >= total} onClick={() => onPage(offset + size)}>Вперёд ›</button>
    </div>
  );
}
