/** Общий HTTP-клиент: базовый адрес `/api/v1` того же хоста, `application/problem+json` (дизайн-док 9.2). */

export interface FieldViolation {
  field?: string;
  message?: string;
}

/** Ошибка API: ветвиться нужно по `code`, а не по тексту. */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly title: string;
  readonly detail: string;
  readonly requestId: string | null;
  readonly errors: FieldViolation[];

  constructor(init: { status: number; code: string; title: string; detail?: string; requestId?: string | null; errors?: FieldViolation[] }) {
    super(init.detail || init.title);
    this.name = 'ApiError';
    this.status = init.status;
    this.code = init.code;
    this.title = init.title;
    this.detail = init.detail ?? '';
    this.requestId = init.requestId ?? null;
    this.errors = init.errors ?? [];
  }
}

export const API_BASE = '/api/v1';

type TokenProvider = () => string | null;
let tokenProvider: TokenProvider = () => null;
let unauthorizedHandler: () => void = () => undefined;

export function configureHttp(opts: { getToken: TokenProvider; onUnauthorized: () => void }): void {
  tokenProvider = opts.getToken;
  unauthorizedHandler = opts.onUnauthorized;
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  body?: unknown;
  query?: Record<string, string | number | boolean | null | undefined>;
  headers?: Record<string, string>;
  /** Для `/auth/login`: 401 здесь — неверный пароль, а не истёкшая сессия. */
  skipUnauthorizedHandler?: boolean;
  signal?: AbortSignal;
}

export function buildUrl(path: string, query?: RequestOptions['query']): string {
  const params = new URLSearchParams();
  for (const [k, v] of Object.entries(query ?? {})) {
    if (v !== undefined && v !== null && v !== '') params.set(k, String(v));
  }
  const qs = params.toString();
  return `${API_BASE}${path}${qs ? `?${qs}` : ''}`;
}

export async function api<T>(path: string, opts: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json', ...opts.headers };
  const token = tokenProvider();
  if (token) headers.Authorization = `Bearer ${token}`;
  if (opts.body !== undefined) headers['Content-Type'] = 'application/json';

  let response: Response;
  try {
    response = await fetch(buildUrl(path, opts.query), {
      method: opts.method ?? 'GET',
      headers,
      body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
      signal: opts.signal,
    });
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') throw e;
    throw new ApiError({ status: 0, code: 'NETWORK_ERROR', title: 'Нет связи с сервером', detail: 'Проверьте подключение и повторите.' });
  }

  if (response.ok) {
    if (response.status === 204) return undefined as T;
    return (await response.json()) as T;
  }

  const problem = await readProblem(response);
  if (response.status === 401 && !opts.skipUnauthorizedHandler) unauthorizedHandler();
  throw problem;
}

async function readProblem(response: Response): Promise<ApiError> {
  let body: Record<string, unknown> = {};
  try {
    body = (await response.json()) as Record<string, unknown>;
  } catch {
    /* тело не JSON — прокси или контейнер отдали свою страницу */
  }
  const code = typeof body.code === 'string' ? body.code : fallbackCode(response.status);
  return new ApiError({
    status: response.status,
    code,
    title: typeof body.title === 'string' ? body.title : `Ошибка ${response.status}`,
    detail: typeof body.detail === 'string' ? body.detail : '',
    requestId: typeof body.request_id === 'string' ? body.request_id : null,
    errors: Array.isArray(body.errors) ? (body.errors as FieldViolation[]) : [],
  });
}

function fallbackCode(status: number): string {
  if (status === 401) return 'UNAUTHENTICATED';
  if (status === 403) return 'FORBIDDEN';
  if (status === 404) return 'NOT_FOUND';
  if (status === 503) return 'BRANCH_UNAVAILABLE';
  return status >= 500 ? 'INTERNAL_ERROR' : 'VALIDATION_ERROR';
}
