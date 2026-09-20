import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { formatPercent, formatSeconds } from '@shared/format/duration';
import { formatDateTime, todayIn } from '@shared/format/time';
import { Bar, EmptyState, Loading, ProblemAlert, SourceBadge, StatusChip, TicketNumber } from '@shared/ui';
import { getDeviations, getSummary, getUnfinished, getWindowStats, type DeviationItem, type DeviationType } from '../api';
import { useTz } from '../context';
import { useNames } from '../names';
import { DeviationList, DEVIATION_TITLES } from './Deviations';

type Section = 'summary' | 'deviations' | 'unfinished';

export function AnalyticsPage() {
  const tz = useTz();
  const [from, setFrom] = useState(() => todayIn(tz));
  const [to, setTo] = useState(() => todayIn(tz));
  const [section, setSection] = useState<Section>('summary');
  const period = { from, to };
  const invalid = from > to;

  const deviations = useQuery({ queryKey: ['deviations', from, to], queryFn: () => getDeviations(period), enabled: !invalid });
  const deviationTotal = Object.values(deviations.data?.counts ?? {}).reduce((a, b) => a + (b ?? 0), 0);

  return (
    <div className="stack">
      <div className="card toolbar">
        <label className="field">С<input type="date" value={from} max={to} onChange={(e) => setFrom(e.target.value)} /></label>
        <label className="field">По (включительно)<input type="date" value={to} min={from} onChange={(e) => setTo(e.target.value)} /></label>
        <div className="subtabs" role="group" aria-label="Раздел аналитики">
          <button type="button" className="btn" aria-pressed={section === 'summary'} onClick={() => setSection('summary')}>Итоги</button>
          <button type="button" className="btn" aria-pressed={section === 'deviations'} onClick={() => setSection('deviations')}>Отклонения{deviationTotal > 0 ? ` · ${deviationTotal}` : ''}</button>
          <button type="button" className="btn" aria-pressed={section === 'unfinished'} onClick={() => setSection('unfinished')}>Незавершённые</button>
        </div>
      </div>
      {invalid && <div className="banner tone-warn" role="alert">Дата «С» позже даты «По» — измените период.</div>}
      {!invalid && section === 'summary' && <SummarySection from={from} to={to} />}
      {!invalid && section === 'deviations' && <DeviationsSection from={from} to={to} tz={tz} query={deviations} />}
      {!invalid && section === 'unfinished' && <UnfinishedSection from={from} to={to} tz={tz} />}
    </div>
  );
}

function Tile({ value, caption }: { value: string; caption: string }) {
  return <div className="tile"><div className="v">{value}</div><div className="l">{caption}</div></div>;
}

const dash = (sec: number | null) => formatSeconds(sec);

function SummarySection({ from, to }: { from: string; to: string }) {
  const summary = useQuery({ queryKey: ['summary', from, to], queryFn: () => getSummary({ from, to }) });
  const windows = useQuery({ queryKey: ['window-stats', from, to], queryFn: () => getWindowStats({ from, to }) });
  const error = summary.error ?? windows.error;
  if (summary.isLoading) return <Loading rows={4} />;
  const s = summary.data;
  if (!s) return <ProblemAlert error={error} />;
  const t = s.totals;
  const empty = t.issued === 0;
  return (
    <div className="stack">
      <ProblemAlert error={error} />
      {empty && <EmptyState title="За этот период талонов не было">Выберите другой период или запустите нагрузку.</EmptyState>}
      <div className="tiles">
        <Tile value={String(t.issued)} caption="Выдано талонов" />
        <Tile value={String(t.served)} caption="Обслужено" />
        <Tile value={String(t.cancelled)} caption="Отменено" />
        <Tile value={String(t.no_show)} caption="Не подошли" />
        <Tile value={String(t.active)} caption="В работе сейчас" />
        <Tile value={dash(t.avg_wait_sec)} caption="Среднее ожидание" />
        <Tile value={dash(t.max_wait_sec)} caption="Максимальное ожидание" />
        <Tile value={dash(t.avg_service_sec)} caption="Среднее обслуживание" />
      </div>
      {!empty && (
        <div className="grid-2">
          <section className="card">
            <h2>По источникам</h2>
            <div className="table-wrap"><table>
              <thead><tr><th>Источник</th><th className="num">Выдано</th><th className="num">Обслужено</th><th>Ср. ожидание</th><th>Макс. ожидание</th></tr></thead>
              <tbody>{s.by_source.map((r) => (
                <tr key={r.source}><td><SourceBadge source={r.source} /></td><td className="num">{r.issued}</td><td className="num">{r.served}</td><td>{dash(r.avg_wait_sec)}</td><td>{dash(r.max_wait_sec)}</td></tr>
              ))}</tbody>
            </table></div>
          </section>
          <section className="card">
            <h2>По услугам</h2>
            <div className="table-wrap"><table>
              <thead><tr><th>Услуга</th><th className="num">Выдано</th><th className="num">Обслужено</th><th>Ср. ожидание</th><th>Макс. ожидание</th><th>Ср. обслуживание</th></tr></thead>
              <tbody>{s.by_service.map((r) => (
                <tr key={r.service_id}><td>{r.service_name}</td><td className="num">{r.issued}</td><td className="num">{r.served}</td><td>{dash(r.avg_wait_sec)}</td><td>{dash(r.max_wait_sec)}</td><td>{dash(r.avg_service_sec)}</td></tr>
              ))}</tbody>
            </table></div>
          </section>
        </div>
      )}
      <section className="card">
        <h2>Загрузка окон</h2>
        {windows.isLoading ? <Loading /> : (windows.data ?? []).length === 0 ? <EmptyState title="Нет данных по окнам" /> : (
          <div className="table-wrap"><table>
            <thead><tr><th>Окно</th><th>Загрузка</th><th>Открыто</th><th>Обслуживает</th><th>Простой</th><th className="num">Обслужено</th><th>Ср. обслуживание</th></tr></thead>
            <tbody>{(windows.data ?? []).map((w) => (
              <tr key={w.window_id}>
                <td>{w.name}</td>
                <td style={{ minWidth: 180 }}><div className="row"><div style={{ flex: 1 }}><Bar ratio={w.utilization} tone={w.utilization < 0.3 ? 'warn' : 'ok'} /></div><strong>{formatPercent(w.utilization)}</strong></div></td>
                <td>{formatSeconds(w.open_sec)}</td><td>{formatSeconds(w.serving_sec)}</td><td>{formatSeconds(w.idle_sec)}</td>
                <td className="num">{w.served}</td><td>{dash(w.avg_service_sec)}</td>
              </tr>
            ))}</tbody>
          </table></div>
        )}
      </section>
    </div>
  );
}

function DeviationsSection({ query, tz }: { from: string; to: string; tz: string; query: ReturnType<typeof useQuery<Awaited<ReturnType<typeof getDeviations>>>> }) {
  const names = useNames();
  if (query.isLoading) return <Loading rows={4} />;
  if (!query.data) return <ProblemAlert error={query.error} />;
  const d = query.data;
  const order = Object.keys(DEVIATION_TITLES) as DeviationType[];
  const groups = order.map((type) => ({ type, items: d.items.filter((i): i is DeviationItem => i.type === type) }));
  const total = groups.reduce((a, g) => a + g.items.length, 0);
  return (
    <div className="stack">
      {total === 0 && <EmptyState title="Отклонений нет">Просроченных талонов, неявок и простоев за период не найдено.</EmptyState>}
      {groups.filter((g) => g.items.length > 0).map((g) => (
        <section key={g.type} className="card">
          <h2>{DEVIATION_TITLES[g.type]} · {d.counts[g.type] ?? g.items.length}</h2>
          <DeviationList type={g.type} items={g.items} names={names} tz={tz} />
        </section>
      ))}
    </div>
  );
}

function UnfinishedSection({ from, to, tz }: { from: string; to: string; tz: string }) {
  const q = useQuery({ queryKey: ['unfinished', from, to], queryFn: () => getUnfinished({ from, to }) });
  if (q.isLoading) return <Loading rows={4} />;
  if (!q.data) return <ProblemAlert error={q.error} />;
  if (q.data.length === 0) return <EmptyState title="Незавершённых талонов нет" />;
  return (
    <section className="card table-wrap">
      <table>
        <thead><tr><th>Номер</th><th>Услуга</th><th>Источник</th><th>Статус</th><th>Выдан</th><th>Слот</th><th>Дедлайн</th></tr></thead>
        <tbody>{q.data.map((t) => (
          <tr key={t.ticket_id}>
            <td><TicketNumber value={t.display_number} /></td><td>{t.service_name}</td><td><SourceBadge source={t.source} /></td>
            <td><StatusChip status={t.status} /></td><td>{formatDateTime(t.issued_at, tz)}</td>
            <td>{formatDateTime(t.slot_start, tz)}</td><td>{formatDateTime(t.due_at, tz)}</td>
          </tr>
        ))}</tbody>
      </table>
    </section>
  );
}
