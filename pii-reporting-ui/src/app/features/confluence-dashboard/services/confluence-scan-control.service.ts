import { inject, Injectable } from '@angular/core';
import { SentinelleApiService } from '../../../core/services/sentinelle-api.service';
import { SpacesDashboardUtils } from '../spaces-dashboard.utils';
import { PiiItemsStorageService } from './pii-items-storage.service';
import { DashboardUiStateService } from './dashboard-ui-state.service';
import { SpaceDataManagementService } from './space-data-management.service';
import { SseEventHandlerService } from './sse-event-handler.service';
import { AbstractScanControlService } from '../../../shared/services/scan-control/abstract-scan-control.service';
import {
  ScanApiAdapter,
  ScanDashboardUtils,
  ScanDataManagement,
  ScanPiiItemsStorage,
  ScanSseEventHandler,
  ScanUiState
} from '../../../shared/services/scan-control/scan-control.interfaces';

@Injectable({
  providedIn: 'root'
})
export class ConfluenceScanControlService extends AbstractScanControlService {
  protected override readonly clearStatusesOnReset = true;

  private readonly sentinelleApi = inject(SentinelleApiService);
  private readonly spacesDashboardUtils = inject(SpacesDashboardUtils);
  private readonly piiItemsStorageService = inject(PiiItemsStorageService);
  private readonly dashboardUiState = inject(DashboardUiStateService);
  private readonly spaceDataManagement = inject(SpaceDataManagementService);
  private readonly sseEventHandlerService = inject(SseEventHandlerService);

  protected readonly apiAdapter: ScanApiAdapter = {
    startAllStream: (scanId?) => this.sentinelleApi.startAllSpacesStream(scanId),
    startSelectedStream: (keys) => this.sentinelleApi.startSelectedSpacesStream(keys),
    pauseScan: (id) => this.sentinelleApi.pauseScan(id),
    resumeScan: (id) => this.sentinelleApi.resumeScan(id),
  };

  protected readonly piiItemsStorage: ScanPiiItemsStorage = {
    clearItemsForEntity: (key) => this.piiItemsStorageService.clearItemsForSpace(key),
    clearAllItems: () => this.piiItemsStorageService.clearAllItems(),
  };

  protected readonly dashboardUtils: ScanDashboardUtils = {
    updateEntity: (key, patch) => this.spacesDashboardUtils.updateSpace(key, patch),
    setEntities: (list) => this.spacesDashboardUtils.setSpaces(list),
  };

  protected readonly uiState: ScanUiState = {
    append: (line) => this.dashboardUiState.append(line),
    clearHistory: () => this.dashboardUiState.clearHistory(),
    collapseAllRows: () => this.dashboardUiState.collapseAllRows(),
    selectEntity: (key) => this.dashboardUiState.selectSpace(key),
    activeEntityId: this.dashboardUiState.activeSpaceKey,
  };

  protected readonly dataManagement: ScanDataManagement = {
    canStartScan: this.spaceDataManagement.canStartScan,
    isEntitiesLoading: this.spaceDataManagement.isSpacesLoading,
    lastScanMeta: this.spaceDataManagement.lastScanMeta,
    lastEntityStatuses: this.spaceDataManagement.lastSpaceStatuses,
    entities: this.spaceDataManagement.spaces,
    loadLastEntityStatuses: (_isActive, alsoLoadItems) => this.spaceDataManagement.loadLastSpaceStatuses(alsoLoadItems),
    loadLastScan: () => this.spaceDataManagement.loadLastScan(),
  };

  protected readonly sseEventHandler: ScanSseEventHandler = {
    routeStreamEvent: (type, data) => this.sseEventHandlerService.routeStreamEvent(type, data),
  };
}
