import { useEffect, useRef, useState } from 'react';
import { api } from './http';

/** Демо-часы узла (frontend-concept 5.4): `GET /public/demo/clock` → `{enabled, now, rate}`. */
export interface DemoClockState {
  enabled: boolean;
  now: string;
  rate: number;
}

export const getDemoClock = () => api<DemoClockState>('/public/demo/clock');

/**
 * Текущее время в шкале отделения. В демо-режиме — время демо-часов: состояние запрашивается при загрузке и раз в 10 с,
 * между запросами время идёт локально со скоростью `rate`. Иначе (или если ручки нет) — часы браузера.
 */
export function useClockNow(tickMs = 1000): { now: number; enabled: boolean; rate: number } {
  const anchor = useRef<{ virtual: number; real: number; rate: number } | null>(null);
  const [state, setState] = useState(() => ({ now: Date.now(), enabled: false, rate: 1 }));

  useEffect(() => {
    let disposed = false;
    const sync = async () => {
      try {
        const c = await getDemoClock();
        if (disposed) return;
        anchor.current = c.enabled ? { virtual: new Date(c.now).getTime(), real: Date.now(), rate: c.rate } : null;
      } catch {
        anchor.current = null;                          // 404 или нет связи — обычное время
      }
    };
    void sync();
    const syncId = window.setInterval(() => void sync(), 10_000);
    const tickId = window.setInterval(() => {
      const a = anchor.current;
      setState(a
        ? { now: a.virtual + (Date.now() - a.real) * a.rate, enabled: true, rate: a.rate }
        : { now: Date.now(), enabled: false, rate: 1 });
    }, tickMs);
    return () => { disposed = true; window.clearInterval(syncId); window.clearInterval(tickId); };
  }, [tickMs]);

  return state;
}
