import { NavLink, Route, Routes } from 'react-router-dom';
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

export function App() {
  return (
    <ToastProvider>
      <header className="app-header">
        <h1>ЕСУО · Сеть отделений</h1>
        {/* Центр пока без ролей (SERVICE_MANAGER / NETWORK_MONITOR): ручки открыты, вход не настроен. */}
        <span className="chip tone-muted" title="Роли центра ещё не включены">вход не настроен</span>
        <span className="grow" />
        <Clock />
        <A11yToggles />
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
