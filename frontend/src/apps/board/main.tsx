import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ErrorBoundary } from '@shared/ui';
import './board.css';
import { Board } from './Board';

// Табло всегда в тёмной теме: светлый экран в зале слепит (концепция 3.1).
document.documentElement.dataset.theme = 'dark';

const queryClient = new QueryClient({ defaultOptions: { queries: { retry: 2, refetchOnWindowFocus: false } } });

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <ErrorBoundary><Board /></ErrorBoundary>
    </QueryClientProvider>
  </StrictMode>,
);
