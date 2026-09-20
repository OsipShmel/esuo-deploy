/**
 * Нагрузка отделения (load-planning.md, 6.1). Ручки делает разработчик B: пока их нет на узле, запрос отвечает 404,
 * а экраны показывают заглушку вместо ошибки. Когда ручки появятся, экраны заработают без правок.
 */
import { api, ApiError } from './http';

export type LoadLevel = 'normal' | 'high' | 'overload';

export interface LoadForecastHour {
  hour_start: string;
  arrivals: number;
  booked: number;
  work_min: number;
  capacity_min: number;
  ratio: number;
  level: string;
  booking_quota?: number;
}

export interface LoadState {
  generated_at: string;
  date?: string;
  profile?: { code: string; name: string; source: 'calendar' | 'weekday' | 'manual' | string };
  level: LoadLevel;
  level_since?: string;
  clear_time_min?: number | null;
  ratio_now?: number | null;
  windows?: { open: number; planned: number; needed: number };
  causes?: { code: string; value?: number; threshold?: number }[];
  forecast?: LoadForecastHour[];
  duration_model?: { version: string; basis: string };
}

/** 404 / 405 — ручка ещё не включена на узле: `null`; остальные ошибки пробрасываем. */
export async function optional<T>(request: () => Promise<T>): Promise<T | null> {
  try {
    return await request();
  } catch (e) {
    if (e instanceof ApiError && (e.status === 404 || e.code === 'NOT_FOUND' || e.status === 405)) return null;
    throw e;
  }
}

export const getLoad = () => optional(() => api<LoadState>('/admin/load'));
export const setLoadProfile = (profile: string | null) => api<LoadState>('/admin/load/profile', { method: 'PUT', body: { profile } });

export const LOAD_CAUSE_TEXT: Record<string, string> = {
  flow_above_forecast: 'Клиентов больше, чем обычно в это время',
  slow_service: 'Обслуживание идёт медленнее обычного',
  few_windows: 'Открыто окон меньше, чем нужно',
  booking_peak: 'На это время много предварительных записей',
  overdue_backlog: 'Накопились просроченные талоны',
};

export const PROFILE_SOURCE_TEXT: Record<string, string> = {
  calendar: 'по календарю центра',
  weekday: 'по дню недели',
  manual: 'выбран вручную',
};
