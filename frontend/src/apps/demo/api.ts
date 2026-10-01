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

// ------------------------------------------------------------------ пульт сценариев (tools/demo-runner через узел)

export interface RunnerScenario {
  code: string;
  title: string;
  desc: string;
  /** `chaos` — рвёт связь или роняет уведомления; `tool` — служебная команда run.sh без параметров. */
  kind: 'scenario' | 'chaos' | 'tool';
  /** Сценарий принимает отрезок дня (`from`/`to`). */
  range?: boolean;
}

export interface RunnerRun {
  id: number;
  code: string;
  args: string[];
  state: 'running' | 'passed' | 'failed' | 'stopped';
  exit_code: number | null;
  started_at: string;
  finished_at: string | null;
  pass: number;
  fail: number;
  lines_total: number;
}

export interface RunnerLine {
  n: number;
  kind: 'pass' | 'fail' | 'step' | 'title' | 'watch' | 'fact' | 'note' | 'text';
  text: string;
}

export interface RunArgs { fast?: boolean; rate?: number; seed?: number; from?: string; to?: string }

/** `null` — пульт сценариев на узле не подключён (нет DEMO_MODE или адреса demo-runner). */
export const getRunnerScenarios = () => optional(() => api<{ scenarios: RunnerScenario[] }>('/admin/demo/runner/scenarios'));
export const startRun = (code: string, args: RunArgs) =>
  api<{ run: RunnerRun }>('/admin/demo/runner/runs', { method: 'POST', body: { code, args } });
export const stopRun = () => api<{ run: RunnerRun | null }>('/admin/demo/runner/runs/current/stop', { method: 'POST' });
