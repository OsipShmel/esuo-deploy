import { api } from '@shared/api/http';

export interface BoardCall {
  display_number: string;
  window_id: string;
  window_name: string;
  status: string;
  called_at: string;
  call_count: number;
}

export interface BoardSnapshot {
  branch_id: string;
  branch_name: string;
  calls: BoardCall[];
  waiting_by_service: Record<string, number>;
  qr_code: string;
  /** Готовая ссылка QR (абсолютная или относительно адреса табло); у старых узлов поля нет. */
  join_url?: string | null;
  /** Куда ведёт QR сейчас: `center` — через интернет, `local` — через Wi-Fi отделения. */
  qr_mode_effective?: 'center' | 'local' | null;
}

export interface BranchLoad {
  timezone: string;
  name: string;
  load?: { level: string; expected_wait_min?: number | null } | null;
}

export const getBoard = () => api<BoardSnapshot>('/public/board');
export const getBranchLoad = () => api<BranchLoad>('/public/branch');

/**
 * Ссылка для QR: ручной ?center= важнее всего; иначе join_url от узла (относительный — от адреса страницы);
 * узел без join_url — по-старому, страница входа на том же адресе, что и табло.
 */
export function boardJoinUrl(data: BoardSnapshot, centerOverride: string | null): string {
  const path = `/b/${data.branch_id}/join?code=${encodeURIComponent(data.qr_code)}`;
  if (centerOverride) return centerOverride.replace(/\/$/, '') + path;
  if (data.join_url) return new URL(data.join_url, window.location.origin).href;
  return window.location.origin + path;
}
