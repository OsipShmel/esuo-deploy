import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';

interface Toast { text: string; error: boolean }
const ToastContext = createContext<(text: string, error?: boolean) => void>(() => undefined);

export const useToast = () => useContext(ToastContext);

/** Всплывающее сообщение внизу справа (результат действия: «Опубликована версия v103», итог повтора DLQ). */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [toast, setToast] = useState<Toast | null>(null);
  const show = useCallback((text: string, error = false) => setToast({ text, error }), []);
  useEffect(() => {
    if (!toast) return undefined;
    const id = window.setTimeout(() => setToast(null), 5000);
    return () => window.clearTimeout(id);
  }, [toast]);
  return (
    <ToastContext.Provider value={show}>
      {children}
      <div role="status" aria-live="polite">{toast && <div className={`toast${toast.error ? ' error' : ''}`}>{toast.text}</div>}</div>
    </ToastContext.Provider>
  );
}
