import { ApiError } from '../api/http';
import { Icon } from './Icon';
import './components.css';

/** Фразы для частых кодов (концепция 3.5); остальное — `title` и `detail` из ProblemDetail. */
const PHRASES: Record<string, string> = {
  BRANCH_UNAVAILABLE: 'Отделение сейчас не на связи. Попробуйте через минуту — ваш запрос не потеряется.',
  STALE_STATE: 'Данные изменились. Обновили список — повторите действие.',
  NETWORK_ERROR: 'Нет связи с сервером. Проверьте подключение и повторите.',
  FORBIDDEN: 'Недостаточно прав для этого действия.',
  UNAUTHENTICATED: 'Сессия истекла. Войдите снова.',
};

export function problemText(error: unknown, phrases: Record<string, string> = PHRASES): { title: string; detail: string; requestId: string | null; code: string } {
  if (error instanceof ApiError) {
    const phrase = phrases[error.code];
    return { title: phrase ? phrase : error.title, detail: phrase ? '' : error.detail, requestId: error.requestId, code: error.code };
  }
  return { title: 'Что-то пошло не так', detail: error instanceof Error ? error.message : '', requestId: null, code: 'UNKNOWN' };
}

export function ProblemAlert({ error, phrases }: { error: unknown; phrases?: Record<string, string> }) {
  if (!error) return null;
  const p = problemText(error, phrases);
  return (
    <div className="banner tone-danger" role="alert">
      <Icon name="alert" />
      <div className="banner-body">
        <strong>{p.title}</strong>
        {p.detail && <div>{p.detail}</div>}
        {p.requestId && <small>Код обращения: {p.requestId}</small>}
      </div>
    </div>
  );
}
