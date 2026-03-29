import { inject, Injectable } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';
import { ScanProgressService } from '../../../core/services/scan-progress.service';
import { ToastService } from '../../../core/services/toast.service';
import { ConfluenceContentPersonallyIdentifiableInformationScanResult } from '../../../core/models/stream-event-type';
import { JiraProjectsDashboardUtils } from '../jira-projects-dashboard.utils';
import { coerceSpaceKey, formatEventLog, isAttachmentPayload, StreamEventType } from '../../confluence-dashboard/spaces-dashboard-stream.utils';
import { JiraProjectDataManagementService } from './jira-project-data-management.service';
import { JiraPiiItemsStorageService } from './jira-pii-items-storage.service';
import { JiraDashboardUiStateService } from './jira-dashboard-ui-state.service';

@Injectable({
  providedIn: 'root'
})
export class JiraSseEventHandlerService {
  private readonly dashboardUtils = inject(JiraProjectsDashboardUtils);
  private readonly translocoService = inject(TranslocoService);
  private readonly scanProgressService = inject(ScanProgressService);
  private readonly toastService = inject(ToastService);
  private readonly dataManagement = inject(JiraProjectDataManagementService);
  private readonly piiItemsStorage = inject(JiraPiiItemsStorageService);
  private readonly uiStateService = inject(JiraDashboardUiStateService);

  routeStreamEvent(type: StreamEventType, payload?: ConfluenceContentPersonallyIdentifiableInformationScanResult): void {
    this.uiStateService.append(formatEventLog(type, JSON.stringify(payload ?? {})));

    if (type === 'multiStart') {
      this.handleAllProjectsScanStart(payload);
      return;
    }

    if (!payload) return;

    switch (type) {
      case 'start':
        this.handleStreamScanStart(payload);
        break;
      case 'pageStart':
        this.handlePageStart(payload);
        break;
      case 'item':
      case 'attachmentItem':
        this.handleItemEvent(payload);
        break;
      case 'scanError':
        this.handleStreamError(payload);
        break;
      case 'complete':
        this.handleStreamComplete(payload);
        break;
      default:
        break;
    }
  }

  private handleAllProjectsScanStart(payload?: ConfluenceContentPersonallyIdentifiableInformationScanResult): void {
    if (payload) {
      this.ensureLastScanMetaFromPayload(payload);
    }

    const projects = this.dataManagement.projects();
    if (!Array.isArray(projects) || projects.length === 0) return;

    // Identify projects already completed in the current scan cycle
    // so we don't reset their status when resuming a paused scan
    const completedKeys = new Set(
      this.dataManagement.lastProjectStatuses()
        .filter(s => s.status === 'COMPLETED')
        .map(s => s.spaceKey.trim().toLowerCase())
    );

    this.dataManagement.queue.set(
      projects
        .filter(p => !completedKeys.has(p.key.trim().toLowerCase()))
        .map(p => p.key)
    );

    for (const project of projects) {
      if (!completedKeys.has(project.key.trim().toLowerCase())) {
        this.dashboardUtils.updateProject(project.key, { status: 'PENDING' });
      }
    }

    if (!this.uiStateService.selectedProjectKey()) {
      this.uiStateService.selectedProjectKey.set(projects[0].key);
    }
  }

  private handleStreamScanStart(payload: ConfluenceContentPersonallyIdentifiableInformationScanResult): void {
    const projectKey = payload.spaceKey;
    if (!projectKey) return;

    this.ensureLastScanMetaFromPayload(payload);

    this.uiStateService.activeProjectKey.set(projectKey);
    if (!this.uiStateService.selectedProjectKey()) {
      this.uiStateService.selectedProjectKey.set(projectKey);
    }

    const currentQueue = this.dataManagement.queue();
    this.dataManagement.queue.set(
      currentQueue.filter((queuedKey) => queuedKey !== projectKey)
    );

    const current = this.scanProgressService.getProgress()[projectKey]?.percent;
    const percent = this.extractPercent(payload) ?? current ?? 0;
    const total = (payload as Record<string, unknown>).pagesTotal as number | undefined;
    const prevTotal = this.scanProgressService.getProgress()[projectKey]?.total;
    this.updateProgress(projectKey, { total: total ?? prevTotal, index: 0, percent });

    this.uiStateService.upsertScanHistory(projectKey, 'running');
    this.dashboardUtils.updateProject(projectKey, { status: 'RUNNING' });
  }

  private handlePageStart(payload: ConfluenceContentPersonallyIdentifiableInformationScanResult): void {
    const projectKey = payload.spaceKey;
    if (!projectKey) return;

    const currentProgress = this.scanProgressService.getProgress()[projectKey] ?? {};
    const total = (payload as Record<string, unknown>).pagesTotal ?? currentProgress.total;
    const index = (payload as Record<string, unknown>).pageIndex ?? currentProgress.index;

    let percent = this.extractPercent(payload);
    if (percent == null && typeof total === 'number' && typeof index === 'number' && total > 0) {
      percent = Math.round((index / total) * 100);
    }

    this.updateProgress(projectKey, { total: total as number, index: index as number, percent });
  }

  private handleItemEvent(payload: ConfluenceContentPersonallyIdentifiableInformationScanResult): void {
    const incomingKey = coerceSpaceKey(payload);
    const looksLikeAttachment = isAttachmentPayload(payload);
    const projectKey = incomingKey ?? (looksLikeAttachment ? this.uiStateService.activeProjectKey() : null);

    if (!projectKey) return;

    const wasAdded = this.piiItemsStorage.addPiiItemToProject(projectKey, payload);

    const progressPercent = this.extractPercent(payload);
    if (progressPercent != null && payload.status !== 'COMPLETED') {
      this.updateProgress(projectKey, { percent: progressPercent });
    }

    const timestamp = (payload as Record<string, unknown>).ts as string ?? new Date().toISOString();

    if (wasAdded) {
      const currentCounts = this.dashboardUtils.getProjectCounts(projectKey);
      const summary = payload.nbOfDetectedPIIBySeverity;
      const deltaHigh = summary?.high ?? summary?.HIGH ?? 0;
      const deltaMedium = summary?.medium ?? summary?.MEDIUM ?? 0;
      const deltaLow = summary?.low ?? summary?.LOW ?? 0;
      const deltaTotal = deltaHigh + deltaMedium + deltaLow;

      this.dashboardUtils.updateProject(projectKey, {
        lastScanTs: timestamp,
        status: 'RUNNING',
        counts: {
          total: currentCounts.total + deltaTotal,
          high: currentCounts.high + deltaHigh,
          medium: currentCounts.medium + deltaMedium,
          low: currentCounts.low + deltaLow
        }
      });
    } else {
      this.dashboardUtils.updateProject(projectKey, {
        lastScanTs: timestamp,
        status: 'RUNNING'
      });
    }
  }

  private handleStreamError(payload: ConfluenceContentPersonallyIdentifiableInformationScanResult): void {
    const projectKey = payload.spaceKey;
    if (!projectKey) return;

    const errorMessage = (payload as Record<string, unknown>)?.message as string
      ?? (payload as Record<string, unknown>)?.errorMessage as string
      ?? 'Unknown error';
    const errorType = this.toastService.detectErrorType(errorMessage);

    this.toastService.showScanError({
      scanId: payload.scanId ?? '',
      spaceKey: projectKey,
      pageId: payload.pageId == null ? undefined : String(payload.pageId),
      pageTitle: payload.pageTitle,
      attachmentName: payload.attachmentName,
      errorMessage,
      errorType
    });

    this.dashboardUtils.updateProject(projectKey, {
      lastScanTs: new Date().toISOString()
    });
  }

  private handleStreamComplete(payload: ConfluenceContentPersonallyIdentifiableInformationScanResult): void {
    const projectKey = payload.spaceKey;
    if (!projectKey) return;

    this.uiStateService.upsertScanHistory(projectKey, 'completed');
    this.updateProgress(projectKey, { percent: 100 });

    this.dashboardUtils.updateProject(projectKey, {
      status: 'OK',
      lastScanTs: new Date().toISOString()
    });

    if (this.uiStateService.activeProjectKey() === projectKey) {
      this.uiStateService.activeProjectKey.set(null);
    }
  }

  private ensureLastScanMetaFromPayload(payload: ConfluenceContentPersonallyIdentifiableInformationScanResult): void {
    try {
      const scanId = (payload as Record<string, unknown>)?.scanId as string | undefined;
      if (!scanId) return;

      const current = this.dataManagement.lastScanMeta();
      if (current?.scanId !== scanId) {
        const timestamp = (payload as Record<string, unknown>)?.ts as string ?? new Date().toISOString();
        const projectsCount = this.dataManagement.projects().length;
        this.dataManagement.lastScanMeta.set({
          scanId,
          lastUpdated: timestamp,
          spacesCount: projectsCount
        });
      }
    } catch {
      // Ignore parsing errors
    }
  }

  private extractPercent(payload: ConfluenceContentPersonallyIdentifiableInformationScanResult): number | undefined {
    return this.scanProgressService.extractPercentFromPayload(payload);
  }

  private updateProgress(
    projectKey: string,
    patch: Partial<{ total: number; index: number; percent: number }>
  ): void {
    this.scanProgressService.updateProgress(projectKey, patch);
  }
}
