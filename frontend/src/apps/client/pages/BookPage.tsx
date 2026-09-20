import { useMemo, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { formatTime, todayIn } from '@shared/format/time';
import { EmptyState, Loading, ProblemAlert } from '@shared/ui';
import { useBranchApi } from '../App';
import { CategoryPicker } from '../CategoryPicker';
import { CLIENT_PHRASES } from '../phrases';
import { saveTicket } from '../storage';
import { useIdempotentSubmit } from '../useIdempotentSubmit';

const HORIZON_DAYS = 30;

const addDays = (isoDate: string, days: number): string => {
  const d = new Date(`${isoDate}T12:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
};

/** Запись на время: дата (сегодня … +30 дней), слот, категория, подтверждение. */
export function BookPage() {
  const { b = '' } = useParams();
  const [params] = useSearchParams();
  const api = useBranchApi();
  const navigate = useNavigate();

  const info = useQuery({ queryKey: ['info', b, api.mode], queryFn: () => api.info(b) });
  const service = info.data?.services.find((s) => s.id === params.get('service'));
  const tz = info.data?.timezone ?? 'Europe/Moscow';
  const today = todayIn(tz);
  const [date, setDate] = useState<string>('');
  const day = date || today;
  const [slot, setSlot] = useState<string | null>(null);
  const [category, setCategory] = useState('default');

  const slots = useQuery({
    queryKey: ['slots', b, service?.id, day, api.mode],
    queryFn: () => api.slots(b, service!.id, day),
    enabled: !!service,
  });
  const available = useMemo(() => (slots.data ?? []).filter((s) => s.remaining_capacity > 0), [slots.data]);
  const sending = useIdempotentSubmit<void>(`${service?.id}|${slot}|${category}`);

  if (info.isLoading) return <Loading rows={4} />;
  if (!info.data) return <ProblemAlert error={info.error} phrases={CLIENT_PHRASES} />;
  if (!service) return <EmptyState title="Услуга не выбрана"><Link to={`/b/${b}`}>Выбрать услугу</Link></EmptyState>;

  async function confirm() {
    if (!slot || !service) return;
    await sending.submit(async (key) => {
      const t = await api.book(b, { service_id: service.id, slot_start: slot, client_category: category }, key);
      saveTicket({ branch: b, token: t.token, number: t.display_number });
      navigate(`/b/${b}/t/${t.token}`);
    });
    // Слот могли занять за это время — обновим список, чтобы клиент выбрал другое время.
    void slots.refetch();
  }

  const err = sending.error;
  return (
    <div className="stack">
      <Link to={`/b/${b}`} className="muted">‹ {info.data.name}</Link>
      <h1>Запись: {service.name}</h1>

      <label className="field">Дата
        <input type="date" value={day} min={today} max={addDays(today, HORIZON_DAYS)}
          onChange={(e) => { setDate(e.target.value); setSlot(null); }} />
      </label>

      <section aria-label="Время">
        <h2>Свободное время</h2>
        {slots.isLoading ? <Loading rows={2} /> : slots.error ? <ProblemAlert error={slots.error} phrases={CLIENT_PHRASES} /> : available.length === 0 ? (
          <EmptyState title="На эту дату свободного времени нет">Выберите другой день.</EmptyState>
        ) : (
          <div className="slots" role="group" aria-label="Слоты">
            {available.map((s) => (
              <button key={s.slot_start} type="button" className="slot" aria-pressed={slot === s.slot_start} onClick={() => setSlot(s.slot_start)}>
                {formatTime(s.slot_start, tz)}
              </button>
            ))}
          </div>
        )}
      </section>

      <CategoryPicker categories={info.data.categories} value={category} onChange={setCategory} />
      <ProblemAlert error={err} phrases={CLIENT_PHRASES} />
      <button type="button" className="btn btn-primary btn-block" disabled={!slot || sending.busy} onClick={() => void confirm()}>
        {err ? 'Повторить' : sending.busy ? 'Записываем…' : slot ? `Записаться на ${formatTime(slot, tz)}` : 'Выберите время'}
      </button>
    </div>
  );
}
