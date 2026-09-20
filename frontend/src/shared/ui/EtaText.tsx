import { formatTime } from '../format/time';

/**
 * Ожидаемое время вызова (концепция 3.5): «около 12 мин», «вот-вот», при `none` — «определится, когда откроется окно»,
 * при `slot` — время записи. Ноль минут не показываем.
 */
export function EtaText({ eta, confidence, slotStart, now, tz }: {
  eta: string | null | undefined; confidence: string | null | undefined; slotStart?: string | null; now: number; tz?: string;
}) {
  if (confidence === 'slot' || (slotStart && !eta)) {
    return <>запись на {formatTime(eta ?? slotStart, tz)}, подтвердите приход в отделении</>;
  }
  if (!eta || confidence === 'none') return <>время вызова определится, когда откроется окно</>;
  const min = Math.round((new Date(eta).getTime() - now) / 60_000);
  const rough = confidence === 'low' ? ' (ориентировочно)' : '';
  if (min <= 0) return <>вот-вот{rough}</>;
  return <>около {min} мин, к {formatTime(eta, tz)}{rough}</>;
}
