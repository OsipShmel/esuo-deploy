import { useRef, useState } from 'react';
import { uuid } from '@shared/format/uuid';

/**
 * Отправка с `Idempotency-Key` (постановка client): ключ создаётся один раз на нажатие и повторяется при повторе
 * того же запроса (сеть оборвалась, 503). Новый ключ — после успеха или когда пользователь изменил ввод (`signature`).
 */
export function useIdempotentSubmit<T>(signature: string) {
  const state = useRef<{ key: string; signature: string }>({ key: uuid(), signature });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  if (state.current.signature !== signature) state.current = { key: uuid(), signature };

  async function submit(action: (key: string) => Promise<T>): Promise<T | undefined> {
    if (busy) return undefined;
    setBusy(true);
    setError(null);
    try {
      const result = await action(state.current.key);
      state.current = { key: uuid(), signature };
      return result;
    } catch (e) {
      setError(e);                                     // ключ сохраняется: «Повторить» отправит тот же запрос
      return undefined;
    } finally {
      setBusy(false);
    }
  }
  return { submit, busy, error, clearError: () => setError(null) };
}
