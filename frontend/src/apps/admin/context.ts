import { createContext, useContext } from 'react';
import type { BranchInfo } from './api';
import { DEFAULT_TZ } from '@shared/format/time';

export const BranchContext = createContext<BranchInfo | null>(null);

export function useBranch(): BranchInfo | null {
  return useContext(BranchContext);
}

export function useTz(): string {
  return useContext(BranchContext)?.timezone ?? DEFAULT_TZ;
}
