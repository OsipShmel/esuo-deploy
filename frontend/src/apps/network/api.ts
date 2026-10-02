/**
 * Клиент API центра для экрана `network`. Типы написаны вручную по контроллерам `center-app` и `docs/openapi/center.yaml`
 * (в спецификации у большинства ответов нет схем). Ручки центра закрыты ролью NETWORK_ADMIN; токен подставляет AuthGate.
 */
import { api } from '@shared/api/http';

// ------------------------------------------------------------------ сеть

export interface BranchCard {
  id: string;
  name: string;
  online: boolean;
  last_heartbeat_at: string;
  seconds_since_heartbeat: number;
  events_attached: boolean;
  waiting: number;
  overdue: number;
  oldest_wait_sec: number;
  windows_open: number;
  windows_total: number;
  outbox_pending: number;
  config_version: number;
  effective_config_version: number;
  config_published_version: number | null;
  config_applied_version: number | null;
  load_level: string | null;
  clear_time_min: number | null;
  day_profile: string | null;
  demo_rate: number | null;
  anomalies: string[];
}

export interface Overview {
  generated_at: string;
  branches_total: number;
  branches_online: number;
  branches: BranchCard[];
}

export interface BranchSummary {
  id: string;
  name: string;
  timezone: string;
  online: boolean;
  services: { id: string; code: string; name: string }[];
  load_level: string | null;
}

export const getOverview = () => api<Overview>('/network/overview');
export const getBranches = () => api<BranchSummary[]>('/branches');

// ------------------------------------------------------------------ уведомления, DLQ, МП

export interface NotificationStatus {
  circuit_breaker: { state: 'CLOSED' | 'OPEN' | 'HALF_OPEN'; failure_rate: number };
  counts: Record<string, number>;
  last_error: string | null;
}

export interface DeadLetter {
  id: number;
  kind: 'notification' | 'mp_callback';
  key: string;
  branch_id: string;
  payload: Record<string, unknown>;
  attempts: number;
  reason: 'exhausted' | 'expired' | 'rejected' | string;
  last_error: string | null;
  created_at: string;
  resolved_at: string | null;
}

export interface MpRequest {
  mp_appointment_id: string;
  branch_id: string;
  status: string;
  published: boolean;
  display_number: string | null;
  reason: string | null;
  callback_attempts: number;
  created_at: string;
  updated_at: string;
}

export const getNotificationStatus = () => api<NotificationStatus>('/admin/notifications/status');
export const getDlq = (kind: DeadLetter['kind']) => api<DeadLetter[]>('/admin/dlq', { query: { kind, status: 'open' } });
export const retryDlq = (id: number) => api<{ result: 'delivered' | 'failed'; error?: string | null }>(`/admin/dlq/${id}/retry`, { method: 'POST' });
export const getMpRequests = () => api<MpRequest[]>('/admin/mp/requests', { query: { limit: 50 } });

// ------------------------------------------------------------------ конфигурация

export type Section = Record<string, unknown>;

export interface Sections {
  queue: Section | null;
  bounds: Section | null;
  planning: Section | null;
}

export interface BranchConfigStatus {
  branch_id: string;
  overrides: Sections;
  published_version: number | null;
  applied_version: number | null;
  applied_at: string | null;
  pending: boolean;
}

export interface ConfigOverview {
  global: Sections & { updated_at: string | null; updated_by: string | null };
  branches: BranchConfigStatus[];
}

export interface Published {
  version: number;
  published_to: string[];
}

export interface VersionView {
  version: number;
  branch_id: string;
  message: Record<string, unknown>;
  author: string | null;
  created_at: string;
}

export interface UserView {
  id: string;
  login: string;
  role: 'OPERATOR' | 'BRANCH_ADMIN';
  employee_id: string | null;
  full_name: string | null;
  active: boolean;
}

export const getConfig = () => api<ConfigOverview>('/config');
export const putGlobal = (body: Partial<Record<keyof Sections, Section | null>>) => api<Published>('/config/global', { method: 'PUT', body });
export const putBranch = (branch: string, body: Partial<Record<keyof Sections, Section | null>>) =>
  api<Published>(`/config/branches/${branch}`, { method: 'PUT', body });
export const getVersions = (branch?: string) => api<VersionView[]>('/config/versions', { query: { branch, limit: 50 } });
export const getUsers = (branch: string) => api<UserView[]>(`/branches/${branch}/users`);
export const createUser = (branch: string, body: { login: string; password: string; role: string; full_name: string }) =>
  api<{ user: UserView; config_version: number }>(`/branches/${branch}/users`, { method: 'POST', body });
export const updateUser = (branch: string, id: string, body: { password?: string; role?: string; active?: boolean }) =>
  api<{ user: UserView; config_version: number }>(`/branches/${branch}/users/${id}`, { method: 'PATCH', body });
