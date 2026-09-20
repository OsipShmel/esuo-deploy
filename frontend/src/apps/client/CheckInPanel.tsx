import { useEffect, useRef, useState } from 'react';
import { ProblemAlert } from '@shared/ui';
import { CLIENT_PHRASES } from './phrases';

interface Detector { detect(source: CanvasImageSource): Promise<{ rawValue: string }[]> }
declare const BarcodeDetector: (new (opts: { formats: string[] }) => Detector) | undefined;

/**
 * Check-in записи: сканирование QR на табло камерой (`BarcodeDetector`), а если камеры или API нет — поле для кода
 * (постановка client). Код `demo:b01` виден на табло мелким текстом под QR.
 */
export function CheckInPanel({ busy, error, onSubmit, onClose }: { busy: boolean; error: unknown; onSubmit: (code: string) => void; onClose: () => void }) {
  const video = useRef<HTMLVideoElement>(null);
  const [code, setCode] = useState('');
  const [camera, setCamera] = useState<'idle' | 'on' | 'unavailable'>('idle');

  useEffect(() => {
    if (typeof BarcodeDetector === 'undefined' || !navigator.mediaDevices?.getUserMedia) { setCamera('unavailable'); return undefined; }
    let stream: MediaStream | null = null;
    let timer: number | undefined;
    let stopped = false;
    const detector = new BarcodeDetector({ formats: ['qr_code'] });
    navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } }).then((s) => {
      if (stopped) { s.getTracks().forEach((t) => t.stop()); return; }
      stream = s;
      setCamera('on');
      if (video.current) { video.current.srcObject = s; void video.current.play(); }
      timer = window.setInterval(async () => {
        if (!video.current) return;
        try {
          const found = await detector.detect(video.current);
          const raw = found[0]?.rawValue;
          if (raw) { window.clearInterval(timer); onSubmit(extractCode(raw)); }
        } catch { /* кадр ещё не готов */ }
      }, 500);
    }).catch(() => setCamera('unavailable'));
    return () => { stopped = true; if (timer) window.clearInterval(timer); stream?.getTracks().forEach((t) => t.stop()); };
    // onSubmit стабилен на время сканирования
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <div className="card stack" role="dialog" aria-label="Подтверждение прихода">
      <h2>Я в отделении</h2>
      {camera === 'on' && <video ref={video} playsInline muted style={{ width: '100%', borderRadius: 10, background: '#000' }} />}
      <p className="muted small">{camera === 'unavailable' ? 'Камера недоступна — введите код с табло.' : 'Наведите камеру на QR-код на табло или введите код вручную.'}</p>
      <label className="field">Код с табло
        <input value={code} onChange={(e) => setCode(e.target.value)} placeholder="demo:b01" autoCapitalize="off" autoCorrect="off" />
      </label>
      <ProblemAlert error={error} phrases={CLIENT_PHRASES} />
      <div className="row">
        <button type="button" className="btn btn-primary" disabled={busy || code.trim() === ''} onClick={() => onSubmit(code.trim())}>Подтвердить приход</button>
        <button type="button" className="btn" onClick={onClose}>Закрыть</button>
      </div>
    </div>
  );
}

/** QR табло — ссылка вида `…/join?code=demo:b01`; из неё берём значение `code`, иначе — как есть. */
function extractCode(raw: string): string {
  try { return new URL(raw).searchParams.get('code') ?? raw; } catch { return raw; }
}
