import { Icon, type IconName } from './Icon';

/** Уровень нагрузки (концепция 3.2): `normal` / `high` / `overload` — цвет, значок и слово. */
const LEVEL: Record<string, { tone: string; icon: IconName; text: string }> = {
  normal: { tone: 'ok', icon: 'check', text: 'Нормально' },
  high: { tone: 'warn', icon: 'hourglass', text: 'Высокая' },
  overload: { tone: 'danger', icon: 'alert', text: 'Перегрузка' },
};

export function LoadLevelBadge({ level }: { level: string | null | undefined }) {
  if (!level) return null;
  const l = LEVEL[level];
  if (!l) return null;
  return <span className={`chip tone-${l.tone}`}><Icon name={l.icon} size={14} />{l.text}</span>;
}
