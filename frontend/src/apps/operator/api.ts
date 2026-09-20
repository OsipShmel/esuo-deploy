/** Команды оператора (дизайн-док 9.3). Все команды над талоном передают `expected_version`. */
import { api } from '@shared/api/http';
import type { TicketView, WindowView } from '@shared/api/node';

export * from '@shared/api/node';

export const openWindow = (id: string, service_ids: string[]) =>
  api<WindowView>(`/windows/${id}/open`, { method: 'POST', body: { service_ids } });
export const closeWindow = (id: string, mode: 'graceful' | 'force') =>
  api<WindowView>(`/windows/${id}/close`, { method: 'POST', body: { mode } });
export const callNext = (windowId: string) => api<TicketView>(`/windows/${windowId}/call-next`, { method: 'POST' });

export interface WalkinInput {
  service_id: string;
  client_category: string;
  call_now: boolean;
}
/** `Idempotency-Key` генерируется на одну отправку формы: повтор при обрыве не создаст второй талон. */
export const addWalkin = (windowId: string, body: WalkinInput, key: string) =>
  api<TicketView>(`/windows/${windowId}/walkin`, { method: 'POST', body, headers: { 'Idempotency-Key': key } });

type Command = 'start' | 'complete' | 'recall' | 'no-show' | 'check-in';
export const ticketCommand = (id: string, command: Command, expected_version: number) =>
  api<TicketView>(`/tickets/${id}/${command}`, { method: 'POST', body: { expected_version } });
export const requeueTicket = (id: string, mode: 'keep_position' | 'to_tail', expected_version: number) =>
  api<TicketView>(`/tickets/${id}/requeue`, { method: 'POST', body: { mode, expected_version } });
export const redirectTicket = (id: string, body: { service_id?: string; window_id?: string; reason?: string }, expected_version: number) =>
  api<TicketView>(`/tickets/${id}/redirect`, { method: 'POST', body: { ...body, expected_version } });

export const reportIncident = (body: { kind: 'technical' | 'operational'; description: string; window_id?: string; ticket_id?: string }) =>
  api<{ id: string }>('/incidents', { method: 'POST', body });
