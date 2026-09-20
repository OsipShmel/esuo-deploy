import { useEffect, useState } from 'react';
import { Icon } from './Icon';

/** Переключатели «тема» и «крупный шрифт и высокий контраст» (принцип 6). Выбор хранится в localStorage. */
function stored(key: string): string | null {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}
function store(key: string, value: string | null): void {
  try {
    if (value === null) window.localStorage.removeItem(key);
    else window.localStorage.setItem(key, value);
  } catch {
    /* хранилище недоступно — настройка живёт до перезагрузки */
  }
}

export function A11yToggles() {
  const [theme, setTheme] = useState<string | null>(() => stored('esuo.theme'));
  const [large, setLarge] = useState<boolean>(() => stored('esuo.a11y') === 'large');

  useEffect(() => {
    const root = document.documentElement;
    if (theme) root.dataset.theme = theme; else delete root.dataset.theme;
    if (large) root.dataset.a11y = 'large'; else delete root.dataset.a11y;
  }, [theme, large]);

  const isDark = theme === 'dark' || (theme === null && window.matchMedia('(prefers-color-scheme: dark)').matches);
  return (
    <div style={{ display: 'flex', gap: 8 }}>
      <button type="button" className="btn btn-sm" aria-pressed={isDark} title="Тёмная или светлая тема"
        onClick={() => { const next = isDark ? 'light' : 'dark'; setTheme(next); store('esuo.theme', next); }}>
        <Icon name={isDark ? 'sun' : 'moon'} /> <span className="sr-only">Сменить тему</span>
      </button>
      <button type="button" className="btn btn-sm" aria-pressed={large} title="Крупный шрифт и высокий контраст"
        onClick={() => { setLarge(!large); store('esuo.a11y', !large ? 'large' : null); }}>
        <Icon name="text" /> <span className="sr-only">Крупный шрифт и высокий контраст</span>
      </button>
    </div>
  );
}
