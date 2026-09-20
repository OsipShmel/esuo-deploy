/** Сессия: токен в памяти и `sessionStorage` (постановка admin, правило 3). */

const KEY = 'esuo.session';

export interface Session {
  accessToken: string;
  role: string;
  employeeId: string | null;
  expiresAt: string;
}

let current: Session | null = null;

function safeStorage(): Storage | null {
  try {
    return window.sessionStorage;
  } catch {
    return null;
  }
}

export function loadSession(): Session | null {
  if (current) return current;
  const raw = safeStorage()?.getItem(KEY);
  if (!raw) return null;
  try {
    const s = JSON.parse(raw) as Session;
    if (new Date(s.expiresAt).getTime() <= Date.now()) {
      safeStorage()?.removeItem(KEY);
      return null;
    }
    current = s;
    return s;
  } catch {
    return null;
  }
}

export function saveSession(s: Session): void {
  current = s;
  safeStorage()?.setItem(KEY, JSON.stringify(s));
}

export function clearSession(): void {
  current = null;
  safeStorage()?.removeItem(KEY);
}

export function accessToken(): string | null {
  return loadSession()?.accessToken ?? null;
}
