import { useEffect, useMemo, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Icon } from '@shared/ui';
import { Clock } from '@shared/ui/Clock';
import { useEventStream } from '@shared/sse/useEventStream';
import { formatTime } from '@shared/format/time';
import { getBoard, getBranchLoad, type BoardSnapshot } from './api';
import { QrImage } from './QrImage';
import { enableSound } from './sound';
import { useAnnouncements } from './useAnnouncements';

const LIST_SIZE = 6;
const DONE_TTL_MS = 2 * 60_000;

/** Табло в зале (концепция 4.2, постановка board): без входа, без мыши, часами подряд. */
export function Board() {
  const qc = useQueryClient();
  const [soundOn, setSoundOn] = useState(false);
  // Маршрут /b/{branch}/join отдаёт и сам узел, поэтому по умолчанию QR ведёт туда же, откуда открыто табло —
  // с верным хостом и портом без ручной настройки. ?center=… — override на случай показа табло не с той же машины.
  const centerParam = useMemo(() => new URLSearchParams(window.location.search).get('center'), []);
  const center = (centerParam ?? window.location.origin).replace(/\/$/, '');

  const stream = useEventStream({
    url: '/api/v1/public/board/stream',
    events: [],
    onSnapshot: (data) => qc.setQueryData(['board'], data as BoardSnapshot),
  });
  const board = useQuery({ queryKey: ['board'], queryFn: getBoard, refetchInterval: stream.state === 'polling' ? 10_000 : false });
  const branch = useQuery({ queryKey: ['branch-load'], queryFn: getBranchLoad, refetchInterval: 30_000 });
  const data = board.data;
  const tz = branch.data?.timezone;
  const load = branch.data?.load ?? null;

  const announced = useAnnouncements(data?.calls);
  useDailyReload(tz);

  // Вызовы в статусе done уходят из списка через 2 минуты после того, как табло их таким увидело.
  const doneSeen = useRef(new Map<string, number>());
  const [tick, setTick] = useState(0);
  useEffect(() => { const id = window.setInterval(() => setTick((n) => n + 1), 15_000); return () => window.clearInterval(id); }, []);
  const list = useMemo(() => {
    const nowMs = Date.now();
    const calls = data?.calls ?? [];
    const alive = new Set(calls.map((c) => c.display_number));
    for (const k of doneSeen.current.keys()) if (!alive.has(k)) doneSeen.current.delete(k);       // без утечек
    for (const c of calls) if (c.status === 'done' && !doneSeen.current.has(c.display_number)) doneSeen.current.set(c.display_number, nowMs);
    return calls.filter((c) => c.status !== 'done' || nowMs - (doneSeen.current.get(c.display_number) ?? nowMs) < DONE_TTL_MS)
      .filter((c) => c.display_number !== announced?.display_number)
      .slice(0, LIST_SIZE);
    // tick заставляет пересчитать срок жизни done
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [data?.calls, announced?.display_number, tick]);

  const waiting = Object.values(data?.waiting_by_service ?? {}).reduce((a, b) => a + b, 0);
  const showLoad = load && (load.level === 'high' || load.level === 'overload');

  const joinUrl = data ? `${center}/b/${data.branch_id}/join?code=${encodeURIComponent(data.qr_code)}` : null;

  if (!soundOn) {
    return (
      <button type="button" className="sound-gate" onClick={() => void enableSound().then(() => setSoundOn(true))}>
        Включить звук
        <span>Нажмите один раз — дальше табло работает само</span>
      </button>
    );
  }

  return (
    <div className="board">
      <header className="board-top">
        <span className="branch">{data?.branch_name ?? branch.data?.name ?? ''}</span>
        <span className="board-status">
          {stream.state !== 'live' && (
            <span className="offline"><Icon name="wifiOff" size={28} /> нет связи{stream.lastMessageAt ? `, данные на ${formatTime(new Date(stream.lastMessageAt).toISOString(), tz)}` : ''}</span>
          )}
          <Clock tz={tz} />
        </span>
      </header>

      <main className="board-main">
        <section className="calls" aria-label="Вызваны">
          <h1>Вызваны</h1>
          {announced ? (
            <div className="announce" role="alert">
              <span className="num">{announced.display_number}</span>
              <span className="arrow">→</span>
              <span className="win">{announced.window_name}</span>
            </div>
          ) : (
            <div className="announce idle">{list.length === 0 ? 'Пока никого не вызывали' : ''}</div>
          )}
          <ul className="call-list">
            {list.map((c) => (
              <li key={c.display_number}>
                <span className="num">{c.display_number}</span><span className="arrow">→</span><span className="win">{c.window_name}</span>
              </li>
            ))}
          </ul>
        </section>

        <aside className="side">
          <h2>Встаньте в очередь с телефона</h2>
          <QrImage value={joinUrl} />
          <div className="facts">
            <div>Ждут: <strong>{waiting}</strong> {plural(waiting)}</div>
            {load?.expected_wait_min != null && <div>Ожидание ~<strong>{load.expected_wait_min}</strong> мин</div>}
          </div>
        </aside>
      </main>

      {showLoad && (
        <footer className={`load-banner ${load.level}`}>
          <Icon name={load.level === 'overload' ? 'alert' : 'hourglass'} size={40} />
          {load.level === 'overload' ? 'Перегрузка: ожидание значительно дольше обычного. Спасибо за терпение' : 'Высокая нагрузка: ожидание дольше обычного. Спасибо за терпение'}
        </footer>
      )}
    </div>
  );
}

function plural(n: number): string {
  const m10 = n % 10;
  const m100 = n % 100;
  return m10 === 1 && m100 !== 11 ? 'человек' : m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14) ? 'человека' : 'человек';
}

/** Раз в сутки в 04:00 по времени отделения — полная перезагрузка страницы (защита от накопленных сбоев). */
function useDailyReload(tz: string | undefined) {
  const lastReloadDay = useRef<string | null>(null);
  useEffect(() => {
    const id = window.setInterval(() => {
      const parts = new Intl.DateTimeFormat('en-GB', { hour: '2-digit', minute: '2-digit', hour12: false, timeZone: tz }).format(new Date());
      const day = new Intl.DateTimeFormat('en-CA', { timeZone: tz }).format(new Date());
      if (parts === '04:00' && lastReloadDay.current !== day) { lastReloadDay.current = day; window.location.reload(); }
    }, 30_000);
    return () => window.clearInterval(id);
  }, [tz]);
}
