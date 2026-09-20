/** Команды над окнами, общие для экранов operator и demo. */
import { api } from './http';
import type { TicketView, WindowView } from './node';

export const openWindow = (id: string, service_ids: string[]) =>
  api<WindowView>(`/windows/${id}/open`, { method: 'POST', body: { service_ids } });
export const closeWindow = (id: string, mode: 'graceful' | 'force') =>
  api<WindowView>(`/windows/${id}/close`, { method: 'POST', body: { mode } });
export const callNext = (windowId: string) => api<TicketView>(`/windows/${windowId}/call-next`, { method: 'POST' });

