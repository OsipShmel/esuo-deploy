/** Журнал решений: фразы человеческим языком (frontend-concept 5.3). Коды причин на экране не показываем. */
import { formatTime } from '@shared/format/time';

export type LogKind = 'calls' | 'queue' | 'system';

export interface Candidate {
  display_number?: string;
  due_at?: string;
  score?: number;
  predicted_duration_sec?: number;
}

export interface LogEntry {
  id: string;
  at: string;
  eventType: string;
  kind: LogKind;
  text: string;
  envelope: unknown;
  candidates: Candidate[];
}

export const LOG_EVENTS = [
  'ticket.called', 'ticket.requeued', 'ticket.redirected', 'ticket.no_show',
  'load.level_changed', 'mp.appointment.confirmed', 'mp.appointment.rejected', 'notification.requested',
  'config.applied', 'incident.created', 'model.trained',
] as const;

const LEVEL: Record<string, string> = { normal: 'нормальная', high: 'высокая', overload: 'перегрузка' };
const CAUSE: Record<string, string> = {
  flow_above_forecast: 'клиентов больше обычного', slow_service: 'обслуживание идёт медленнее обычного',
  few_windows: 'открыто окон меньше, чем нужно', booking_peak: 'много предварительных записей на это время',
  overdue_backlog: 'накопились просроченные талоны',
};

const obj = (v: unknown): Record<string, unknown> => (v && typeof v === 'object' ? (v as Record<string, unknown>) : {});
const str = (v: unknown): string => (typeof v === 'string' ? v : v === undefined || v === null ? '' : String(v));
const windowNum = (name: string) => name.replace(/^Окно\s*/i, '') || name;
const mins = (sec: number) => (sec >= 90 ? `${Math.round(sec / 60)} мин` : `${Math.round(sec)} с`);

/** Превращает событие узла в строку журнала; неизвестные события не показываем (`null`). */
export function toLogEntry(eventType: string, envelope: unknown, tz: string): LogEntry | null {
  const env = obj(envelope);
  const data = obj(env.data);
  const number = str(data.display_number);
  const candidates = Array.isArray(data.candidates) ? (data.candidates as Candidate[]) : [];
  const entry = (kind: LogKind, text: string): LogEntry => ({
    id: str(env.event_id) || `${eventType}-${Math.random()}`, at: str(env.occurred_at), eventType, kind, text, envelope, candidates,
  });

  switch (eventType) {
    case 'ticket.called': {
      const win = windowNum(str(data.window_name));
      const reason = str(data.reason);
      if (reason === 'overdue_edf') return entry('calls', `${number} вызван в окно ${win}: просрочен, просроченные идут первыми`);
      if (reason === 'score_in_slack') {
        const mine = candidates.find((c) => c.display_number === number);
        const others = candidates.filter((c) => c.display_number !== number && typeof c.predicted_duration_sec === 'number');
        const other = others.sort((a, b) => (a.predicted_duration_sec ?? 0) - (b.predicted_duration_sec ?? 0)).at(-1);
        const cmp = mine?.predicted_duration_sec !== undefined && other?.predicted_duration_sec !== undefined
          ? ` (${mins(mine.predicted_duration_sec)} против ${mins(other.predicted_duration_sec)})` : '';
        return entry('calls', `${number} вызван в окно ${win}: дедлайны близки, выбран более быстрый${cmp}`);
      }
      if (reason === 'call_now') return entry('calls', `${number} вызван в окно ${win} сразу: живая очередь`);
      return entry('calls', `${number} вызван в окно ${win}: дедлайн раньше всех`);
    }
    case 'ticket.requeued':
      return entry('queue', `${number || 'Талон'} вернулся в очередь${data.reason === 'window_closed_force' ? ': окно закрыто, место сохранено' : data.mode === 'to_tail' ? ' в конец' : ' с сохранением места'}`);
    case 'ticket.redirected':
      return entry('queue', `${number || 'Талон'} перенаправлен оператором`);
    case 'ticket.no_show':
      return entry('queue', `${number || 'Талон'}: клиент не подошёл, окно освободилось`);
    case 'load.level_changed': {
      const causes = Array.isArray(data.causes) ? (data.causes as { code?: string }[]).map((c) => CAUSE[str(c.code)] ?? str(c.code)).join('; ') : '';
      return entry('system', `Нагрузка: ${LEVEL[str(data.from)] ?? str(data.from)} → ${LEVEL[str(data.to)] ?? str(data.to)}.${causes ? ` ${causes[0]?.toUpperCase()}${causes.slice(1)}` : ''}`);
    }
    case 'mp.appointment.rejected': {
      const at = data.slot_start ? formatTime(str(data.slot_start), tz) : '';
      const why = str(data.reason) === 'BOOKING_LIMITED' ? 'запись ограничена из-за нагрузки'
        : str(data.reason) === 'SLOT_EXPIRED' ? 'это время уже прошло' : str(data.detail) || str(data.reason);
      return entry('system', `МП: запись${at ? ` на ${at}` : ''} отклонена — ${why}`);
    }
    case 'mp.appointment.confirmed':
      return entry('system', `МП: запись${data.slot_start ? ` на ${formatTime(str(data.slot_start), tz)}` : ''} подтверждена, талон ${number}`);
    case 'notification.requested':
      return entry('system', `Уведомление «очередь подходит» для ${number}`);
    case 'config.applied':
      return entry('system', `Применена конфигурация центра v${str(data.central_version)}`);
    case 'incident.created':
      return entry('system', `Инцидент: ${str(data.description)}`);
    case 'model.trained':
      return entry('system', `Модель длительности ${str(data.version)}: MAE ${str(data.mae_sec)} с${data.previous_mae_sec ? ` (было ${str(data.previous_mae_sec)} с)` : ''}`);
    default:
      return null;
  }
}
