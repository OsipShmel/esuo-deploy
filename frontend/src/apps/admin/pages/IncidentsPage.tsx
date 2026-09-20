import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { formatDateTime } from '@shared/format/time';
import { EmptyState, Loading, ProblemAlert } from '@shared/ui';
import { getDeviations, resolveIncident } from '../api';
import { useTz } from '../context';
import { useNames } from '../names';

const KIND: Record<string, string> = { technical: 'Техническая', operational: 'Операционная' };

/** Открытые инциденты — элементы `open_incident` из отклонений (постановка, раздел 4). Создаёт их оператор. */
export function IncidentsPage() {
  const qc = useQueryClient();
  const tz = useTz();
  const names = useNames();
  const q = useQuery({ queryKey: ['deviations', 'today'], queryFn: () => getDeviations({}), refetchInterval: 15_000 });
  const resolve = useMutation({
    mutationFn: (id: string) => resolveIncident(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['deviations'] }),
  });

  const incidents = (q.data?.items ?? []).filter((i) => i.type === 'open_incident');
  return (
    <section className="card stack">
      <h2>Открытые инциденты · {incidents.length}</h2>
      <ProblemAlert error={q.error ?? resolve.error} />
      {q.isLoading ? <Loading /> : incidents.length === 0 ? (
        <EmptyState title="Открытых инцидентов нет">Инциденты фиксирует оператор — они появятся здесь.</EmptyState>
      ) : (
        <div className="table-wrap"><table>
          <thead><tr><th>Тип</th><th>Описание</th><th>Окно</th><th>Создан</th><th /></tr></thead>
          <tbody>{incidents.map((i) => {
            const id = String(i.incident_id);
            return (
              <tr key={id}>
                <td><span className={`chip tone-${i.kind === 'technical' ? 'danger' : 'warn'}`}>{KIND[String(i.kind)] ?? String(i.kind)}</span></td>
                <td>{String(i.description)}</td>
                <td>{i.window_id ? names.window(i.window_id) : '—'}</td>
                <td>{formatDateTime(String(i.created_at), tz)}</td>
                <td><button type="button" className="btn btn-sm" disabled={resolve.isPending} onClick={() => resolve.mutate(id)}>Закрыть</button></td>
              </tr>
            );
          })}</tbody>
        </table></div>
      )}
    </section>
  );
}
