import { useEffect, useRef, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { ApiError } from '@shared/api/http';
import { accessToken } from '@shared/api/session';
import { useEventStream } from '@shared/sse/useEventStream';
import { EmptyState, Loading, ProblemAlert } from '@shared/ui';
import { getRunnerScenarios, startRun, stopRun, type RunArgs, type RunnerLine, type RunnerRun, type RunnerScenario } from './api';

const STATE_TEXT: Record<RunnerRun['state'], string> = { running: 'идёт', passed: 'пройден', failed: 'есть провалы', stopped: 'остановлен' };
const STATE_TONE: Record<RunnerRun['state'], string> = { running: 'accent', passed: 'ok', failed: 'danger', stopped: 'warn' };
const KIND_TEXT: Record<RunnerScenario['kind'], string> = { scenario: '', chaos: 'хаос', tool: 'служебное' };
const MAX_SHOWN = 2000;

/**
 * Вкладка «Сценарии»: запуск `scripts/demo/run.sh` кнопкой и живой вывод (план финала, D5). Ходит на узел, узел — в
 * demo-runner. Одновременно идёт один прогон; поток SSE после переподключения присылает снимок заново.
 */
export function ScenarioRunner() {
  const scenarios = useQuery({ queryKey: ['runner-scenarios'], queryFn: getRunnerScenarios, retry: false, staleTime: 60_000 });
  const [run, setRun] = useState<RunnerRun | null>(null);
  const [lines, setLines] = useState<RunnerLine[]>([]);
  const [fast, setFast] = useState(false);
  const [follow, setFollow] = useState(true);
  const logRef = useRef<HTMLOListElement>(null);
  const available = !!scenarios.data;
  const token = accessToken();

  const stream = useEventStream({
    url: available && token ? `/api/v1/admin/demo/runner/stream?access_token=${encodeURIComponent(token)}` : null,
    events: ['line', 'state'],
    onSnapshot: (data) => {
      const s = data as { run: RunnerRun | null; lines: RunnerLine[] };
      setRun(s.run);
      setLines(s.lines.slice(-MAX_SHOWN));
    },
    onEvent: (name, data) => {
      if (name === 'state') setRun(data as RunnerRun);
      if (name === 'line') setLines((cur) => [...cur, data as RunnerLine].slice(-MAX_SHOWN));
    },
  });

  const start = useMutation({ mutationFn: (v: { code: string; args: RunArgs }) => startRun(v.code, v.args), onSuccess: (r) => { setRun(r.run); setLines([]); setFollow(true); } });
  const stop = useMutation({ mutationFn: stopRun });

  // Живой лог держится у последней строки, пока человек сам не прокрутил вверх.
  useEffect(() => {
    const el = logRef.current;
    if (follow && el) el.scrollTop = el.scrollHeight;
  }, [lines, follow]);

  if (scenarios.isLoading) return <Loading rows={4} />;
  if (scenarios.error) {
    const down = scenarios.error instanceof ApiError && scenarios.error.code === 'DEMO_RUNNER_UNAVAILABLE';
    return (
      <section className="card stack">
        <h2>Сценарии</h2>
        <ProblemAlert error={scenarios.error} />
        {down && <p className="muted small">Запуск сервиса на стенде: <code>docker compose --profile demo up -d --build demo-runner</code></p>}
        <div className="row"><button type="button" className="btn btn-sm" onClick={() => void scenarios.refetch()}>Проверить ещё раз</button></div>
      </section>
    );
  }
  if (!scenarios.data) {
    return (
      <EmptyState title="Пульт сценариев не подключён">
        Нужны DEMO_MODE=true на узле и сервис demo-runner (<code>docker compose --profile demo up -d</code>). Без него сценарии
        запускаются из терминала: <code>./scripts/demo/run.sh list</code>.
      </EmptyState>
    );
  }

  const running = run?.state === 'running';
  const title = (code: string) => scenarios.data?.scenarios.find((s) => s.code === code)?.title ?? code;
  const list = scenarios.data.scenarios;

  return (
    <div className="runner-grid">
      <section className="card stack" aria-label="Сценарии">
        <div className="row between">
          <h2 style={{ margin: 0 }}>Сценарии</h2>
          <label className="check" title="Без пауз между шагами: быстрее, но за ходом на экранах не уследить">
            <input type="checkbox" checked={fast} onChange={(e) => setFast(e.target.checked)} /> без пауз
          </label>
        </div>
        <ProblemAlert error={start.error ?? stop.error} />
        <ul className="runner-list">
          {list.map((s) => (
            <li key={s.code} className={`runner-card${run?.code === s.code && running ? ' active' : ''}`}>
              <div>
                <strong>{s.title}</strong>
                {KIND_TEXT[s.kind] && <span className={`chip tone-${s.kind === 'chaos' ? 'warn' : 'muted'}`} style={{ marginLeft: 8 }}>{KIND_TEXT[s.kind]}</span>}
                <div className="muted small">{s.desc}</div>
              </div>
              <button type="button" className="btn btn-sm btn-primary" disabled={running || start.isPending}
                title={running ? 'Одновременно идёт один прогон' : undefined}
                onClick={() => start.mutate({ code: s.code, args: s.kind === 'tool' ? {} : { fast } })}>Запустить</button>
            </li>
          ))}
        </ul>
        <p className="muted small" style={{ margin: 0 }}>Итоги прогонов и подпись хода событий пишутся в <code>docs/demo/jury/runs.md</code>, как при запуске из терминала.</p>
      </section>

      <section className="card stack" aria-label="Ход прогона">
        <div className="row between">
          <h2 style={{ margin: 0 }}>{run ? title(run.code) : 'Ход прогона'}</h2>
          {run && (
            <div className="row">
              <span className={`chip tone-${STATE_TONE[run.state]}`}>{STATE_TEXT[run.state]}</span>
              <span className="runner-count tone-ok" title="Проверок пройдено">✔ {run.pass}</span>
              <span className={`runner-count${run.fail > 0 ? ' tone-danger' : ' muted'}`} title="Проверок провалено">✘ {run.fail}</span>
              {running && <button type="button" className="btn btn-sm btn-danger" disabled={stop.isPending}
                title="Останавливает прогон и возвращает связь отделений и уведомления" onClick={() => stop.mutate()}>Остановить</button>}
            </div>
          )}
        </div>
        {stream.state !== 'live' && <p className="muted small" style={{ margin: 0 }}>Соединение с пультом восстанавливается…</p>}
        {lines.length === 0 ? (
          <p className="muted">{run ? 'Ждём первые строки…' : 'Выберите сценарий слева — здесь появится его ход по шагам.'}</p>
        ) : (
          <ol className="runner-log" ref={logRef} aria-live="polite"
            onScroll={(e) => { const el = e.currentTarget; setFollow(el.scrollHeight - el.scrollTop - el.clientHeight < 40); }}>
            {lines.map((l) => <li key={l.n} className={`k-${l.kind}`}>{l.text}</li>)}
          </ol>
        )}
      </section>
    </div>
  );
}
