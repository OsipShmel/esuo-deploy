import { useEffect, useRef, useState } from 'react';
import type { BoardCall } from './api';
import { playCallSignal } from './sound';

const SHOW_MS = 10_000;
const MIN_SHOW_MS = 4_000;
const MAX_PENDING = 5;

/**
 * Новый вызов — это талон, которого не было, или рост `call_count` (повторный вызов). Он 10 секунд показывается
 * в верхнем блоке крупно со звуком; если вызовов несколько, они идут по очереди, каждый не меньше 4 секунд.
 * Первый снапшот только запоминается: уже стоящие вызовы после запуска табло не объявляются.
 */
export function useAnnouncements(calls: BoardCall[] | undefined): BoardCall | null {
  const [current, setCurrent] = useState<BoardCall | null>(null);
  const seen = useRef<Map<string, string> | null>(null);
  const pending = useRef<BoardCall[]>([]);
  const timer = useRef<number | undefined>(undefined);
  const shownAt = useRef(0);
  const showing = useRef(false);

  const pump = useRef(() => undefined as void);
  pump.current = () => {
    window.clearTimeout(timer.current);
    const item = pending.current.shift();
    if (!item) {
      showing.current = false;
      setCurrent(null);
      return;
    }
    showing.current = true;
    shownAt.current = Date.now();
    setCurrent(item);
    playCallSignal();
    timer.current = window.setTimeout(() => pump.current(), pending.current.length > 0 ? MIN_SHOW_MS : SHOW_MS);
  };

  useEffect(() => {
    if (!calls) return;
    const known = seen.current;
    // Буфер ограничен: не больше числа вызовов в снапшоте.
    seen.current = new Map(calls.map((c) => [c.display_number, `${c.called_at}|${c.call_count}`]));
    if (!known) return;

    // calls: новые первыми → объявляем от старого к новому.
    const fresh = calls.filter((c) => c.status === 'called' && known.get(c.display_number) !== `${c.called_at}|${c.call_count}`).reverse();
    if (fresh.length === 0) return;
    pending.current.push(...fresh);
    if (pending.current.length > MAX_PENDING) pending.current.splice(0, pending.current.length - MAX_PENDING);

    if (!showing.current) {
      pump.current();
    } else {
      // Уже показываем вызов, а за ним есть очередь: сокращаем показ до минимума в 4 секунды.
      window.clearTimeout(timer.current);
      timer.current = window.setTimeout(() => pump.current(), Math.max(0, MIN_SHOW_MS - (Date.now() - shownAt.current)));
    }
  }, [calls]);

  useEffect(() => () => window.clearTimeout(timer.current), []);
  return current;
}
