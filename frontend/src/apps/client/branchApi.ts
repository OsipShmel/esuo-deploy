/**
 * Клиентский API отделения. Один интерфейс, две реализации (постановка client): страница открыта с центра
 * (`/api/v1/branches/{b}/…`) или с узла отделения в Wi-Fi (`/api/v1/public/…`). Экраны о режиме не знают.
 */
import { api } from '@shared/api/http';

export interface BranchService {
  id: string;
  code: string;
  name: string;
  number_prefix: string;
  slot_duration_min?: number;
}

export interface BranchInfo {
  id: string;
  name: string;
  timezone: string;
  services: BranchService[];
  categories: string[];
  load?: { level: string; expected_wait_min?: number | null } | null;
}

export interface BranchSummary {
  id: string;
  name: string;
  timezone: string;
  online: boolean;
  services: BranchService[];
  load_level: string | null;
}

export interface ClientTicket {
  ticket_id: string;
  token: string;
  branch_id: string;
  display_number: string;
  service: { id: string; name: string };
  source: 'appointment' | 'qr' | 'walkin';
  client_category: string;
  status: 'booked' | 'waiting' | 'called' | 'serving' | 'done' | 'cancelled' | 'no_show';
  position: number | null;
  eta: string | null;
  eta_confidence: 'high' | 'medium' | 'low' | 'none' | 'slot' | null;
  slot_start: string | null;
  window: { id: string; name: string } | null;
  called_at: string | null;
  can_cancel: boolean;
  can_postpone: boolean;
  version: number;
  updated_at: string;
}

export interface Slot {
  slot_start: string;
  slot_end: string;
  remaining_capacity: number;
}

export interface BookInput { service_id: string; slot_start: string; client_category: string }
export interface QrInput { qr_code: string; service_id: string; client_category: string; device_id: string }

export interface BranchApi {
  readonly mode: 'center' | 'node';
  listBranches(): Promise<BranchSummary[]>;
  info(branch: string): Promise<BranchInfo>;
  slots(branch: string, serviceId: string, date: string): Promise<Slot[]>;
  book(branch: string, body: BookInput, key: string): Promise<ClientTicket>;
  qr(branch: string, body: QrInput, key: string): Promise<ClientTicket>;
  ticket(branch: string, token: string): Promise<ClientTicket>;
  cancel(branch: string, token: string): Promise<ClientTicket>;
  postpone(branch: string, token: string, minutes: number): Promise<ClientTicket>;
  checkIn(branch: string, token: string, qrCode: string): Promise<ClientTicket>;
  streamUrl(branch: string, token: string): string;
}

const idem = (key: string) => ({ 'Idempotency-Key': key });

/** Центр: интернет, `/api/v1/branches/{b}/…`. */
export const centerApi: BranchApi = {
  mode: 'center',
  listBranches: () => api<BranchSummary[]>('/branches'),
  info: (b) => api<BranchInfo>(`/branches/${b}/info`),
  slots: (b, serviceId, date) => api<Slot[]>(`/branches/${b}/slots`, { query: { service_id: serviceId, date } }),
  book: (b, body, key) => api<ClientTicket>(`/branches/${b}/appointments`, { method: 'POST', body, headers: idem(key) }),
  qr: (b, body, key) => api<ClientTicket>(`/branches/${b}/qr-tickets`, { method: 'POST', body, headers: idem(key) }),
  ticket: (b, t) => api<ClientTicket>(`/branches/${b}/tickets/${t}`),
  cancel: (b, t) => api<ClientTicket>(`/branches/${b}/tickets/${t}/cancel`, { method: 'POST' }),
  postpone: (b, t, minutes) => api<ClientTicket>(`/branches/${b}/tickets/${t}/postpone`, { method: 'POST', body: { minutes } }),
  checkIn: (b, t, qr) => api<ClientTicket>(`/branches/${b}/tickets/${t}/check-in`, { method: 'POST', body: { qr_code: qr } }),
  streamUrl: (b, t) => `/api/v1/branches/${b}/tickets/${t}/stream`,
};

/** Узел отделения: Wi-Fi зала, `/api/v1/public/…`; номер отделения в путях не участвует. */
export const nodeApi: BranchApi = {
  mode: 'node',
  listBranches: async () => {
    const i = await api<BranchInfo>('/public/branch');
    return [{ id: i.id, name: i.name, timezone: i.timezone, online: true, services: i.services, load_level: i.load?.level ?? null }];
  },
  info: () => api<BranchInfo>('/public/branch'),
  slots: (_b, serviceId, date) => api<Slot[]>('/public/slots', { query: { service_id: serviceId, date } }),
  book: (_b, body, key) => api<ClientTicket>('/public/appointments', { method: 'POST', body, headers: idem(key) }),
  qr: (_b, body, key) => api<ClientTicket>('/public/qr-tickets', { method: 'POST', body, headers: idem(key) }),
  ticket: (_b, t) => api<ClientTicket>(`/public/tickets/${t}`),
  cancel: (_b, t) => api<ClientTicket>(`/public/tickets/${t}/cancel`, { method: 'POST' }),
  postpone: (_b, t, minutes) => api<ClientTicket>(`/public/tickets/${t}/postpone`, { method: 'POST', body: { minutes } }),
  checkIn: (_b, t, qr) => api<ClientTicket>(`/public/tickets/${t}/check-in`, { method: 'POST', body: { qr_code: qr } }),
  streamUrl: (_b, t) => `/api/v1/public/tickets/${t}/stream`,
};

/** Режим определяем по хосту: узел отвечает на `GET /public/branch`, центр — 404. */
export async function detectApi(): Promise<BranchApi> {
  try {
    await api('/public/branch');
    return nodeApi;
  } catch {
    return centerApi;
  }
}
