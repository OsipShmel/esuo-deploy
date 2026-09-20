/** Общие типы и запросы узла отделения, которые нужны нескольким экранам (admin, operator, board). */
import { api } from './http';

export interface BranchInfo {
  id: string;
  name: string;
  timezone: string;
  categories: string[];
  services: ServiceItem[];
}

export interface ServiceItem {
  id: string;
  code: string;
  name: string;
  number_prefix: string;
  slot_duration_min: number;
  slot_capacity: number;
  active: boolean;
}

export interface TicketView {
  ticket_id: string;
  display_number: string;
  service: { id: string; name: string };
  source: string;
  client_category: string;
  status: string;
  called_at: string | null;
  version: number;
}

export interface WindowView {
  id: string;
  name: string;
  status: 'open' | 'closing' | 'closed';
  employee_id: string | null;
  service_ids: string[];
  active_ticket: TicketView | null;
  version: number;
}

export interface QueueItem {
  ticket_id: string;
  display_number: string;
  service_id: string;
  service_name: string;
  source: string;
  client_category: string;
  status: string;
  issued_at: string;
  slot_start: string | null;
  eligible_from: string | null;
  due_at: string | null;
  overdue: boolean;
  position: number | null;
  window_id: string | null;
  target_window_id: string | null;
  called_at: string | null;
  call_count: number;
  version: number;
}

export interface QueueSnapshot {
  windows: WindowView[];
  queue: QueueItem[];
}

export const getBranch = () => api<BranchInfo>('/public/branch');
export const getWindows = () => api<WindowView[]>('/windows');
export const getQueue = (params: { service_id?: string; status?: string } = {}) => api<QueueItem[]>('/queue', { query: params });
