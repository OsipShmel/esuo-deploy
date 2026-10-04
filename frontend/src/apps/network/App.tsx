import { Link, Route, Routes, useLocation } from 'react-router-dom';
import { AuthGate } from '@shared/auth/AuthGate';
import { A11yToggles } from '@shared/ui';
import { Clock } from '@shared/ui/Clock';
import { BranchPage } from './pages/BranchPage';
import { ConfigPage } from './pages/ConfigPage';
import { ListPage } from './pages/ListPage';
import { NotificationsPage } from './pages/NotificationsPage';
import { OverviewPage } from './pages/OverviewPage';
import { ToastProvider } from './toast';

const TABS = [
  { to: '/network/notifications', title: 'Уведомления' },
  { to: '/network/config', title: 'Конфигурация' },
];
/** «Сеть» — главная, регионы, списки и панели отделений; остальные вкладки — по своему адресу. */
const TAB_NETWORK = { to: '/network', title: 'Сеть' };

/** API центра закрыт (кроме публичного шлюза клиента): экран работает под учётной записью оператора сети. */
export function App() {
  return (
    <AuthGate title="ЕСУО · Сеть отделений" roles={['NETWORK_ADMIN']} forbiddenText="Экран доступен только оператору сети." defaultLogin="network">
      {({ logout }) => <Shell onLogout={logout} />}
    </AuthGate>
  );
}

function Shell({ onLogout }: { onLogout: () => void }) {
  return (
    <ToastProvider>
      <header className="app-header">
        <h1>ЕСУО · Сеть отделений</h1>
        <span className="grow" />
        <Clock />
        <A11yToggles />
        <button type="button" className="btn btn-sm" onClick={onLogout}>Выйти</button>
      </header>
      <Tabs />
      <main className="page">
        <Routes>
          <Route path="/network" element={<OverviewPage />} />
          <Route path="/network/regions/:region" element={<ListPage />} />
          <Route path="/network/list" element={<ListPage />} />
          <Route path="/network/b/:id" element={<BranchPage />} />
          <Route path="/network/notifications" element={<NotificationsPage />} />
          <Route path="/network/config" element={<ConfigPage />} />
          <Route path="*" element={<OverviewPage />} />
        </Routes>
      </main>
    </ToastProvider>
  );
}

function Tabs() {
  const { pathname } = useLocation();
  const active = TABS.find((t) => pathname.startsWith(t.to))?.to ?? TAB_NETWORK.to;
  return (
    <nav className="tabs" aria-label="Разделы">
      {[TAB_NETWORK, ...TABS].map((t) => (
        <Link key={t.to} to={t.to} className={`tab${active === t.to ? ' active' : ''}`} aria-current={active === t.to ? 'page' : undefined}>{t.title}</Link>
      ))}
    </nav>
  );
}
