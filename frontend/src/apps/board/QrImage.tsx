import { useEffect, useRef } from 'react';
import QRCode from 'qrcode';

/** QR строится на клиенте из ссылки на страницу получения талона; обновляется при каждом изменении ссылки. */
export function QrImage({ value, size = 420 }: { value: string | null; size?: number }) {
  const canvas = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    if (!value || !canvas.current) return;
    void QRCode.toCanvas(canvas.current, value, { width: size, margin: 2, color: { dark: '#000000', light: '#ffffff' } });
  }, [value, size]);
  return <canvas ref={canvas} className="qr" role="img" aria-label="QR-код для получения талона" />;
}

