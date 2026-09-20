import { useQuery, useQueryClient } from '@tanstack/react-query';
import { accessToken } from './session';
import { getQueue, getWindows, type QueueItem, type QueueSnapshot, type WindowView } from './node';
import { useEventStream, type StreamStatus } from '../sse/useEventStream';

/** События узла, после которых очередь и окна перечитываются (снимок целиком — проще и надёжнее патчей). */
export const QUEUE_EVENTS = [
  'ticket.created', 'ticket.checked_in', 'ticket.called', 'ticket.recalled', 'ticket.serving', 'ticket.completed',
  'ticket.requeued', 'ticket.redirected', 'ticket.postponed', 'ticket.cancelled', 'ticket.no_show',
  'window.opened', 'window.closing', 'window.closed',
] as const;

export interface LiveQueue {
  windows: WindowView[] | undefined;
  queue: QueueItem[] | undefined;
  loading: boolean;
  error: unknown;
  stream: StreamStatus;
  refresh: () => void;
}

/**
 * Очередь и окна отделения в реальном времени: SSE со снапшотом (`/operator/stream` или `/admin/stream`),
 * при недоступном SSE — опрос GET раз в 10 с (дизайн-док 9.6).
 */
export interface LiveQueueOptions {
  /** Дополнительные имена событий (журнал решений демо-пульта): они не перечитывают очередь. */
  extraEvents?: readonly string[];
  onEvent?: (name: string, data: unknown, id: string) => void;
}

export function useLiveQueue(streamPath: '/operator/stream' | '/admin/stream', opts: LiveQueueOptions = {}): LiveQueue {
  const qc = useQueryClient();
  const token = accessToken();

  const refresh = () => {
    void qc.invalidateQueries({ queryKey: ['queue'] });
    void qc.invalidateQueries({ queryKey: ['windows', 'live'] });
  };

  const stream = useEventStream({
    url: token ? `/api/v1${streamPath}?access_token=${encodeURIComponent(token)}` : null,
    events: opts.extraEvents ? [...QUEUE_EVENTS, ...opts.extraEvents] : QUEUE_EVENTS,
    onSnapshot: (data) => {
      const s = data as Partial<QueueSnapshot>;
      if (s.queue) qc.setQueryData(['queue'], s.queue);
      if (s.windows) qc.setQueryData(['windows', 'live'], s.windows);
    },
    onEvent: (name, data, id) => {
      if ((QUEUE_EVENTS as readonly string[]).includes(name)) refresh();
      opts.onEvent?.(name, data, id);
    },
  });
  const refetchInterval = stream.state === 'polling' ? 10_000 : false;
  const queue = useQuery({ queryKey: ['queue'], queryFn: () => getQueue(), refetchInterval });
  const windows = useQuery({ queryKey: ['windows', 'live'], queryFn: getWindows, refetchInterval });

  return {
    windows: windows.data, queue: queue.data, loading: queue.isLoading || windows.isLoading,
    error: queue.error ?? windows.error, stream, refresh,
  };
}
