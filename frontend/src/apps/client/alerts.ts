/** Сигналы о вызове: вибрация, звук (Web Audio, без файлов), системное уведомление, если разрешено. */

export function vibrate(): void {
  try { navigator.vibrate?.([300, 150, 300, 150, 500]); } catch { /* не поддерживается */ }
}

export function beep(): void {
  try {
    const Ctx = window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
    if (!Ctx) return;
    const ctx = new Ctx();
    const tone = (freq: number, at: number) => {
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.frequency.value = freq;
      gain.gain.setValueAtTime(0.0001, ctx.currentTime + at);
      gain.gain.exponentialRampToValueAtTime(0.3, ctx.currentTime + at + 0.02);
      gain.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + at + 0.4);
      osc.connect(gain).connect(ctx.destination);
      osc.start(ctx.currentTime + at);
      osc.stop(ctx.currentTime + at + 0.45);
    };
    tone(880, 0);
    tone(660, 0.5);
    window.setTimeout(() => void ctx.close(), 1500);
  } catch { /* без жеста пользователя браузер может не дать играть звук */ }
}

export function systemNotify(title: string, body: string): void {
  try {
    if (typeof Notification !== 'undefined' && Notification.permission === 'granted') new Notification(title, { body });
  } catch { /* уведомления недоступны */ }
}

export function notificationsSupported(): boolean {
  return typeof Notification !== 'undefined';
}
