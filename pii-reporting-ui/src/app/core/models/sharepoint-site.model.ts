import { SeverityCounts } from './severity-counts';

export interface SharePointSite {
  id: string;
  name: string;
  webUrl?: string;
  description?: string;
}

export type SharePointSiteStatus = 'FAILED' | 'RUNNING' | 'OK' | 'PENDING' | 'INTERRUPTED' | 'PAUSED';

export interface UISharePointSite extends SharePointSite {
  status?: SharePointSiteStatus;
  lastScanTs?: string;
  counts?: SeverityCounts;
  originalIndex: number;
}
