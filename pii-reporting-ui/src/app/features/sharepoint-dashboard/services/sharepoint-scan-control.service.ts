import { inject, Injectable } from '@angular/core';
import { SentinelleApiService } from '../../../core/services/sentinelle-api.service';
import { SharePointSitesDashboardUtils } from '../sharepoint-sites-dashboard.utils';
import { SharePointPiiItemsStorageService } from './sharepoint-pii-items-storage.service';
import { SharePointDashboardUiStateService } from './sharepoint-dashboard-ui-state.service';
import { SharePointSiteDataManagementService } from './sharepoint-site-data-management.service';
import { SharePointSseEventHandlerService } from './sharepoint-sse-event-handler.service';
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
export class SharePointScanControlService extends AbstractScanControlService {
  private readonly sentinelleApi = inject(SentinelleApiService);
  private readonly sharePointDashboardUtils = inject(SharePointSitesDashboardUtils);
  private readonly sharePointPiiItemsStorage = inject(SharePointPiiItemsStorageService);
  private readonly sharePointUiState = inject(SharePointDashboardUiStateService);
  private readonly sharePointDataManagement = inject(SharePointSiteDataManagementService);
  private readonly sharePointSseEventHandler = inject(SharePointSseEventHandlerService);

  protected readonly apiAdapter: ScanApiAdapter = {
    startAllStream: (scanId?) => this.sentinelleApi.startAllSharePointSitesStream(scanId),
    startSelectedStream: (keys) => this.sentinelleApi.startSelectedSharePointSitesStream(keys),
    pauseScan: (id) => this.sentinelleApi.pauseScan(id),
    resumeScan: (id) => this.sentinelleApi.resumeScan(id),
  };

  protected readonly piiItemsStorage: ScanPiiItemsStorage = {
    clearItemsForEntity: (key) => this.sharePointPiiItemsStorage.clearItemsForSite(key),
    clearAllItems: () => this.sharePointPiiItemsStorage.clearAllItems(),
  };

  protected readonly dashboardUtils: ScanDashboardUtils = {
    updateEntity: (key, patch) => this.sharePointDashboardUtils.updateSite(key, patch),
    setEntities: (list) => this.sharePointDashboardUtils.setSites(list),
  };

  protected readonly uiState: ScanUiState = {
    append: (line) => this.sharePointUiState.append(line),
    clearHistory: () => this.sharePointUiState.clearHistory(),
    collapseAllRows: () => this.sharePointUiState.collapseAllRows(),
    selectEntity: (key) => this.sharePointUiState.selectSite(key),
    activeEntityId: this.sharePointUiState.activeSiteId,
  };

  protected readonly dataManagement: ScanDataManagement = {
    canStartScan: this.sharePointDataManagement.canStartScan,
    isEntitiesLoading: this.sharePointDataManagement.isSitesLoading,
    lastScanMeta: this.sharePointDataManagement.lastScanMeta,
    lastEntityStatuses: this.sharePointDataManagement.lastSiteStatuses,
    entities: this.sharePointDataManagement.sites,
    loadLastEntityStatuses: (a, b) => this.sharePointDataManagement.loadLastSiteStatuses(a, b),
    loadLastScan: () => this.sharePointDataManagement.loadLastScan(),
  };

  protected readonly sseEventHandler: ScanSseEventHandler = {
    routeStreamEvent: (type, data) => this.sharePointSseEventHandler.routeStreamEvent(type, data),
  };
}
