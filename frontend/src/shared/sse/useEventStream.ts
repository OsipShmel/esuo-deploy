import { useEffect, useRef, useState } from 'react';

/**
 * Хук SSE (дизайн-док 9.6, концепция раздел 6): `EventSource` переподключается сам, после переподключения сервер
 * присылает новый `snapshot`. После `POLL_AFTER` подряд неудачных попыток хук закрывает поток и переходит в `polling`:
 * вызывающий опрашивает GET, а хук раз в `RETRY_MS` пробует вернуть SSE.
 */
export type StreamState = 'connecting' | 'live' | 'polling';

const POLL_AFTER = 3;
const RETRY_MS = 30_000;

export interface StreamOptions {
  /** Полный URL потока; `null` — не подключаться. Токен для SSE передаётся в query (`access_token`). */
  url: string | null;
  /** Имена именованных событий, кроме `snapshot` и `heartbeat`. */
  events: readonly string[];
  onSnapshot?: (data: unknown) => void;
  onEvent?: (name: string, data: unknown, lastEventId: string) => void;
}

export interface StreamStatus {
  state: StreamState;
  /** Момент последнего сообщения (включая heartbeat) — для полосы «нет связи, данные на 10:42». */
  lastMessageAt: number | null;
}

export function useEventStream(opts: StreamOptions): StreamStatus {
  const [status, setStatus] = useState<StreamStatus>({ state: 'connecting', lastMessageAt: null });
  const handlers = useRef(opts);
  handlers.current = opts;
  const eventsKey = opts.events.join('|');

  useEffect(() => {
    const url = opts.url;
    if (!url) return undefined;
    let source: EventSource | null = null;
    let failures = 0;
    let retryTimer: number | undefined;
    let disposed = false;

    const parse = (raw: string): unknown => {
      try {
        return JSON.parse(raw);
      } catch {
        return raw;
      }
    };
    const touch = (state: StreamState) => setStatus({ state, lastMessageAt: Date.now() });

    const connect = () => {
      if (disposed) return;
      setStatus((s) => ({ ...s, state: 'connecting' }));
      const es = new EventSource(url);
      source = es;
      es.addEventListener('snapshot', (e) => {
        failures = 0;
        touch('live');
        handlers.current.onSnapshot?.(parse((e as MessageEvent<string>).data));
      });
      es.addEventListener('heartbeat', () => {
        failures = 0;
        touch('live');
      });
      // Узел перезапускается: закрываем поток и возвращаемся через 1–3 с (дизайн-док 9.6).
      es.addEventListener('reconnect', () => {
        es.close();
        setStatus((s) => ({ ...s, state: 'connecting' }));
        retryTimer = window.setTimeout(connect, 1000 + Math.random() * 2000);
      });
      for (const name of eventsKey ? eventsKey.split('|') : []) {
        es.addEventListener(name, (e) => {
          const m = e as MessageEvent<string>;
          touch('live');
          handlers.current.onEvent?.(name, parse(m.data), m.lastEventId);
        });
      }
      es.onerror = () => {
        failures += 1;
        if (failures >= POLL_AFTER) {
          es.close();
          setStatus((s) => ({ ...s, state: 'polling' }));
          retryTimer = window.setTimeout(connect, RETRY_MS);
        } else {
          setStatus((s) => ({ ...s, state: 'connecting' }));
        }
      };
    };

    connect();
    return () => {
      disposed = true;
      source?.close();
      if (retryTimer) window.clearTimeout(retryTimer);
    };
    // eventsKey — стабильный ключ списка событий
  }, [opts.url, eventsKey]);

  return status;
}
