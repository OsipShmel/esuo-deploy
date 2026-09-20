import { createContext, useContext } from 'react';
import { Link, Route, Routes } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { A11yToggles, EmptyState, Loading } from '@shared/ui';
import { detectApi, type BranchApi } from './branchApi';
import { BranchPage } from './pages/BranchPage';
import { BookPage } from './pages/BookPage';
import { HomePage } from './pages/HomePage';
import { JoinPage } from './pages/JoinPage';
import { TicketPage } from './pages/TicketPage';

const ApiContext = createContext<BranchApi | null>(null);

export function useBranchApi(): BranchApi {
  const a = useContext(ApiContext);
  if (!a) throw new Error('BranchApi не готов');
  return a;
}

export function App() {
  const detected = useQuery({ queryKey: ['api-mode'], queryFn: detectApi, staleTime: Infinity, retry: 0 });
  return (
    <ApiContext.Provider value={detected.data ?? null}>
      <div className="shell">
        <header className="topbar">
          <Link to="/"><strong>ЕСУО</strong> · Электронная очередь</Link>
          <A11yToggles />
        </header>
        {detected.isLoading || !detected.data ? <Loading rows={4} /> : (
          <Routes>
            <Route path="/" element={<HomePage />} />
            <Route path="/b/:b" element={<BranchPage />} />
            <Route path="/b/:b/book" element={<BookPage />} />
            <Route path="/b/:b/join" element={<JoinPage />} />
            <Route path="/b/:b/t/:token" element={<TicketPage />} />
            <Route path="*" element={<EmptyState title="Страница не найдена"><Link to="/">На главную</Link></EmptyState>} />
          </Routes>
        )}
      </div>
    </ApiContext.Provider>
  );
}
