/** Описание параметров очереди (дизайн-док 6.9) и вспомогательные функции над вложенными значениями конфигурации. */
import type { Section } from './api';

export type FieldKind = 'int' | 'decimal' | 'bool' | 'enum';

export interface FieldDef {
  key: string;              // ключ верхнего уровня
  path: string[];           // полный путь (для target_wait_min — вложенный)
  label: string;
  unit?: string;
  kind: FieldKind;
  options?: { value: string; label: string }[];
}

const SOURCES: [string, string][] = [['qr', 'QR'], ['walkin', 'Живая очередь']];
const CATEGORIES: [string, string][] = [['default', 'обычный'], ['pensioner', 'пенсионер'], ['special', 'спец-категория']];

const targetWait: FieldDef[] = SOURCES.flatMap(([s, sl]) => CATEGORIES.map(([c, cl]): FieldDef => ({
  key: 'target_wait_min', path: ['target_wait_min', s, c], label: `Целевое ожидание: ${sl}, ${cl}`, unit: 'мин', kind: 'int',
})));

const int = (key: string, label: string, unit?: string): FieldDef => ({ key, path: [key], label, unit, kind: 'int' });

export const QUEUE_FIELDS: FieldDef[] = [
  ...targetWait,
  int('slack_min', 'Окно близких дедлайнов (score)', 'мин'),
  int('call_timeout_sec', 'Время подойти к окну', 'сек'),
  int('max_recalls', 'Повторных вызовов до неявки'),
  int('early_checkin_min', 'Допуск записи раньше слота', 'мин'),
  int('grace_late_min', 'Опоздание без потери приоритета', 'мин'),
  int('late_checkin_retention_min', 'Запись без прихода считается неявкой через', 'мин'),
  int('postpone_max_min', 'Откладывание не дольше', 'мин'),
  int('postpone_max_count', 'Откладываний на талон'),
  { key: 'scoring_strategy', path: ['scoring_strategy'], label: 'Стратегия выбора', kind: 'enum',
    options: [{ value: 'uniform', label: 'uniform — по дедлайну' }, { value: 'heuristic', label: 'heuristic — короткие первыми в пределах slack' }] },
  { key: 'late_policy', path: ['late_policy'], label: 'Опоздавшая запись', kind: 'enum',
    options: [{ value: 'convert_to_qr', label: 'стать обычным талоном' }, { value: 'reject', label: 'отказать' }] },
  { key: 'requeue_default_mode', path: ['requeue_default_mode'], label: 'Возврат в очередь по умолчанию', kind: 'enum',
    options: [{ value: 'keep_position', label: 'с сохранением места' }, { value: 'to_tail', label: 'в конец' }] },
  { key: 'auto_call_on_free', path: ['auto_call_on_free'], label: 'Автовызов при освобождении окна', kind: 'bool' },
  int('approaching_position_lte', 'Уведомление «очередь приближается»: позиция не больше'),
  int('approaching_eta_lte_min', '…или ожидание не больше', 'мин'),
  int('recommend_overdue_min', 'Рекомендация окна: задержка больше', 'мин'),
  int('recommend_overdue_count', 'Рекомендация окна: просроченных больше'),
  int('idle_alert_min', 'Окно без активности дольше', 'мин'),
  int('long_service_min', 'Долгое обслуживание дольше', 'мин'),
  int('heuristic_min_samples', 'Наблюдений для доверия прогнозу'),
  int('default_service_duration_sec', 'Длительность обслуживания, пока нет статистики', 'сек'),
  { key: 'slow_window_factor', path: ['slow_window_factor'], label: 'Окно медленнее нормы в', unit: 'раз', kind: 'decimal' },
];

/** Ключи, для которых центр может задать границы локального переопределения. */
export const BOUNDABLE: { key: string; label: string }[] = [
  { key: 'target_wait_min', label: 'Целевое ожидание, мин' },
  { key: 'slack_min', label: 'Окно близких дедлайнов, мин' },
  { key: 'call_timeout_sec', label: 'Время подойти к окну, сек' },
  { key: 'max_recalls', label: 'Повторных вызовов' },
  { key: 'early_checkin_min', label: 'Допуск записи раньше слота, мин' },
  { key: 'grace_late_min', label: 'Опоздание без потери приоритета, мин' },
  { key: 'postpone_max_min', label: 'Откладывание не дольше, мин' },
];

export const getIn = (obj: unknown, path: string[]): unknown =>
  path.reduce<unknown>((o, k) => (o && typeof o === 'object' ? (o as Record<string, unknown>)[k] : undefined), obj);

export function setIn(obj: Section, path: string[], value: unknown): Section {
  const [head, ...rest] = path;
  if (head === undefined) return obj;
  if (rest.length === 0) return { ...obj, [head]: value };
  const child = (obj[head] && typeof obj[head] === 'object' ? obj[head] : {}) as Section;
  return { ...obj, [head]: setIn(child, rest, value) };
}

/** Удаляет значение и пустые родительские объекты. */
export function deleteIn(obj: Section, path: string[]): Section {
  const [head, ...rest] = path;
  if (head === undefined) return obj;
  const next = { ...obj };
  if (rest.length === 0) {
    delete next[head];
    return next;
  }
  const child = obj[head];
  if (!child || typeof child !== 'object') return next;
  const cleaned = deleteIn(child as Section, rest);
  if (Object.keys(cleaned).length === 0) delete next[head]; else next[head] = cleaned;
  return next;
}

export const isEmpty = (s: Section | null | undefined): boolean => !s || Object.keys(s).length === 0;
