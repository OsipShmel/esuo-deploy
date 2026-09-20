/** Токен талона и `device_id` в localStorage (S13: талон переживает закрытие вкладки). */

const TICKET_KEY = 'esuo.ticket';
const DEVICE_KEY = 'esuo.device';

export interface SavedTicket {
  branch: string;
  token: string;
  number: string;
}

function read(key: string): string | null {
  try { return window.localStorage.getItem(key); } catch { return null; }
}
function write(key: string, value: string | null): void {
  try {
    if (value === null) window.localStorage.removeItem(key); else window.localStorage.setItem(key, value);
  } catch { /* хранилище недоступно (приватный режим) — токен остаётся только в адресе */ }
}

export function savedTicket(): SavedTicket | null {
  const raw = read(TICKET_KEY);
  if (!raw) return null;
  try { return JSON.parse(raw) as SavedTicket; } catch { return null; }
}
export const saveTicket = (t: SavedTicket) => write(TICKET_KEY, JSON.stringify(t));
export const forgetTicket = () => write(TICKET_KEY, null);

/** Случайный UUID, создаётся один раз: по нему сервер не даёт взять второй активный QR-талон. */
export function deviceId(): string {
  let id = read(DEVICE_KEY);
  if (!id) {
    id = crypto.randomUUID();
    write(DEVICE_KEY, id);
  }
  return id;
}
