import { formatSeconds } from '@shared/format/duration';
import { formatDateTime } from '@shared/format/time';
import { SourceBadge } from '@shared/ui';
import type { DeviationItem, DeviationType } from '../api';

export const DEVIATION_TITLES: Record<DeviationType, string> = {
  overdue_ticket: 'Просроченные талоны',
  no_show: 'Не подошли',
  long_service: 'Долгие обслуживания',
  slow_window: 'Окна медленнее нормы',
  idle_window: 'Окна без активности',
  service_without_window: 'Услуги без открытого окна',
  open_incident: 'Открытые инциденты',
};

interface Names {
  service: (id: unknown) => string;
  window: (id: unknown) => string;
}

const KIND: Record<string, string> = { technical: 'Техническая', operational: 'Операционная' };
const REASON: Record<string, string> = {
  operator: 'отметил оператор',
  no_show_after_recalls: 'не подошёл после повторных вызовов',
  appointment_expired: 'запись не подтверждена вовремя',
};

const num = (v: unknown): number | null => (typeof v === 'number' ? v : null);
const str = (v: unknown): string => (typeof v === 'string' ? v : '');

/** Таблица одной группы отклонений: поля — по постановке admin, раздел 3. */
export function DeviationList({ type, items, names, tz }: { type: DeviationType; items: DeviationItem[]; names: Names; tz: string }) {
  switch (type) {
    case 'overdue_ticket':
      return (
        <Table head={['Номер', 'Услуга', 'Источник', 'Дедлайн', 'Просрочен на']}>
          {items.map((i, k) => <tr key={k}><td><b>{str(i.display_number)}</b></td><td>{names.service(i.service_id)}</td><td><SourceBadge source={str(i.source)} /></td><td>{formatDateTime(str(i.due_at), tz)}</td><td>{formatSeconds(num(i.overdue_sec))}</td></tr>)}
        </Table>
      );
    case 'no_show':
      return (
        <Table head={['Номер', 'Услуга', 'Источник', 'Причина', 'Когда']}>
          {items.map((i, k) => <tr key={k}><td><b>{str(i.display_number)}</b></td><td>{names.service(i.service_id)}</td><td><SourceBadge source={str(i.source)} /></td><td>{REASON[str(i.reason)] ?? str(i.reason)}</td><td>{formatDateTime(str(i.at), tz)}</td></tr>)}
        </Table>
      );
    case 'long_service':
      return (
        <Table head={['Номер', 'Услуга', 'Окно', 'Длительность']}>
          {items.map((i, k) => <tr key={k}><td><b>{str(i.display_number)}</b></td><td>{names.service(i.service_id)}</td><td>{names.window(i.window_id)}</td><td>{formatSeconds(num(i.duration_sec))}</td></tr>)}
        </Table>
      );
    case 'slow_window':
      return (
        <Table head={['Окно', 'Среднее обслуживание', 'Норма по отделению', 'Медленнее в', 'Обслужено']}>
          {items.map((i, k) => <tr key={k}><td>{str(i.window_name) || names.window(i.window_id)}</td><td>{formatSeconds(num(i.avg_service_sec))}</td><td>{formatSeconds(num(i.norm_service_sec))}</td><td>{num(i.ratio)?.toLocaleString('ru-RU')} раза</td><td>{num(i.samples)}</td></tr>)}
        </Table>
      );
    case 'idle_window':
      return (
        <Table head={['Окно', 'Простой', 'Последняя активность']}>
          {items.map((i, k) => <tr key={k}><td>{str(i.window_name)}</td><td>{formatSeconds(num(i.idle_sec))}</td><td>{formatDateTime(str(i.last_activity_at), tz)}</td></tr>)}
        </Table>
      );
    case 'service_without_window':
      return (
        <Table head={['Услуга', 'Ждут клиентов']}>
          {items.map((i, k) => <tr key={k}><td>{str(i.service_name)}</td><td>{num(i.waiting)}</td></tr>)}
        </Table>
      );
    case 'open_incident':
      return (
        <Table head={['Тип', 'Описание', 'Окно', 'Создан']}>
          {items.map((i, k) => <tr key={k}><td>{KIND[str(i.kind)] ?? str(i.kind)}</td><td>{str(i.description)}</td><td>{i.window_id ? names.window(i.window_id) : '—'}</td><td>{formatDateTime(str(i.created_at), tz)}</td></tr>)}
        </Table>
      );
  }
}

function Table({ head, children }: { head: string[]; children: React.ReactNode }) {
  return (
    <div className="table-wrap">
      <table>
        <thead><tr>{head.map((h) => <th key={h}>{h}</th>)}</tr></thead>
        <tbody>{children}</tbody>
      </table>
    </div>
  );
}
