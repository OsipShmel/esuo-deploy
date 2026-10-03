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
  /** База отделения не отвечает столько секунд; цифры очереди — последние известные. null — отвечает. */
  db_unavailable_sec?: number | null;
  /** Сработавшие правила мониторинга (`sys.*`): готовый текст для человека. */
  alerts?: BranchAlert[];
  /** Строка «Узел»: несколько цифр последнего heartbeat; null — нода их ещё не присылает. */
  node?: NodeLine | null;
  /** Дашборд «ЕСУО · Отделение» на этом отделении; null — у центра не задан адрес Grafana. */
  dashboard_url?: string | null;
}

export interface NodeLine {
  cpu_pct: number | null;
  http_p95_ms: number | null;
  disk_free_mb: number | null;
  disk_total_mb: number | null;
  app_version: string | null;
  uptime_sec: number | null;
}

export interface BranchAlert {
  code: string;
  severity: 'info' | 'warning' | 'critical';
  text: string;
  since: string;
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

// ------------------------------------------------------------------ диагностика отделения

export interface LogPullResult {
  pull_id: string;
  lines: number;
  truncated: boolean;
  /** Логи в Grafana Explore; null — у центра не задан адрес Grafana. */
  grafana_url: string | null;
}

export interface Snapshot {
  id: string;
  branch_id: string;
  taken_at: string;
  requested_by: string;
  size_bytes: number;
  payload: Record<string, unknown>;
}

export const pullLogs = (branch: string, minutes: number) =>
  api<LogPullResult>(`/branches/${branch}/diagnostics/logs`, { method: 'POST', body: { minutes } });
export const takeSnapshot = (branch: string) => api<Snapshot>(`/branches/${branch}/diagnostics/snapshots`, { method: 'POST' });

// ------------------------------------------------------------------ уведомления, DLQ, МП

/** Защита (`circuit_breaker`) — состояние сейчас; счётчики и последняя ошибка — с начала работы центра. */
export interface NotificationStatus {
  /** failure_rate — по последним window_calls отправкам; отрицательная, пока отправок меньше нужного. */
  circuit_breaker: { state: 'CLOSED' | 'OPEN' | 'HALF_OPEN'; failure_rate: number; window_calls: number };
  counts: Record<string, number>;
  last_error: string | null;
  last_error_at: string | null;
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
