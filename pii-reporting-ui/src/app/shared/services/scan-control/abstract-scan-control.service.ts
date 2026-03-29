import { computed, inject, signal } from '@angular/core';
import { Subscription } from 'rxjs';
import { ConfirmationService } from 'primeng/api';
import { TranslocoService } from '@jsverse/transloco';
import { ToastService } from '../../../core/services/toast.service';
import { ScanProgressService } from '../../../core/services/scan-progress.service';
import {
    ScanApiAdapter,
    ScanDashboardUtils,
    ScanDataManagement,
    ScanPiiItemsStorage,
    ScanSseEventHandler,
    ScanUiState
} from './scan-control.interfaces';

export abstract class AbstractScanControlService {
  protected readonly confirmationService = inject(ConfirmationService);
  protected readonly translocoService = inject(TranslocoService);
  protected readonly toastService = inject(ToastService);
  protected readonly scanProgressService = inject(ScanProgressService);

  protected abstract readonly apiAdapter: ScanApiAdapter;
  protected abstract readonly piiItemsStorage: ScanPiiItemsStorage;
  protected abstract readonly dashboardUtils: ScanDashboardUtils;
  protected abstract readonly uiState: ScanUiState;
  protected abstract readonly dataManagement: ScanDataManagement;
  protected abstract readonly sseEventHandler: ScanSseEventHandler;

  /** Whether to clear lastEntityStatuses during full reset. Only Confluence uses this. */
  protected readonly clearStatusesOnReset: boolean = false;

  private sseSubscription?: Subscription;

  readonly isStreaming = signal(false);
  readonly isResuming = signal<boolean>(false);

  readonly canStartScan = computed(() =>
    !this.isStreaming() &&
    this.dataManagement.canStartScan()
  );

  readonly canResumeScan = computed(() =>
    !this.isStreaming() &&
    !this.dataManagement.isEntitiesLoading() &&
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
        this.uiState.append(
          this.translocoService.translate('dashboard.logs.scanCancelled')
        );
      }
    });
  }

  startSelected(entityKeys: string[]): void {
    if (this.isStreaming() || entityKeys.length === 0) return;

    this.confirmationService.confirm({
      header: this.translocoService.translate('confirmations.selectedScan.header'),
      message: this.translocoService.translate('confirmations.selectedScan.message', { count: entityKeys.length }),
      icon: 'pi pi-exclamation-triangle',
      acceptLabel: this.translocoService.translate('confirmations.selectedScan.acceptLabel'),
      rejectLabel: this.translocoService.translate('confirmations.selectedScan.rejectLabel'),
      acceptButtonStyleClass: 'p-button-info',
      rejectButtonStyleClass: 'p-button-secondary',
      accept: () => this.executeStartSelected(entityKeys),
      reject: () => {
        this.uiState.append(
          this.translocoService.translate('dashboard.logs.scanCancelled')
        );
      }
    });
  }

  pauseScan(): void {
    const scanId = this.dataManagement.lastScanMeta()?.scanId;
    this.disconnectSse();

    if (scanId) {
      this.apiAdapter.pauseScan(scanId).subscribe({
        next: () => {
          this.uiState.append(
            this.translocoService.translate('dashboard.logs.scanPaused', { scanId })
          );
          this.dataManagement.loadLastEntityStatuses(false, false).subscribe();
          this.dataManagement.loadLastScan().subscribe();
        },
        error: (err) => {
          this.uiState.append(
            this.translocoService.translate('dashboard.logs.pauseError', {
              error: err?.message ?? err
            })
          );
          this.dataManagement.loadLastEntityStatuses(false, false).subscribe();
          this.dataManagement.loadLastScan().subscribe();
        }
      });
    } else {
      this.dataManagement.loadLastEntityStatuses(false, false).subscribe();
      this.dataManagement.loadLastScan().subscribe();
    }
  }

  resumeLastScan(): void {
    const meta = this.dataManagement.lastScanMeta();
    if (!meta || this.isStreaming() || this.isResuming()) {
      return;
    }

    this.isResuming.set(true);
    this.uiState.append(
      this.translocoService.translate('dashboard.logs.resumeRequest', {
        scanId: meta.scanId
      })
    );

    this.apiAdapter.resumeScan(meta.scanId).subscribe({
      next: () => {
        this.isResuming.set(false);

        this.uiState.append(
          this.translocoService.translate('dashboard.logs.resumeAccepted')
        );

        this.startSseStream(meta.scanId);
        this.dataManagement.loadLastEntityStatuses(true, true).subscribe();
      },
      error: (e) => {
        this.isResuming.set(false);
        this.uiState.append(
          this.translocoService.translate('dashboard.logs.resumeError', {
            error: e?.message ?? e
          })
        );
      }
    });
  }

  checkAndReconnectToRunningScan(): void {
    if (this.isStreaming()) return;

    const meta = this.dataManagement.lastScanMeta();
    const statuses = this.dataManagement.lastEntityStatuses();

    if (!meta?.scanId) return;

    const hasRunningScan = statuses.some(s => s.status === 'RUNNING');
    const hasPausedScan = statuses.some(s => s.status === 'PAUSED');

    if (!hasRunningScan || hasPausedScan) return;

    this.uiState.append(
      this.translocoService.translate('dashboard.logs.autoReconnect', {
        scanId: meta.scanId
      })
    );

    this.startSseStream(meta.scanId);
    this.dataManagement.loadLastEntityStatuses(true, true).subscribe();
  }

  reset(): void {
    this.disconnectSse();
    this.isResuming.set(false);
    this.uiState.activeEntityId.set(null);
  }

  private executeStartSelected(entityKeys: string[]): void {
    this.disconnectSse();
    this.resetDashboardForNewScan(entityKeys);
    this.isStreaming.set(true);

    this.sseSubscription = this.apiAdapter.startSelectedStream(entityKeys).subscribe({
      next: (ev) => {
        this.sseEventHandler.routeStreamEvent(ev.type, ev.data);
      },
      complete: () => {
        this.isStreaming.set(false);
      },
      error: (err) => {
        this.uiState.append(
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
    this.startSseStream();
  }

  private startSseStream(scanId?: string): void {
    this.isStreaming.set(true);

    this.sseSubscription = this.apiAdapter.startAllStream(scanId).subscribe({
      next: (ev) => {
        this.sseEventHandler.routeStreamEvent(ev.type, ev.data);
      },
      complete: () => {
        this.isStreaming.set(false);
      },
      error: (err) => {
        this.uiState.append(
          this.translocoService.translate('dashboard.logs.sseError', {
            error: err?.message ?? err
          })
        );
        this.isStreaming.set(false);
      }
    });
  }

  private resetDashboardForNewScan(entityKeys?: string[]): void {
    if (entityKeys && entityKeys.length > 0) {
      for (const key of entityKeys) {
        this.piiItemsStorage.clearItemsForEntity(key);
        this.scanProgressService.resetProgress(key);
        this.dashboardUtils.updateEntity(key, {
          status: 'PENDING',
          lastScanTs: undefined,
          counts: { total: 0, high: 0, medium: 0, low: 0 }
        });
      }
    } else {
      this.piiItemsStorage.clearAllItems();
      const statuses = this.dataManagement.lastEntityStatuses();
      for (const status of statuses) {
        this.scanProgressService.resetProgress(status.spaceKey);
      }
      this.uiState.clearHistory();
      if (this.clearStatusesOnReset) {
        this.dataManagement.lastEntityStatuses.set([]);
      }
      try {
        this.dashboardUtils.setEntities(this.dataManagement.entities());
      } catch {
        // no-op
      }
    }

    this.uiState.collapseAllRows();
    this.uiState.selectEntity(null);
    this.uiState.activeEntityId.set(null);
    this.toastService.clearScanErrors();
  }

  private disconnectSse(): void {
    if (this.sseSubscription) {
      this.sseSubscription.unsubscribe();
      this.sseSubscription = undefined;
    }
    this.isStreaming.set(false);
  }
}
