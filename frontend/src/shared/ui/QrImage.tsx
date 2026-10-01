import { useEffect, useRef } from 'react';
import QRCode from 'qrcode';

/** QR строится на клиенте из ссылки на страницу получения талона; обновляется при каждом изменении ссылки. */
export function QrImage({ value, size = 420, className = 'qr', label = 'QR-код для получения талона' }:
  { value: string | null; size?: number; className?: string; label?: string }) {
  const canvas = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    if (!value || !canvas.current) return;
    void QRCode.toCanvas(canvas.current, value, { width: size, margin: 2, color: { dark: '#000000', light: '#ffffff' } });
  }, [value, size]);
  return <canvas ref={canvas} className={className} role="img" aria-label={label} />;
}
