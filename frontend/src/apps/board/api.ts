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
}

export interface BranchLoad {
  timezone: string;
  name: string;
  load?: { level: string; expected_wait_min?: number | null } | null;
}

export const getBoard = () => api<BoardSnapshot>('/public/board');
export const getBranchLoad = () => api<BranchLoad>('/public/branch');
