import { Icon } from '@shared/ui';
import type { RecommendationReason, Recommendations } from '../api';
import { formatSeconds } from '@shared/format/duration';

const REASON: Record<RecommendationReason, string> = {
  eta_overrun: 'ожидание превышает дедлайн',
  overdue_count: 'много просроченных',
  no_open_window: 'нет открытого окна',
};

/** Баннер «откройте окно» (постановка, раздел 7). Кнопки «открыть» здесь нет: окно открывает оператор. */
export function RecommendationBanner({ data }: { data: Recommendations | undefined }) {
  if (!data?.recommend_open_window) return null;
  return (
    <section className="banner tone-warn" role="alert" aria-label="Рекомендация по окнам">
      <Icon name="alert" size={22} />
      <div className="banner-body">
        <strong>Рекомендуем открыть дополнительное окно</strong>
        <ul style={{ margin: '4px 0 0', paddingLeft: 20 }}>
          {data.services.map((s) => (
            <li key={s.service_id}>
              <strong>{s.service_name}</strong>: ждут {s.waiting}
              {s.overdue > 0 && `, просрочено ${s.overdue}`}
              {s.max_eta_overrun_sec !== null && s.max_eta_overrun_sec > 0 && `, задержка до ${formatSeconds(s.max_eta_overrun_sec)}`}
              {' — '}{s.reasons.map((r) => REASON[r] ?? r).join('; ')}.
              {s.suggested_windows.length > 0
                ? ` Открыть можно: ${s.suggested_windows.map((w) => w.name).join(', ')}.`
                : ' Подходящих закрытых окон нет.'}
            </li>
          ))}
        </ul>
      </div>
    </section>
  );
}
