/**
 * Типы и клиент экрана `admin`. Написаны вручную по постановке (OpenAPI пока нет);
 * когда появится `docs/openapi/node.yaml`, модуль заменяется сгенерированным клиентом.
 */
import { api } from '@shared/api/http';
import { optional } from '@shared/api/load';

// ------------------------------------------------------------------ общие

export * from '@shared/api/node';
import type { ServiceItem } from '@shared/api/node';

// ------------------------------------------------------------------ аналитика

export interface Period {
  from?: string;
  to?: string;
}

export interface Totals {
  issued: number;
  served: number;
  cancelled: number;
  no_show: number;
  active: number;
  avg_wait_sec: number | null;
  max_wait_sec: number | null;
  avg_service_sec: number | null;
}

export interface SourceStat {
  source: string;
  issued: number;
  served: number;
  avg_wait_sec: number | null;
  max_wait_sec: number | null;
}

export interface ServiceStat {
  service_id: string;
  service_name: string;
  issued: number;
  served: number;
  avg_wait_sec: number | null;
  max_wait_sec: number | null;
  avg_service_sec: number | null;
}

export interface Summary {
  from: string;
  to: string;
  totals: Totals;
  by_source: SourceStat[];
  by_service: ServiceStat[];
}

export interface WindowStat {
  window_id: string;
  name: string;
  open_sec: number;
  serving_sec: number;
  idle_sec: number;
  served: number;
  avg_service_sec: number | null;
  utilization: number;
}

export type DeviationType =
  | 'overdue_ticket' | 'no_show' | 'long_service' | 'slow_window' | 'idle_window' | 'service_without_window' | 'open_incident';

export interface DeviationItem {
  type: DeviationType;
  [field: string]: unknown;
}

export interface Deviations {
  generated_at: string;
  from: string;
  to: string;
  counts: Partial<Record<DeviationType, number>>;
  items: DeviationItem[];
}

export interface UnfinishedItem {
  ticket_id: string;
  display_number: string;
  source: string;
  status: string;
  service_id: string;
  service_name: string;
  issued_at: string;
  slot_start: string | null;
  due_at: string | null;
  window_id: string | null;
}

export const getSummary = (p: Period) => api<Summary>('/admin/analytics/summary', { query: { ...p } });
export const getWindowStats = (p: Period) => api<WindowStat[]>('/admin/analytics/windows', { query: { ...p } });
export const getDeviations = (p: Period) => api<Deviations>('/admin/analytics/deviations', { query: { ...p } });
export const getUnfinished = (p: Period) => api<UnfinishedItem[]>('/admin/analytics/unfinished', { query: { ...p } });

// ------------------------------------------------------------------ инциденты

export interface Incident {
  id: string;
  kind: 'technical' | 'operational';
  description: string;
  window_id: string | null;
  ticket_id: string | null;
  status: 'open' | 'resolved';
  created_at: string;
  resolved_at: string | null;
}

export const resolveIncident = (id: string) => api<Incident>(`/admin/incidents/${id}`, { method: 'PATCH', body: { status: 'resolved' } });

// ------------------------------------------------------------------ конфигурация

export interface Bound {
  min: number;
  max: number;
}

export type ConfigValue = number | string | boolean | { [key: string]: ConfigValue };

export interface BranchConfig {
  central_version: number;
  effective_version: number;
  central: Record<string, ConfigValue>;
  local_overrides: Record<string, ConfigValue>;
  bounds: Record<string, Bound>;
  effective: Record<string, ConfigValue>;
}

export const getConfig = () => api<BranchConfig>('/admin/config');
export const putConfig = (overrides: Record<string, ConfigValue>) => api<BranchConfig>('/admin/config', { method: 'PUT', body: overrides });

// ------------------------------------------------------------------ справочники

export interface WindowItem {
  id: string;
  name: string;
  status: string;
  active: boolean;
  current_employee_id: string | null;
  service_ids: string[];
}

export interface EmployeeItem {
  id: string;
  full_name: string;
  active: boolean;
}

export interface ServiceInput {
  code?: string;
  name: string;
  number_prefix: string;
  slot_duration_min?: number;
  slot_capacity?: number;
  active?: boolean;
}

const inactive = (include: boolean) => ({ include_inactive: include || undefined });

export const listServices = (include: boolean) => api<ServiceItem[]>('/admin/services', { query: inactive(include) });
export const createService = (b: ServiceInput) => api<ServiceItem>('/admin/services', { method: 'POST', body: b });
export const updateService = (id: string, b: ServiceInput) => api<ServiceItem>(`/admin/services/${id}`, { method: 'PUT', body: b });
export const deleteService = (id: string) => api<ServiceItem>(`/admin/services/${id}`, { method: 'DELETE' });

export const listWindows = (include: boolean) => api<WindowItem[]>('/admin/windows', { query: inactive(include) });
export const createWindow = (b: { name: string; service_ids: string[] }) => api<WindowItem>('/admin/windows', { method: 'POST', body: b });
export const renameWindow = (id: string, name: string) => api<WindowItem>(`/admin/windows/${id}`, { method: 'PUT', body: { name } });
export const setWindowServices = (id: string, service_ids: string[]) =>
  api<WindowItem>(`/admin/windows/${id}/services`, { method: 'PUT', body: { service_ids } });
export const deleteWindow = (id: string) => api<WindowItem>(`/admin/windows/${id}`, { method: 'DELETE' });

export const listEmployees = (include: boolean) => api<EmployeeItem[]>('/admin/employees', { query: inactive(include) });
export const createEmployee = (b: { full_name: string; active?: boolean }) => api<EmployeeItem>('/admin/employees', { method: 'POST', body: b });
export const updateEmployee = (id: string, b: { full_name: string; active: boolean }) =>
  api<EmployeeItem>(`/admin/employees/${id}`, { method: 'PUT', body: b });
export const deleteEmployee = (id: string) => api<EmployeeItem>(`/admin/employees/${id}`, { method: 'DELETE' });

// ------------------------------------------------------------------ рекомендации

export type RecommendationReason = 'eta_overrun' | 'overdue_count' | 'no_open_window';

export interface RecommendationService {
  service_id: string;
  service_name: string;
  waiting: number;
  overdue: number;
  max_eta_overrun_sec: number | null;
  open_windows: number;
  reasons: RecommendationReason[];
  suggested_windows: { window_id: string; name: string }[];
}

export interface Recommendations {
  generated_at: string;
  recommend_open_window: boolean;
  overdue_min: number;
  overdue_count: number;
  services: RecommendationService[];
}

export const getRecommendations = () => api<Recommendations>('/admin/recommendations');

// ------------------------------------------------------------------ нагрузка и модель длительности (делает B)

export * from '@shared/api/load';

/** Ответ `GET /admin/model` (AdminModelController): модель приходит из центра, обучение на узле не запускается. */
export interface DurationModel {
  version: string | null;
  /** `active` — применяется; `disabled` — выключена переопределением отделения; `none` — не публиковалась. */
  status: 'active' | 'disabled' | 'none' | string;
  enabled?: boolean;
  source?: string;
  /** `false` — кнопку «Переобучить» не показываем. */
  trainable?: boolean;
  trained_at?: string;
  train_from?: string;
  train_to?: string;
  observations?: number;
  metrics?: Record<string, unknown>;
}

export const getModel = () => optional(() => api<DurationModel>('/admin/model'));
export const trainModel = () => api<DurationModel>('/admin/model/train', { method: 'POST' });
