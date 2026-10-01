import { useEffect } from 'react';

/**
 * Сигналы о вызове на телефоне: вибрация, звук (Web Audio, без файлов), системное уведомление, экран не гаснет,
 * заголовок вкладки мигает. Всё это — «лучшее из доступного»: чего браузер не умеет, то молча пропускается
 * (вибрации нет в iOS Safari, Wake Lock — в старых браузерах).
 */

export function vibrate(): void {
  try { navigator.vibrate?.([300, 150, 300, 150, 500]); } catch { /* не поддерживается */ }
}

// ------------------------------------------------------------------ звук

type AudioCtor = typeof AudioContext;
const audioCtor = (): AudioCtor | undefined =>
  window.AudioContext ?? (window as unknown as { webkitAudioContext?: AudioCtor }).webkitAudioContext;

/** Контекст создаётся и «размораживается» одним нажатием: без жеста мобильный браузер звук не играет. */
let ctx: AudioContext | null = null;

export function soundSupported(): boolean {
  return audioCtor() !== undefined;
}

export function soundEnabled(): boolean {
  return ctx !== null && ctx.state === 'running';
}

/** Вызывать из обработчика нажатия. Играет короткий сигнал — человек слышит, что звук включён и на какой громкости. */
export async function enableSound(): Promise<boolean> {
  try {
    const Ctor = audioCtor();
    if (!Ctor) return false;
    ctx = ctx ?? new Ctor();
    await ctx.resume();
    if (ctx.state !== 'running') return false;
    tone(ctx, 880, 0, 0.25);
    return true;
  } catch {
    return false;
  }
}

/** Двухтоновый сигнал вызова. Без включённого звука — попытка «как получится» (на компьютере обычно играет). */
export function beep(): void {
  try {
    if (ctx) {
      if (ctx.state !== 'running') void ctx.resume().catch(() => undefined);     // вкладка вернулась из фона
      tone(ctx, 880, 0, 0.4);
      tone(ctx, 660, 0.5, 0.4);
      return;
    }
    const Ctor = audioCtor();
    if (!Ctor) return;
    const once = new Ctor();
    tone(once, 880, 0, 0.4);
    tone(once, 660, 0.5, 0.4);
    window.setTimeout(() => void once.close().catch(() => undefined), 1500);
  } catch { /* без жеста пользователя браузер может не дать играть звук */ }
}

function tone(audio: AudioContext, freq: number, at: number, length: number): void {
  const osc = audio.createOscillator();
  const gain = audio.createGain();
  const t0 = audio.currentTime + at;
  osc.frequency.value = freq;
  gain.gain.setValueAtTime(0.0001, t0);
  gain.gain.exponentialRampToValueAtTime(0.3, t0 + 0.02);
  gain.gain.exponentialRampToValueAtTime(0.0001, t0 + length);
  osc.connect(gain).connect(audio.destination);
  osc.start(t0);
  osc.stop(t0 + length + 0.05);
}

// ------------------------------------------------------------------ системное уведомление

export function systemNotify(title: string, body: string): void {
  try {
    if (typeof Notification !== 'undefined' && Notification.permission === 'granted') new Notification(title, { body });
  } catch { /* уведомления недоступны */ }
}

export function notificationsSupported(): boolean {
  return typeof Notification !== 'undefined';
}

// ------------------------------------------------------------------ экран вызова

const REPEAT_MS = 5_000;
const REPEAT_LIMIT = 12;      // минута напоминаний; дальше экран просто остаётся, оператор вызовет повторно

/** Пока экран вызова открыт: сигнал сразу и затем каждые 5 с (до минуты), пока человек не нажал «Иду». */
export function useCallSignal(active: boolean, signalKey: string): void {
  useEffect(() => {
    if (!active) return undefined;
    let left = REPEAT_LIMIT;
    vibrate(); beep();
    const id = window.setInterval(() => {
      if (--left <= 0) { window.clearInterval(id); return; }
      vibrate(); beep();
    }, REPEAT_MS);
    return () => { window.clearInterval(id); try { navigator.vibrate?.(0); } catch { /* нет вибрации */ } };
  }, [active, signalKey]);
}

/** Заголовок вкладки мигает, пока человек не отозвался: вызов видно и с другой вкладки. */
export function useTitleBlink(active: boolean, text: string): void {
  useEffect(() => {
    if (!active) return undefined;
    const original = document.title;
    let on = false;
    const id = window.setInterval(() => { on = !on; document.title = on ? text : original; }, 1000);
    document.title = text;
    return () => { window.clearInterval(id); document.title = original; };
  }, [active, text]);
}

interface WakeLockSentinelLike { release(): Promise<void>; addEventListener(type: 'release', cb: () => void): void }
interface WakeLockLike { request(type: 'screen'): Promise<WakeLockSentinelLike> }

/**
 * Экран телефона не гаснет, пока человек ждёт вызова. Браузер сам снимает блокировку, когда вкладка уходит в фон,
 * поэтому при возврате на вкладку она захватывается заново.
 */
export function useWakeLock(active: boolean): void {
  useEffect(() => {
    const wakeLock = (navigator as unknown as { wakeLock?: WakeLockLike }).wakeLock;
    if (!active || !wakeLock) return undefined;
    let sentinel: WakeLockSentinelLike | null = null;
    let stopped = false;
    const acquire = () => {
      if (stopped || sentinel || document.visibilityState !== 'visible') return;
      wakeLock.request('screen').then((s) => {
        if (stopped) { void s.release().catch(() => undefined); return; }
        sentinel = s;
        s.addEventListener('release', () => { sentinel = null; });
      }).catch(() => undefined);                       // энергосбережение или запрет — работаем без блокировки
    };
    acquire();
    document.addEventListener('visibilitychange', acquire);
    return () => {
      stopped = true;
      document.removeEventListener('visibilitychange', acquire);
      void sentinel?.release().catch(() => undefined);
    };
  }, [active]);
}
