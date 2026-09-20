import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { listServices, listWindows } from './api';

/** Названия услуг и окон для подписей (включая отключённые — в истории они встречаются). */
export function useNames() {
  const services = useQuery({ queryKey: ['services', true], queryFn: () => listServices(true), staleTime: 60_000 });
  const windows = useQuery({ queryKey: ['windows', true], queryFn: () => listWindows(true), staleTime: 60_000 });
  return useMemo(() => {
    const s = new Map((services.data ?? []).map((x) => [x.id, x.name]));
    const w = new Map((windows.data ?? []).map((x) => [x.id, x.name]));
    return {
      service: (id: unknown) => (typeof id === 'string' ? s.get(id) ?? 'Услуга' : '—'),
      window: (id: unknown) => (typeof id === 'string' ? w.get(id) ?? 'Окно' : '—'),
    };
  }, [services.data, windows.data]);
}
