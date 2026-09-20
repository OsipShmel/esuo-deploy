/**
 * Ручки демо-пульта. Часть из них (нагрузка, сценарии, статус связи) описана в load-planning.md и на узле пока
 * не реализована: такой запрос отвечает 404, а пульт показывает на его месте понятную заглушку, а не ошибку.
 */
import { api } from '@shared/api/http';
import { optional } from '@shared/api/load';
import type { DemoClockState } from '@shared/api/demoClock';

export const getCenterLink = () => optional(() => api<{ center_link: boolean }>('/public/demo/status'));
export const setClock = (body: { rate?: number; now?: string }) => api<DemoClockState>('/admin/demo/clock', { method: 'PUT', body });
export const resetDemo = (scenario: string | null) => api<unknown>('/admin/demo/reset', { method: 'POST', body: { scenario } });
export const startScenario = (scenario: string) => api<unknown>('/admin/demo/scenario', { method: 'POST', body: { scenario } });
export const stopScenario = () => api<unknown>('/admin/demo/scenario', { method: 'DELETE' });
