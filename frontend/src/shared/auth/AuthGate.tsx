import { useCallback, useEffect, useState, type FormEvent, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { ApiError, configureHttp } from '../api/http';
import { login } from '../api/auth';
import { accessToken, clearSession, loadSession, type Session } from '../api/session';
import { ProblemAlert } from '../ui';

interface Props {
  title: string;
  /** Роли, которым разрешён этот экран; иначе — «нужны другие права». */
  roles: readonly string[];
  forbiddenText: string;
  defaultLogin?: string;
  children: (ctx: { session: Session; logout: () => void }) => ReactNode;
}

/** Вход и защита экрана: 401 в любом запросе возвращает на форму входа; токен — в памяти и `sessionStorage`. */
export function AuthGate({ title, roles, forbiddenText, defaultLogin = '', children }: Props) {
  const [session, setSession] = useState<Session | null>(() => {
    const s = loadSession();
    return s && roles.includes(s.role) ? s : null;
  });
  const [expired, setExpired] = useState(false);
  const qc = useQueryClient();

  const logout = useCallback(() => {
    clearSession();
    qc.clear();
    setSession(null);
  }, [qc]);

  useEffect(() => {
    configureHttp({ getToken: accessToken, onUnauthorized: () => { setExpired(true); logout(); } });
  }, [logout]);

  if (!session) {
    return <LoginForm title={title} roles={roles} forbiddenText={forbiddenText} defaultLogin={defaultLogin} expired={expired}
      onLoggedIn={(s) => { setExpired(false); setSession(s); }} />;
  }
  return <>{children({ session, logout })}</>;
}

function LoginForm({ title, roles, forbiddenText, defaultLogin, expired, onLoggedIn }: {
  title: string; roles: readonly string[]; forbiddenText: string; defaultLogin: string; expired: boolean; onLoggedIn: (s: Session) => void;
}) {
  const [name, setName] = useState(defaultLogin);
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const s = await login(name.trim(), password);
      if (!roles.includes(s.role)) {
        clearSession();
        setError(new ApiError({ status: 403, code: 'ROLE_NOT_ALLOWED', title: 'Этот экран вам недоступен', detail: forbiddenText }));
        return;
      }
      onLoggedIn(s);
    } catch (err) {
      setError(err instanceof ApiError && err.code === 'UNAUTHENTICATED'
        ? new ApiError({ status: 401, code: 'BAD_CREDENTIALS', title: 'Неверный логин или пароль', requestId: err.requestId })
        : err);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div style={{ minHeight: '100vh', display: 'grid', placeItems: 'center', padding: 16 }}>
      <form className="card" onSubmit={submit} style={{ width: 'min(400px, 100%)', display: 'grid', gap: 12 }}>
        <h1 style={{ margin: 0 }}>{title}</h1>
        {expired && <p className="muted" style={{ margin: 0 }}>Сессия истекла. Войдите снова.</p>}
        <label className="field">Логин
          <input value={name} onChange={(e) => setName(e.target.value)} autoComplete="username" required />
        </label>
        <label className="field">Пароль
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" required />
        </label>
        <ProblemAlert error={error} />
        <button className="btn btn-primary" type="submit" disabled={busy}>{busy ? 'Входим…' : 'Войти'}</button>
      </form>
    </div>
  );
}
