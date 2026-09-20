import { useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { EmptyState, Loading, ProblemAlert } from '@shared/ui';
import { ApiError } from '@shared/api/http';
import { useBranchApi } from '../App';
import { CategoryPicker } from '../CategoryPicker';
import { CLIENT_PHRASES } from '../phrases';
import { deviceId, forgetTicket, savedTicket, saveTicket } from '../storage';
import { useIdempotentSubmit } from '../useIdempotentSubmit';

/** Пришёл по QR с табло: выбор услуги и категории → электронный талон. */
export function JoinPage() {
  const { b = '' } = useParams();
  const [params] = useSearchParams();
  const api = useBranchApi();
  const navigate = useNavigate();
  const info = useQuery({ queryKey: ['info', b, api.mode], queryFn: () => api.info(b) });
  const [serviceId, setServiceId] = useState<string>('');
  const [category, setCategory] = useState('default');
  const code = params.get('code') ?? '';
  const sending = useIdempotentSubmit<void>(`${serviceId}|${category}|${code}`);
  const saved = savedTicket();

  if (info.isLoading) return <Loading rows={4} />;
  if (!info.data) return <ProblemAlert error={info.error} phrases={CLIENT_PHRASES} />;

  async function take() {
    if (!serviceId) return;
    await sending.submit(async (key) => {
      const t = await api.qr(b, { qr_code: code, service_id: serviceId, client_category: category, device_id: deviceId() }, key);
      saveTicket({ branch: b, token: t.token, number: t.display_number });
      navigate(`/b/${b}/t/${t.token}`);
    });
  }

  if (!code) {
    return <EmptyState title="Нет кода отделения">Отсканируйте QR-код на табло в зале ещё раз.</EmptyState>;
  }

  const err = sending.error;
  const errCode = err instanceof ApiError ? err.code : null;
  return (
    <div className="stack">
      <h1>Талон в {info.data.name}</h1>
      <h2>Выберите услугу</h2>
      <div className="stack">
        {info.data.services.map((s) => (
          <label key={s.id} className="choice" style={{ minHeight: 60, fontWeight: 500 }}>
            <input type="radio" name="service" checked={serviceId === s.id} onChange={() => setServiceId(s.id)} />
            {s.name}
          </label>
        ))}
      </div>
      <CategoryPicker categories={info.data.categories} value={category} onChange={setCategory} />
      <ProblemAlert error={err} phrases={CLIENT_PHRASES} />
      {errCode === 'ACTIVE_TICKET_EXISTS' && saved && (
        <Link className="list-item" to={`/b/${saved.branch}/t/${saved.token}`}>
          <span>Открыть мой талон {saved.number}</span><span className="chev">›</span>
        </Link>
      )}
      {errCode === 'TICKET_NOT_FOUND' && (
        <button type="button" className="btn" onClick={() => forgetTicket()}>Забыть сохранённый талон</button>
      )}
      <button type="button" className="btn btn-primary btn-block" disabled={!serviceId || sending.busy} onClick={() => void take()}>
        {err ? 'Повторить' : sending.busy ? 'Получаем талон…' : 'Получить талон'}
      </button>
    </div>
  );
}
