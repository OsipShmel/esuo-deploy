/**
 * Ручки демо-пульта. Часть из них описана в load-planning.md и на узле пока не реализована (нагрузка) — такой
 * запрос отвечает 404, а пульт показывает на его месте понятную заглушку, а не ошибку. `/admin/demo/reset` и
 * `/public/demo/status` (stage 6) уже есть на узле и требуют `DEMO_MODE=true`.
 */
import { api } from '@shared/api/http';
import { optional } from '@shared/api/load';
import type { DemoClockState } from '@shared/api/demoClock';

export const getCenterLink = () => optional(() => api<{ center_link: boolean }>('/public/demo/status'));
export const setClock = (body: { rate?: number; now?: string }) => api<DemoClockState>('/admin/demo/clock', { method: 'PUT', body });
export const resetDemo = (scenario: string | null) =>
  api<{ reset: boolean; scenario: string | null }>('/admin/demo/reset', { method: 'POST', body: { scenario } });
