import { deadlineText } from '../format/time';
import { Icon } from './Icon';
import './components.css';

/** Дедлайн талона: «через 12 мин» / песочные часы «скоро» / восклицательный знак «просрочен на N мин». */
export function DeadlineHint({ dueAt, now }: { dueAt: string | null | undefined; now?: number }) {
  const d = deadlineText(dueAt, now);
  if (d.level === 'none') return <span className="muted">{d.text}</span>;
  return (
    <span className={`chip tone-${d.level === 'overdue' ? 'danger' : 'warn'}`}>
      <Icon name={d.level === 'overdue' ? 'alert' : 'hourglass'} size={14} />
      {d.text}
    </span>
  );
}
