import { STATUS_LABEL, SOURCE_LABEL, WINDOW_STATUS_LABEL, label } from '../format/labels';
import { Icon, type IconName } from './Icon';
import './components.css';

type Tone = 'muted' | 'accent' | 'ok' | 'warn' | 'danger' | 'booked';

const STATUS: Record<string, { tone: Tone; icon: IconName; pulse?: boolean }> = {
  booked: { tone: 'booked', icon: 'calendar' },
  waiting: { tone: 'muted', icon: 'clock' },
  called: { tone: 'accent', icon: 'bell', pulse: true },
  serving: { tone: 'ok', icon: 'person' },
  done: { tone: 'muted', icon: 'check' },
  cancelled: { tone: 'muted', icon: 'cross' },
  no_show: { tone: 'danger', icon: 'cross' },
};

/** Статус талона: цвет + значок + слово (принцип 3 — цвет не единственный носитель смысла). */
export function StatusChip({ status, text }: { status: string; text?: string }) {
  const s = STATUS[status] ?? { tone: 'muted' as Tone, icon: 'clock' as IconName };
  return (
    <span className={`chip tone-${s.tone}${s.pulse ? ' pulse' : ''}`}>
      <Icon name={s.icon} size={14} />
      {text ?? label(STATUS_LABEL, status)}
    </span>
  );
}

const SOURCE: Record<string, { tone: Tone; icon: IconName }> = {
  appointment: { tone: 'booked', icon: 'calendar' },
  qr: { tone: 'accent', icon: 'qr' },
  walkin: { tone: 'warn', icon: 'person' },
};

export function SourceBadge({ source }: { source: string }) {
  const s = SOURCE[source] ?? { tone: 'muted' as Tone, icon: 'person' as IconName };
  return (
    <span className={`chip tone-${s.tone}`}>
      <Icon name={s.icon} size={14} />
      {label(SOURCE_LABEL, source)}
    </span>
  );
}

const WINDOW: Record<string, Tone> = { open: 'ok', closing: 'warn', closed: 'muted' };

export function WindowStatusChip({ status }: { status: string }) {
  return <span className={`chip tone-${WINDOW[status] ?? 'muted'}`}>{label(WINDOW_STATUS_LABEL, status)}</span>;
}
