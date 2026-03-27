import { SeverityCounts } from './severity-counts';

export interface Space {
  status?: 'FAILED' | 'RUNNING' | 'OK' | 'PENDING' | 'NOT_STARTED' | 'INTERRUPTED' | 'PAUSED';
  key: string;
  name?: string;
  url?: string;
  counts?: SeverityCounts;
}
