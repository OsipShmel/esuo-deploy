import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { formatDateTime } from '@shared/format/time';
import { EmptyState, Icon, Loading, ProblemAlert } from '@shared/ui';
import { getDlq, getMpRequests, getNotificationStatus, retryDlq, type DeadLetter } from '../api';
import { useToast } from '../toast';

const REASON: Record<string, string> = { exhausted: 'исчерпаны попытки', expired: 'устарело', rejected: 'провайдер отклонил' };
const MP_STATUS: Record<string, string> = { accepted: 'принята', confirmed: 'подтверждена', rejected: 'отклонена' };
const BREAKER: Record<string, { text: string; tone: string }> = {
  CLOSED: { text: 'работает', tone: 'ok' },
  HALF_OPEN: { text: 'проверяет провайдера', tone: 'warn' },
  OPEN: { text: 'отключён защитой', tone: 'danger' },
};

export function NotificationsPage() {
  const status = useQuery({ queryKey: ['notif-status'], queryFn: getNotificationStatus, refetchInterval: 15_000 });
  const notif = useQuery({ queryKey: ['dlq', 'notification'], queryFn: () => getDlq('notification'), refetchInterval: 15_000 });
  const mp = useQuery({ queryKey: ['dlq', 'mp_callback'], queryFn: () => getDlq('mp_callback'), refetchInterval: 15_000 });
  const requests = useQuery({ queryKey: ['mp-requests'], queryFn: getMpRequests, refetchInterval: 15_000 });

  const s = status.data;
  const b = s ? BREAKER[s.circuit_breaker.state] ?? { text: s.circuit_breaker.state, tone: 'muted' } : null;

  return (
    <div className="stack">
      <section className="card stack" aria-label="Провайдер уведомлений">
        <h2>Провайдер уведомлений</h2>
        {status.isLoading ? <Loading rows={1} /> : !s ? <ProblemAlert error={status.error} /> : (
          <>
            <div className="breaker">
              <span className={`state tone-${b?.tone}`}>{b?.text}</span>
              <span className="muted">
                {s.circuit_breaker.failure_rate < 0
                  ? 'пока мало отправок для оценки'
                  : `ошибок в последних ${s.circuit_breaker.window_calls} отправках: ${Math.round(s.circuit_breaker.failure_rate)}%`}
              </span>
            </div>
            <div className="row">
              <span className="muted small">С начала работы центра:</span>
              {Object.entries(s.counts).map(([k, v]) => <span key={k} className="chip tone-muted">{COUNT_LABEL[k] ?? k}: {v}</span>)}
            </div>
            {s.last_error && (
              <div className="muted small">
                Последняя ошибка{s.last_error_at && ` ${formatDateTime(s.last_error_at)}`}: {ERROR_TEXT[s.last_error] ?? s.last_error}
              </div>
            )}
          </>
        )}
      </section>

      <DlqTable title="Недоставленные уведомления" query={notif} />
      <DlqTable title="Недоставленные ответы в МП" query={mp} />

      <section className="card stack" aria-label="Записи из МП">
        <h2>Записи из МП</h2>
        {requests.isLoading ? <Loading /> : !requests.data ? <ProblemAlert error={requests.error} /> : requests.data.length === 0 ? <EmptyState title="Записей из МП пока нет" /> : (
          <div className="table-wrap"><table>
            <thead><tr><th>Запись МП</th><th>Отделение</th><th>Статус</th><th>Талон</th><th>Причина</th><th className="num">Попыток ответа</th><th>Обновлена</th></tr></thead>
            <tbody>{requests.data.map((r) => (
              <tr key={r.mp_appointment_id}>
                <td>{r.mp_appointment_id}</td><td>{r.branch_id}</td>
                <td><span className={`chip tone-${r.status === 'rejected' ? 'danger' : r.status === 'confirmed' ? 'ok' : 'muted'}`}>{MP_STATUS[r.status] ?? r.status}</span></td>
                <td>{r.display_number ?? '—'}</td><td>{r.reason ?? '—'}</td><td className="num">{r.callback_attempts}</td><td>{formatDateTime(r.updated_at)}</td>
              </tr>
            ))}</tbody>
          </table></div>
        )}
      </section>
    </div>
  );
}

/** failed — неудачные попытки, а не потерянные уведомления: после повтора они обычно доставляются. */
const COUNT_LABEL: Record<string, string> = {
  sent: 'доставлено', failed: 'неудачных попыток', expired: 'устарело', rejected: 'отклонено провайдером', dlq_open: 'в DLQ',
};
const ERROR_TEXT: Record<string, string> = { 'circuit breaker open': 'провайдер был отключён защитой (circuit breaker)' };

function DlqTable({ title, query }: { title: string; query: ReturnType<typeof useQuery<DeadLetter[]>> }) {
  const qc = useQueryClient();
  const toast = useToast();
  const [busyId, setBusyId] = useState<number | null>(null);
  const retry = useMutation({
    mutationFn: (id: number) => retryDlq(id),
    onMutate: (id) => setBusyId(id),
    onSettled: () => setBusyId(null),
    onSuccess: (r) => {
      toast(r.result === 'delivered' ? 'Доставлено' : `Не удалось отправить: ${r.error ?? 'ошибка'}`, r.result !== 'delivered');
      void qc.invalidateQueries({ queryKey: ['dlq'] });
      void qc.invalidateQueries({ queryKey: ['notif-status'] });
    },
    onError: () => toast('Не удалось повторить отправку', true),
  });
  return (
    <section className="card stack" aria-label={title}>
      <h2>{title}{query.data ? ` · ${query.data.length}` : ''}</h2>
      {query.isLoading ? <Loading /> : !query.data ? <ProblemAlert error={query.error} /> : query.data.length === 0 ? <EmptyState title="Всё доставлено" /> : (
        <div className="table-wrap"><table>
          <thead><tr><th>Отделение</th><th>Ключ</th><th>Причина</th><th className="num">Попыток</th><th>Последняя ошибка</th><th>Создано</th><th /></tr></thead>
          <tbody>{query.data.map((d) => (
            <tr key={d.id}>
              <td>{d.branch_id}</td><td className="small">{d.key}</td><td>{REASON[d.reason] ?? d.reason}</td><td className="num">{d.attempts}</td>
              <td className="small">{d.last_error ?? '—'}</td><td>{formatDateTime(d.created_at)}</td>
              <td><button type="button" className="btn btn-sm" disabled={busyId === d.id} onClick={() => retry.mutate(d.id)}><Icon name="bell" size={14} /> Отправить снова</button></td>
            </tr>
          ))}</tbody>
        </table></div>
      )}
    </section>
  );
}
