import type { ReactNode } from 'react';
import './components.css';

export function EmptyState({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="empty">
      <strong>{title}</strong>
      {children}
    </div>
  );
}

export function Loading({ rows = 3 }: { rows?: number }) {
  return (
    <div aria-busy="true" aria-label="Загрузка" style={{ display: 'grid', gap: 8 }}>
      {Array.from({ length: rows }, (_, i) => <div key={i} className="skeleton" style={{ height: 20 }} />)}
    </div>
  );
}
