import { useClockNow } from '../api/demoClock';
import { formatTime } from '../format/time';

/** Часы: в демо-режиме показывают время демо-часов и множитель («10:42 ×60»), иначе — обычное время. */
export function Clock({ tz, seconds = false }: { tz?: string; seconds?: boolean }) {
  const { now, enabled, rate } = useClockNow();
  const text = seconds
    ? new Intl.DateTimeFormat('ru-RU', { hour: '2-digit', minute: '2-digit', second: '2-digit', timeZone: tz }).format(new Date(now))
    : formatTime(new Date(now).toISOString(), tz);
  return <time dateTime={new Date(now).toISOString()}>{text}{enabled && rate !== 1 ? <span className="muted"> ×{rate}</span> : null}</time>;
}
