import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Loading, ProblemAlert } from '@shared/ui';
import { QrImage } from '@shared/ui/QrImage';
import { getQrSticker } from '../api';
import { useBranch } from '../context';

type Target = 'center' | 'local';

/**
 * Печатная страница «QR для наклейки»: долгоживущий подписанный код для двери, стойки, листовки. В отличие от кода на
 * табло он не ротируется; отзываются все наклейки разом — повышением QR_STICKER_KEY_VERSION на узле.
 */
export function StickerPage() {
  const branch = useBranch();
  const q = useQuery({ queryKey: ['qr-sticker'], queryFn: getQrSticker, staleTime: 60_000 });
  const [choice, setChoice] = useState<Target | null>(null);

  if (q.isLoading) return <Loading rows={4} />;
  if (!q.data) return <ProblemAlert error={q.error} />;

  const s = q.data;
  const target: Target = choice ?? (s.center_url ? 'center' : 'local');
  const raw = target === 'center' && s.center_url ? s.center_url : s.local_url;
  const url = new URL(raw, window.location.origin).href;

  return (
    <div className="stack">
      <section className="card stack no-print">
        <h2>QR для наклейки</h2>
        <p className="muted small" style={{ margin: 0 }}>
          Код на табло меняется каждые 2 минуты. Для двери, стойки и листовок — этот код: он действует, пока не отозван.
          Отозвать все напечатанные наклейки: увеличить QR_STICKER_KEY_VERSION на узле (сейчас {s.key_version}) и
          перезапустить его.
        </p>
        {!s.signed && (
          <div className="banner tone-warn" role="status">
            <div className="banner-body">
              Демо-код: наклейка работает только на демо-стенде (DEMO_DATA=true).
              <small>Для отделения задайте QR_SECRET — код станет подписанным.</small>
            </div>
          </div>
        )}
        {s.center_url && (
          <div className="row" role="radiogroup" aria-label="Куда ведёт код">
            <label className="check"><input type="radio" name="target" checked={target === 'center'} onChange={() => setChoice('center')} />
              Через интернет (центр)</label>
            <label className="check"><input type="radio" name="target" checked={target === 'local'} onChange={() => setChoice('local')} />
              Через Wi-Fi отделения</label>
          </div>
        )}
        {target === 'local' && (
          <p className="muted small" style={{ margin: 0 }}>Телефон клиента должен быть в Wi-Fi отделения: ссылка ведёт на {new URL(url).origin}.</p>
        )}
        <div className="row"><button type="button" className="btn btn-primary" onClick={() => window.print()}>Печать</button></div>
      </section>

      <section className="card sticker-sheet" aria-label="Наклейка">
        <div className="sticker-branch">{branch?.name ?? ''}</div>
        <h2 className="sticker-title">Встаньте в очередь с телефона</h2>
        <QrImage value={url} size={640} className="sticker-qr" label="QR-код для наклейки" />
        <p className="sticker-hint">Наведите камеру телефона на код и выберите услугу — талон придёт на телефон.</p>
        <p className="sticker-url">{url}</p>
      </section>
    </div>
  );
}
