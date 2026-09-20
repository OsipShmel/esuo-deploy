/** «12 мин 30 с»; `null` — «нет данных», не «0» (постановка admin, раздел 3). */
export function formatSeconds(sec: number | null | undefined): string {
  if (sec === null || sec === undefined) return 'нет данных';
  const total = Math.max(0, Math.round(sec));
  if (total < 1) return 'меньше секунды';
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const parts: string[] = [];
  if (h) parts.push(`${h} ч`);
  if (m) parts.push(`${m} мин`);
  if (s && !h) parts.push(`${s} с`);
  return parts.join(' ');
}

export function formatPercent(ratio: number): string {
  return `${Math.round(Math.max(0, Math.min(1, ratio)) * 100)}%`;
}
