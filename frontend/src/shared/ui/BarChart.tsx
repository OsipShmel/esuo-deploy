import './components.css';

/** Полоса с процентом: простой HTML, без библиотек. `tone` по уровню; подпись обязательна рядом. */
export function Bar({ ratio, tone }: { ratio: number; tone?: 'ok' | 'warn' | 'danger' }) {
  const pct = Math.round(Math.max(0, Math.min(1, ratio)) * 100);
  return (
    <div className={`bar${tone ? ` tone-${tone}` : ''}`} role="img" aria-label={`${pct}%`}>
      <span style={{ width: `${pct}%` }} />
    </div>
  );
}
