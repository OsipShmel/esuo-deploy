/** Двухтоновый сигнал вызова на Web Audio API (без внешних файлов). Контекст создаётся по жесту «Включить звук». */
let ctx: AudioContext | null = null;

export async function enableSound(): Promise<boolean> {
  try {
    const Ctx = window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
    if (!Ctx) return false;
    ctx = ctx ?? new Ctx();
    await ctx.resume();
    return ctx.state === 'running';
  } catch {
    return false;
  }
}

export function playCallSignal(): void {
  if (!ctx || ctx.state !== 'running') return;
  const audio = ctx;
  const tone = (freq: number, start: number, length: number) => {
    const osc = audio.createOscillator();
    const gain = audio.createGain();
    osc.type = 'sine';
    osc.frequency.value = freq;
    const t0 = audio.currentTime + start;
    gain.gain.setValueAtTime(0.0001, t0);
    gain.gain.exponentialRampToValueAtTime(0.35, t0 + 0.03);
    gain.gain.exponentialRampToValueAtTime(0.0001, t0 + length);
    osc.connect(gain).connect(audio.destination);
    osc.start(t0);
    osc.stop(t0 + length + 0.05);
  };
  tone(880, 0, 0.45);
  tone(660, 0.5, 0.6);
}
