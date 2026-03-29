import { inject, Injectable } from '@angular/core';
import { SentinelleApiService } from '../../../core/services/sentinelle-api.service';
import { JiraProjectsDashboardUtils } from '../jira-projects-dashboard.utils';
import { JiraPiiItemsStorageService } from './jira-pii-items-storage.service';
import { JiraDashboardUiStateService } from './jira-dashboard-ui-state.service';
import { JiraProjectDataManagementService } from './jira-project-data-management.service';
import { JiraSseEventHandlerService } from './jira-sse-event-handler.service';
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
export class JiraScanControlService extends AbstractScanControlService {
  private readonly sentinelleApi = inject(SentinelleApiService);
  private readonly jiraDashboardUtils = inject(JiraProjectsDashboardUtils);
  private readonly jiraPiiItemsStorage = inject(JiraPiiItemsStorageService);
  private readonly jiraUiState = inject(JiraDashboardUiStateService);
  private readonly jiraDataManagement = inject(JiraProjectDataManagementService);
  private readonly jiraSseEventHandler = inject(JiraSseEventHandlerService);

  protected readonly apiAdapter: ScanApiAdapter = {
    startAllStream: (scanId?) => this.sentinelleApi.startAllJiraProjectsStream(scanId),
    startSelectedStream: (keys) => this.sentinelleApi.startSelectedJiraProjectsStream(keys),
    pauseScan: (id) => this.sentinelleApi.pauseScan(id),
    resumeScan: (id) => this.sentinelleApi.resumeScan(id),
  };

  protected readonly piiItemsStorage: ScanPiiItemsStorage = {
    clearItemsForEntity: (key) => this.jiraPiiItemsStorage.clearItemsForProject(key),
    clearAllItems: () => this.jiraPiiItemsStorage.clearAllItems(),
  };

  protected readonly dashboardUtils: ScanDashboardUtils = {
    updateEntity: (key, patch) => this.jiraDashboardUtils.updateProject(key, patch),
    setEntities: (list) => this.jiraDashboardUtils.setProjects(list),
  };

  protected readonly uiState: ScanUiState = {
    append: (line) => this.jiraUiState.append(line),
    clearHistory: () => this.jiraUiState.clearHistory(),
    collapseAllRows: () => this.jiraUiState.collapseAllRows(),
    selectEntity: (key) => this.jiraUiState.selectProject(key),
    activeEntityId: this.jiraUiState.activeProjectKey,
  };

  protected readonly dataManagement: ScanDataManagement = {
    canStartScan: this.jiraDataManagement.canStartScan,
    isEntitiesLoading: this.jiraDataManagement.isProjectsLoading,
    lastScanMeta: this.jiraDataManagement.lastScanMeta,
    lastEntityStatuses: this.jiraDataManagement.lastProjectStatuses,
    entities: this.jiraDataManagement.projects,
    loadLastEntityStatuses: (a, b) => this.jiraDataManagement.loadLastProjectStatuses(a, b),
    loadLastScan: () => this.jiraDataManagement.loadLastScan(),
  };

  protected readonly sseEventHandler: ScanSseEventHandler = {
    routeStreamEvent: (type, data) => this.jiraSseEventHandler.routeStreamEvent(type, data),
  };
}
