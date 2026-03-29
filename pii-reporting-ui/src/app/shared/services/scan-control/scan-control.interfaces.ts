import { Signal, WritableSignal } from '@angular/core';
import { Observable } from 'rxjs';
import { StreamEvent } from '../../../core/models/stream-event';
import {
  ConfluenceContentPersonallyIdentifiableInformationScanResult,
  StreamEventType
} from '../../../core/models/stream-event-type';
import { LastScanMeta, SpaceScanStateDto } from '../../../core/services/sentinelle-api.service';

export interface ScanApiAdapter {
  startAllStream(scanId?: string): Observable<StreamEvent>;
  startSelectedStream(keys: string[]): Observable<StreamEvent>;
  pauseScan(scanId: string): Observable<void>;
  resumeScan(scanId: string): Observable<void>;
}

export interface ScanPiiItemsStorage {
  clearItemsForEntity(key: string): void;
  clearAllItems(): void;
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export interface ScanDashboardUtils {
  updateEntity(key: string, patch: Record<string, any>): void;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  setEntities(list: any): void;
}

export interface ScanUiState {
  append(line: string): void;
  clearHistory(): void;
  collapseAllRows(): void;
  selectEntity(key: string | null): void;
  activeEntityId: WritableSignal<string | null>;
}

export interface ScanDataManagement {
  canStartScan: Signal<boolean>;
  isEntitiesLoading: Signal<boolean>;
  lastScanMeta: WritableSignal<LastScanMeta | null>;
  lastEntityStatuses: WritableSignal<SpaceScanStateDto[]>;
  entities: Signal<unknown[]>;
  loadLastEntityStatuses(isActive: boolean, alsoLoadItems: boolean): Observable<void>;
  loadLastScan(): Observable<void>;
}

export interface ScanSseEventHandler {
  routeStreamEvent(type: StreamEventType, data?: ConfluenceContentPersonallyIdentifiableInformationScanResult): void;
}
