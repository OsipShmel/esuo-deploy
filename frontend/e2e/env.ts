export const NODE = process.env.E2E_NODE_URL ?? 'http://localhost:8080';
export const CENTER = process.env.E2E_CENTER_URL ?? 'http://localhost:8081';
export const BRANCH = process.env.E2E_BRANCH ?? 'b01';

/** Дата «завтра» в часовом поясе отделения: на сегодня слотов может уже не быть. */
export function tomorrow(tz = 'Europe/Moscow'): string {
  const d = new Date(Date.now() + 24 * 3600_000);
  return new Intl.DateTimeFormat('en-CA', { year: 'numeric', month: '2-digit', day: '2-digit', timeZone: tz }).format(d);
}
