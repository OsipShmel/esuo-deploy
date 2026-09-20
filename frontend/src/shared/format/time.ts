/** Времена приходят в UTC; показываем в часовом поясе отделения (`GET /public/branch` → `timezone`). */
export const DEFAULT_TZ = 'Europe/Moscow';

export function formatTime(iso: string | null | undefined, tz: string = DEFAULT_TZ): string {
  if (!iso) return '—';
  return new Intl.DateTimeFormat('ru-RU', { hour: '2-digit', minute: '2-digit', timeZone: tz }).format(new Date(iso));
}

export function formatDateTime(iso: string | null | undefined, tz: string = DEFAULT_TZ): string {
  if (!iso) return '—';
  return new Intl.DateTimeFormat('ru-RU', {
    day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit', timeZone: tz,
  }).format(new Date(iso));
}

/** Дата `YYYY-MM-DD` «сегодня» в часовом поясе отделения. */
export function todayIn(tz: string = DEFAULT_TZ): string {
  return new Intl.DateTimeFormat('en-CA', { year: 'numeric', month: '2-digit', day: '2-digit', timeZone: tz }).format(new Date());
}

/** «через 12 мин» / «скоро» / «просрочен на 3 мин» — подписи дедлайна (концепция 3.2). */
export function deadlineText(dueIso: string | null | undefined, now: number = Date.now()): { text: string; level: 'none' | 'soon' | 'overdue' } {
  if (!dueIso) return { text: '—', level: 'none' };
  const diffMin = Math.round((new Date(dueIso).getTime() - now) / 60_000);
  if (diffMin < 0) return { text: `просрочен на ${Math.max(1, -diffMin)} мин`, level: 'overdue' };
  if (diffMin <= 5) return { text: 'скоро', level: 'soon' };
  return { text: `через ${diffMin} мин`, level: 'none' };
}
