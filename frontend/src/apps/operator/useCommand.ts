import { useState } from 'react';
import { ApiError } from '@shared/api/http';

/**
 * Запуск команды оператора: одна команда за раз, ошибка — в `error`. `409 STALE_STATE` («данные изменились»)
 * сразу обновляет очередь — оператор видит актуальное состояние и повторяет действие.
 */
export function useCommand(refresh: () => void) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  async function run<T>(action: () => Promise<T>): Promise<T | undefined> {
    if (busy) return undefined;
    setBusy(true);
    setError(null);
    try {
      const result = await action();
      refresh();
      return result;
    } catch (e) {
      setError(e);
      if (e instanceof ApiError && (e.code === 'STALE_STATE' || e.code === 'INVALID_STATE_TRANSITION' || e.code === 'WINDOW_BUSY')) refresh();
      return undefined;
    } finally {
      setBusy(false);
    }
  }
  return { busy, error, run, clearError: () => setError(null) };
}
