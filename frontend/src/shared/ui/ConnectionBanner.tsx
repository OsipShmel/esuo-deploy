import type { StreamStatus } from '../sse/useEventStream';
import { formatTime } from '../format/time';
import { Icon } from './Icon';
import './components.css';

/** Обрыв связи виден: «нет связи, данные на 10:42» (принцип 4). */
export function ConnectionBanner({ status, tz }: { status: StreamStatus; tz?: string }) {
  if (status.state === 'live') return null;
  const at = status.lastMessageAt ? formatTime(new Date(status.lastMessageAt).toISOString(), tz) : null;
  const polling = status.state === 'polling';
  return (
    <div className="banner tone-warn" role="status">
      <Icon name="wifiOff" />
      <div className="banner-body">
        {polling ? 'Живое обновление недоступно — данные обновляются раз в 10 секунд.' : 'Нет связи, переподключаемся…'}
        {at && <small> Данные на {at}.</small>}
      </div>
    </div>
  );
}
