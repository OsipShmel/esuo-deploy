import { api } from './http';
import { saveSession, type Session } from './session';

interface LoginResponse {
  access_token: string;
  token_type: string;
  expires_at: string;
  role: string;
  employee_id: string | null;
}

export async function login(loginName: string, password: string): Promise<Session> {
  const r = await api<LoginResponse>('/auth/login', {
    method: 'POST',
    body: { login: loginName, password },
    skipUnauthorizedHandler: true,
  });
  const session: Session = { accessToken: r.access_token, role: r.role, employeeId: r.employee_id, expiresAt: r.expires_at };
  saveSession(session);
  return session;
}
