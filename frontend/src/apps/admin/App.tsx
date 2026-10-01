import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { AuthGate } from '@shared/auth/AuthGate';
import { A11yToggles } from '@shared/ui';
import { getBranch, getRecommendations, getDeviations } from './api';
import { BranchContext } from './context';
import { RecommendationBanner } from './pages/RecommendationBanner';
import { QueuePage } from './pages/QueuePage';
import { AnalyticsPage } from './pages/AnalyticsPage';
import { IncidentsPage } from './pages/IncidentsPage';
import { ConfigPage } from './pages/ConfigPage';
import { CatalogPage } from './pages/CatalogPage';
import { LoadPage } from './pages/LoadPage';
import { StickerPage } from './pages/StickerPage';

const TABS = [
  { id: 'queue', title: 'Очередь' },
  { id: 'load', title: 'Нагрузка' },
  { id: 'analytics', title: 'Аналитика' },
  { id: 'incidents', title: 'Инциденты' },
  { id: 'config', title: 'Настройки' },
  { id: 'catalog', title: 'Справочники' },
  { id: 'qr', title: 'QR-наклейка' },
] as const;
type TabId = (typeof TABS)[number]['id'];

function tabFromHash(): TabId {
  const h = window.location.hash.replace('#', '');
  return TABS.some((t) => t.id === h) ? (h as TabId) : 'queue';
}

export function App() {
  return (
    <AuthGate title="ЕСУО · Руководитель отделения" roles={['BRANCH_ADMIN']} forbiddenText="Экран руководителя открывается только под учётной записью руководителя. Оператору нужна страница /operator.html." defaultLogin="admin">
      {({ logout }) => <Shell onLogout={logout} />}
    </AuthGate>
  );
}

function Shell({ onLogout }: { onLogout: () => void }) {
  const [tab, setTab] = useState<TabId>(tabFromHash);
  const branch = useQuery({ queryKey: ['branch'], queryFn: getBranch, staleTime: 300_000 });
  const recommendations = useQuery({ queryKey: ['recommendations'], queryFn: getRecommendations, refetchInterval: 15_000 });
  const deviations = useQuery({ queryKey: ['deviations', 'today'], queryFn: () => getDeviations({}), refetchInterval: 30_000 });

  useEffect(() => {
    const onHash = () => setTab(tabFromHash());
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);

  const openIncidents = deviations.data?.counts.open_incident ?? 0;

  return (
    <BranchContext.Provider value={branch.data ?? null}>
      <header className="app-header">
        <h1>ЕСУО · Руководитель</h1>
        <span className="muted">{branch.data?.name ?? ''}</span>
        <span className="grow" />
        <A11yToggles />
        <button type="button" className="btn btn-sm" onClick={onLogout}>Выйти</button>
      </header>
      <nav className="tabs" role="tablist" aria-label="Разделы">
        {TABS.map((t) => (
          <button key={t.id} type="button" role="tab" className="tab" aria-selected={tab === t.id}
            onClick={() => { window.location.hash = t.id; }}>
            {t.title}
            {t.id === 'incidents' && openIncidents > 0 && <span className="chip tone-danger count">{openIncidents}</span>}
          </button>
        ))}
      </nav>
      <main className="page">
        <RecommendationBanner data={recommendations.data} />
        {tab === 'queue' && <QueuePage />}
        {tab === 'load' && <LoadPage />}
        {tab === 'analytics' && <AnalyticsPage />}
        {tab === 'incidents' && <IncidentsPage />}
        {tab === 'config' && <ConfigPage />}
        {tab === 'catalog' && <CatalogPage />}
        {tab === 'qr' && <StickerPage />}
      </main>
    </BranchContext.Provider>
  );
}
