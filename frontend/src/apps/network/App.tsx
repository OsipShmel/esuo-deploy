import { NavLink, Route, Routes } from 'react-router-dom';
import { AuthGate } from '@shared/auth/AuthGate';
import { A11yToggles } from '@shared/ui';
import { Clock } from '@shared/ui/Clock';
import { ConfigPage } from './pages/ConfigPage';
import { NotificationsPage } from './pages/NotificationsPage';
import { OverviewPage } from './pages/OverviewPage';
import { ToastProvider } from './toast';

const TABS = [
  { to: '/network', title: 'Сеть', end: true },
  { to: '/network/notifications', title: 'Уведомления', end: false },
  { to: '/network/config', title: 'Конфигурация', end: false },
];

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
      <nav className="tabs" aria-label="Разделы">
        {TABS.map((t) => <NavLink key={t.to} to={t.to} end={t.end} className={({ isActive }) => `tab${isActive ? ' active' : ''}`}>{t.title}</NavLink>)}
      </nav>
      <main className="page">
        <Routes>
          <Route path="/network" element={<OverviewPage />} />
          <Route path="/network/notifications" element={<NotificationsPage />} />
          <Route path="/network/config" element={<ConfigPage />} />
          <Route path="*" element={<OverviewPage />} />
        </Routes>
      </main>
    </ToastProvider>
  );
}
