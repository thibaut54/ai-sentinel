import { computed, inject, Injectable, signal } from '@angular/core';
import { Subscription } from 'rxjs';
import { ConfirmationService } from 'primeng/api';
import { TranslocoService } from '@jsverse/transloco';
import { SentinelleApiService } from '../../../core/services/sentinelle-api.service';
import { ToastService } from '../../../core/services/toast.service';
import { ScanProgressService } from '../../../core/services/scan-progress.service';
import { SharePointSitesDashboardUtils } from '../sharepoint-sites-dashboard.utils';
import { SharePointPiiItemsStorageService } from './sharepoint-pii-items-storage.service';
import { SharePointDashboardUiStateService } from './sharepoint-dashboard-ui-state.service';
import { SharePointSiteDataManagementService } from './sharepoint-site-data-management.service';
import { SharePointSseEventHandlerService } from './sharepoint-sse-event-handler.service';
import { StreamEventType } from '../../confluence-dashboard/spaces-dashboard-stream.utils';

@Injectable({
  providedIn: 'root'
})
export class SharePointScanControlService {
  private readonly sentinelleApiService = inject(SentinelleApiService);
  private readonly confirmationService = inject(ConfirmationService);
  private readonly translocoService = inject(TranslocoService);
  private readonly toastService = inject(ToastService);
  private readonly scanProgressService = inject(ScanProgressService);
  private readonly dashboardUtils = inject(SharePointSitesDashboardUtils);
  private readonly piiItemsStorage = inject(SharePointPiiItemsStorageService);
  private readonly uiStateService = inject(SharePointDashboardUiStateService);
  private readonly dataManagement = inject(SharePointSiteDataManagementService);
  private readonly sseEventHandler = inject(SharePointSseEventHandlerService);

  private sseSubscription?: Subscription;

  readonly isStreaming = signal(false);
  readonly isResuming = signal<boolean>(false);

  readonly canStartScan = computed(() =>
    !this.isStreaming() &&
    this.dataManagement.canStartScan()
  );

  readonly canResumeScan = computed(() =>
    !this.isStreaming() &&
    !this.dataManagement.isSitesLoading() &&
    !!this.dataManagement.lastScanMeta() &&
    !this.isResuming()
  );

  startAll(): void {
    if (this.isStreaming()) return;

    this.confirmationService.confirm({
      header: this.translocoService.translate('confirmations.globalScan.header'),
      message: this.translocoService.translate('confirmations.globalScan.message'),
      icon: 'pi pi-exclamation-triangle',
      acceptLabel: this.translocoService.translate('confirmations.globalScan.acceptLabel'),
      rejectLabel: this.translocoService.translate('confirmations.globalScan.rejectLabel'),
      acceptButtonStyleClass: 'p-button-info',
      rejectButtonStyleClass: 'p-button-secondary',
      accept: () => this.executeStartAll(),
      reject: () => {
        this.uiStateService.append(
          this.translocoService.translate('dashboard.logs.scanCancelled')
        );
      }
    });
  }

  startSelected(siteIds: string[]): void {
    if (this.isStreaming() || siteIds.length === 0) return;

    this.confirmationService.confirm({
      header: this.translocoService.translate('confirmations.selectedScan.header'),
      message: this.translocoService.translate('confirmations.selectedScan.message', { count: siteIds.length }),
      icon: 'pi pi-exclamation-triangle',
      acceptLabel: this.translocoService.translate('confirmations.selectedScan.acceptLabel'),
      rejectLabel: this.translocoService.translate('confirmations.selectedScan.rejectLabel'),
      acceptButtonStyleClass: 'p-button-info',
      rejectButtonStyleClass: 'p-button-secondary',
      accept: () => this.executeStartSelected(siteIds),
      reject: () => {
        this.uiStateService.append(
          this.translocoService.translate('dashboard.logs.scanCancelled')
        );
      }
    });
  }

  private executeStartSelected(siteIds: string[]): void {
    this.disconnectSse();
    this.resetDashboardForNewScan(siteIds);
    this.isStreaming.set(true);

    this.sseSubscription = this.sentinelleApiService.startSelectedSharePointSitesStream(siteIds).subscribe({
      next: (ev) => {
        this.sseEventHandler.routeStreamEvent(ev.type as StreamEventType, ev.data);
      },
      complete: () => {
        this.isStreaming.set(false);
      },
      error: (err) => {
        this.uiStateService.append(
          this.translocoService.translate('dashboard.logs.sseError', {
            error: err?.message ?? err
          })
        );
        this.isStreaming.set(false);
      }
    });
  }

  private executeStartAll(): void {
    this.disconnectSse();
    this.resetDashboardForNewScan();

    this.isStreaming.set(true);

    this.sseSubscription = this.sentinelleApiService.startAllSharePointSitesStream().subscribe({
      next: (ev) => {
        this.sseEventHandler.routeStreamEvent(ev.type as StreamEventType, ev.data);
      },
      complete: () => {
        this.isStreaming.set(false);
      },
      error: (err) => {
        this.uiStateService.append(
          this.translocoService.translate('dashboard.logs.sseError', {
            error: err?.message ?? err
          })
        );
        this.isStreaming.set(false);
      }
    });
  }

  private resetDashboardForNewScan(siteIds?: string[]): void {
    if (siteIds && siteIds.length > 0) {
      for (const id of siteIds) {
        this.piiItemsStorage.clearItemsForSite(id);
        this.scanProgressService.resetProgress(id);
        this.dashboardUtils.updateSite(id, {
          status: 'PENDING',
          lastScanTs: undefined,
          counts: { total: 0, high: 0, medium: 0, low: 0 }
        });
      }
    } else {
      this.piiItemsStorage.clearAllItems();
      this.scanProgressService.resetAllProgress();
      this.uiStateService.clearHistory();
      try {
        this.dashboardUtils.setSites(this.dataManagement.sites());
      } catch {
        // no-op
      }
    }

    this.uiStateService.collapseAllRows();
    this.uiStateService.selectSite(null);
    this.uiStateService.activeSiteId.set(null);
    this.toastService.clearScanErrors();
  }

  pauseScan(): void {
    const scanId = this.dataManagement.lastScanMeta()?.scanId;
    this.disconnectSse();

    if (scanId) {
      this.sentinelleApiService.pauseScan(scanId).subscribe({
        next: () => {
          this.uiStateService.append(
            this.translocoService.translate('dashboard.logs.scanPaused', { scanId })
          );
        },
        error: (err) => {
          this.uiStateService.append(
            this.translocoService.translate('dashboard.logs.pauseError', {
              error: err?.message ?? err
            })
          );
        }
      });
    }
  }

  private disconnectSse(): void {
    if (this.sseSubscription) {
      this.sseSubscription.unsubscribe();
      this.sseSubscription = undefined;
    }
    this.isStreaming.set(false);
  }

  checkAndReconnectToRunningScan(): void {
    if (this.isStreaming()) return;

    const meta = this.dataManagement.lastScanMeta();
    const statuses = this.dataManagement.lastSiteStatuses();

    if (!meta?.scanId) return;

    const hasRunningScan = statuses.some(s => s.status === 'RUNNING');
    const hasPausedScan = statuses.some(s => s.status === 'PAUSED');

    if (!hasRunningScan || hasPausedScan) return;

    this.uiStateService.append(
      this.translocoService.translate('dashboard.logs.autoReconnect', {
        scanId: meta.scanId
      })
    );

    this.isStreaming.set(true);
    this.sseSubscription = this.sentinelleApiService.startAllSharePointSitesStream(meta.scanId).subscribe({
      next: (ev) => {
        this.sseEventHandler.routeStreamEvent(ev.type as StreamEventType, ev.data);
      },
      error: (err) => {
        this.uiStateService.append(
          this.translocoService.translate('dashboard.logs.sseError', {
            error: err?.message ?? err
          })
        );
        this.isStreaming.set(false);
      }
    });

    this.dataManagement.loadLastSpaceStatuses(true, true).subscribe();
  }

  reset(): void {
    this.disconnectSse();
    this.isResuming.set(false);
    this.uiStateService.activeSiteId.set(null);
  }
}
