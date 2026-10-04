import { formatSeconds } from '@shared/format/duration';
import type { BranchCard } from './api';

/** Тексты аномалий (постановка network, вкладка «Сеть»). */
export const ANOMALY: Record<string, (b: BranchCard) => string> = {
  offline: (b) => `${b.id}: нет связи с отделением`,
  wait_grows_without_tickets: (b) => `${b.id}: ожидание растёт, хотя клиентов мало — проверьте окна`,
  overdue_tickets: (b) => `${b.id}: ${b.overdue} просроченных талонов`,
  no_open_windows: (b) => `${b.id}: клиенты ждут, а окна закрыты`,
  outbox_lag: (b) => `${b.id}: события задерживаются в отделении`,
  config_pending: (b) => `${b.id}: конфигурация v${b.config_published_version ?? '?'} ещё не применена`,
  overload: (b) => `${b.id}: перегрузка дольше 15 минут`,
  db_unavailable: (b) => `${b.id}: данные очереди не обновляются ${formatSeconds(b.db_unavailable_sec ?? 0)} — база отделения не отвечает или ПК перегружен; связь есть`,
};

/** Подпись аномалии в итоге по сети: «без связи — 37». */
export const ANOMALY_TITLE: Record<string, string> = {
  offline: 'без связи',
  wait_grows_without_tickets: 'ожидание растёт без клиентов',
  overdue_tickets: 'просроченные талоны',
  no_open_windows: 'окна закрыты при очереди',
  outbox_lag: 'события задерживаются',
  config_pending: 'конфигурация не применена',
  overload: 'перегрузка дольше 15 мин',
  db_unavailable: 'база отделения не отвечает',
};

/** Красное на экране: отделение не может обслуживать или центр его не видит. */
export const DANGER = new Set(['offline', 'no_open_windows', 'overload', 'db_unavailable']);

/** Число по-русски, с пробелами между разрядами: 250 000. */
export const n = (v: number) => v.toLocaleString('ru-RU');

export function plural(v: number, one: string, few: string, many: string): string {
  const m10 = v % 10;
  const m100 = v % 100;
  if (m10 === 1 && m100 !== 11) return one;
  if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return few;
  return many;
}
