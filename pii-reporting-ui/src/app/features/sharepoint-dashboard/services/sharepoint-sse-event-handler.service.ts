import { inject, Injectable } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';
import { ScanProgressService } from '../../../core/services/scan-progress.service';
import { ToastService } from '../../../core/services/toast.service';
import { ConfluenceContentPersonallyIdentifiableInformationScanResult } from '../../../core/models/stream-event-type';
import { SharePointSitesDashboardUtils } from '../sharepoint-sites-dashboard.utils';
import { coerceSpaceKey, formatEventLog, isAttachmentPayload, StreamEventType } from '../../confluence-dashboard/spaces-dashboard-stream.utils';
import { SharePointSiteDataManagementService } from './sharepoint-site-data-management.service';
import { SharePointPiiItemsStorageService } from './sharepoint-pii-items-storage.service';
import { SharePointDashboardUiStateService } from './sharepoint-dashboard-ui-state.service';

@Injectable({
  providedIn: 'root'
})
export class SharePointSseEventHandlerService {
  private readonly dashboardUtils = inject(SharePointSitesDashboardUtils);
  private readonly translocoService = inject(TranslocoService);
  private readonly scanProgressService = inject(ScanProgressService);
  private readonly toastService = inject(ToastService);
  private readonly dataManagement = inject(SharePointSiteDataManagementService);
  private readonly piiItemsStorage = inject(SharePointPiiItemsStorageService);
  private readonly uiStateService = inject(SharePointDashboardUiStateService);

  routeStreamEvent(type: StreamEventType, payload?: ConfluenceContentPersonallyIdentifiableInformationScanResult): void {
    this.uiStateService.append(formatEventLog(type, JSON.stringify(payload ?? {})));

    if (type === 'multiStart') {
      this.handleAllSitesScanStart(payload);
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

  private handleAllSitesScanStart(payload?: ConfluenceContentPersonallyIdentifiableInformationScanResult): void {
    if (payload) {
      this.ensureLastScanMetaFromPayload(payload);
    }

    const sites = this.dataManagement.sites();
    if (!Array.isArray(sites) || sites.length === 0) return;

    this.dataManagement.queue.set(sites.map((s) => s.id));

    for (const site of sites) {
      this.dashboardUtils.updateSite(site.id, { status: 'PENDING' });
    }

    if (!this.uiStateService.selectedSiteId()) {
      this.uiStateService.selectedSiteId.set(sites[0].id);
    }
  }

  private handleStreamScanStart(payload: ConfluenceContentPersonallyIdentifiableInformationScanResult): void {
    const siteId = payload.spaceKey;
    if (!siteId) return;

    this.ensureLastScanMetaFromPayload(payload);

    this.uiStateService.activeSiteId.set(siteId);
    if (!this.uiStateService.selectedSiteId()) {
      this.uiStateService.selectedSiteId.set(siteId);
    }

    const currentQueue = this.dataManagement.queue();
    this.dataManagement.queue.set(
      currentQueue.filter((queuedId) => queuedId !== siteId)
    );

    const current = this.scanProgressService.getProgress()[siteId]?.percent;
    const percent = this.extractPercent(payload) ?? current ?? 0;
    const total = (payload as Record<string, unknown>).pagesTotal as number | undefined;
    const prevTotal = this.scanProgressService.getProgress()[siteId]?.total;
    this.updateProgress(siteId, { total: total ?? prevTotal, index: 0, percent });

    this.uiStateService.upsertScanHistory(siteId, 'running');
    this.dashboardUtils.updateSite(siteId, { status: 'RUNNING' });
  }

  private handlePageStart(payload: ConfluenceContentPersonallyIdentifiableInformationScanResult): void {
    const siteId = payload.spaceKey;
    if (!siteId) return;

    const currentProgress = this.scanProgressService.getProgress()[siteId] ?? {};
    const total = (payload as Record<string, unknown>).pagesTotal ?? currentProgress.total;
    const index = (payload as Record<string, unknown>).pageIndex ?? currentProgress.index;

    let percent = this.extractPercent(payload);
    if (percent == null && typeof total === 'number' && typeof index === 'number' && total > 0) {
      percent = Math.round((index / total) * 100);
    }

    this.updateProgress(siteId, { total: total as number, index: index as number, percent });
  }

  private handleItemEvent(payload: ConfluenceContentPersonallyIdentifiableInformationScanResult): void {
    const incomingKey = coerceSpaceKey(payload);
    const looksLikeAttachment = isAttachmentPayload(payload);
    const siteId = incomingKey ?? (looksLikeAttachment ? this.uiStateService.activeSiteId() : null);

    if (!siteId) return;

    const wasAdded = this.piiItemsStorage.addPiiItemToSite(siteId, payload);

    const progressPercent = this.extractPercent(payload);
    if (progressPercent != null && payload.status !== 'COMPLETED') {
      this.updateProgress(siteId, { percent: progressPercent });
    }

    const timestamp = (payload as Record<string, unknown>).ts as string ?? new Date().toISOString();

    if (wasAdded) {
      const currentCounts = this.dashboardUtils.getSiteCounts(siteId);
      const summary = payload.nbOfDetectedPIIBySeverity;
      const deltaHigh = summary?.high ?? summary?.HIGH ?? 0;
      const deltaMedium = summary?.medium ?? summary?.MEDIUM ?? 0;
      const deltaLow = summary?.low ?? summary?.LOW ?? 0;
      const deltaTotal = deltaHigh + deltaMedium + deltaLow;

      this.dashboardUtils.updateSite(siteId, {
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
      this.dashboardUtils.updateSite(siteId, {
        lastScanTs: timestamp,
        status: 'RUNNING'
      });
    }
  }

  private handleStreamError(payload: ConfluenceContentPersonallyIdentifiableInformationScanResult): void {
    const siteId = payload.spaceKey;
    if (!siteId) return;

    const errorMessage = (payload as Record<string, unknown>)?.message as string
      ?? (payload as Record<string, unknown>)?.errorMessage as string
      ?? 'Unknown error';
    const errorType = this.toastService.detectErrorType(errorMessage);

    this.toastService.showScanError({
      scanId: payload.scanId ?? '',
      spaceKey: siteId,
      pageId: payload.pageId == null ? undefined : String(payload.pageId),
      pageTitle: payload.pageTitle,
      attachmentName: payload.attachmentName,
      errorMessage,
      errorType
    });

    this.dashboardUtils.updateSite(siteId, {
      lastScanTs: new Date().toISOString()
    });
  }

  private handleStreamComplete(payload: ConfluenceContentPersonallyIdentifiableInformationScanResult): void {
    const siteId = payload.spaceKey;
    if (!siteId) return;

    this.uiStateService.upsertScanHistory(siteId, 'completed');
    this.updateProgress(siteId, { percent: 100 });

    this.dashboardUtils.updateSite(siteId, {
      status: 'OK',
      lastScanTs: new Date().toISOString()
    });

    if (this.uiStateService.activeSiteId() === siteId) {
      this.uiStateService.activeSiteId.set(null);
    }
  }

  private ensureLastScanMetaFromPayload(payload: ConfluenceContentPersonallyIdentifiableInformationScanResult): void {
    try {
      const scanId = (payload as Record<string, unknown>)?.scanId as string | undefined;
      if (!scanId) return;

      const current = this.dataManagement.lastScanMeta();
      if (current?.scanId !== scanId) {
        const timestamp = (payload as Record<string, unknown>)?.ts as string ?? new Date().toISOString();
        const sitesCount = this.dataManagement.sites().length;
        this.dataManagement.lastScanMeta.set({
          scanId,
          lastUpdated: timestamp,
          spacesCount: sitesCount
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
    siteId: string,
    patch: Partial<{ total: number; index: number; percent: number }>
  ): void {
    this.scanProgressService.updateProgress(siteId, patch);
  }
}
