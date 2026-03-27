import { computed, inject, Injectable, signal } from '@angular/core';
import { Observable, Subscription } from 'rxjs';
import { take } from 'rxjs/operators';
import { ConfirmationService } from 'primeng/api';
import { TranslocoService } from '@jsverse/transloco';
import { SentinelleApiService } from '../../../core/services/sentinelle-api.service';
import { ScanStatusPollingService } from '../../../core/services/scan-status-polling.service';
import { ToastService } from '../../../core/services/toast.service';
import { ScanProgressService } from '../../../core/services/scan-progress.service';
import { SpacesDashboardUtils } from '../spaces-dashboard.utils';
import { PiiItemsStorageService } from './pii-items-storage.service';
import { DashboardUiStateService } from './dashboard-ui-state.service';
import { SpaceDataManagementService } from './space-data-management.service';
import { SseEventHandlerService } from './sse-event-handler.service';
import { StreamEventType } from '../spaces-dashboard-stream.utils';
import { StreamEvent } from '../../../core/models/stream-event';

/**
 * Service responsible for controlling scan lifecycle (start, stop, resume).
 *
 * Business purpose:
 * - Orchestrates multi-space scan initiation with user confirmation
 * - Manages SSE stream subscription for real-time PII item updates
 * - Manages polling for backend-authoritative scan statuses
 * - Handles scan pause/stop functionality
 * - Enables scan resume from last checkpoint
 * - Coordinates state reset for new scans
 *
 * Architecture:
 * - Statuses/progress: polled via ScanStatusPollingService (single source of truth)
 * - PII items: streamed via SSE (live cards)
 * - Completion detection: via scanCompleted$ from polling (not SSE multiComplete)
 */
@Injectable({
  providedIn: 'root'
})
export class ScanControlService {
  private readonly sentinelleApiService = inject(SentinelleApiService);
  private readonly statusPolling = inject(ScanStatusPollingService);
  private readonly confirmationService = inject(ConfirmationService);
  private readonly translocoService = inject(TranslocoService);
  private readonly toastService = inject(ToastService);
  private readonly scanProgressService = inject(ScanProgressService);
  private readonly spacesDashboardUtils = inject(SpacesDashboardUtils);
  private readonly piiItemsStorage = inject(PiiItemsStorageService);
  private readonly uiStateService = inject(DashboardUiStateService);
  private readonly dataManagement = inject(SpaceDataManagementService);
  private readonly sseEventHandler = inject(SseEventHandlerService);

  private sseSubscription?: Subscription;
  private completionSub?: Subscription;

  // Streaming state (SSE connected for live items)
  readonly isStreaming = signal(false);

  // Backend-authoritative button states (derived from polling)
  readonly scanActive = this.statusPolling.scanActive;
  readonly scanPaused = this.statusPolling.scanPaused;
  readonly actionPending = this.statusPolling.actionPending;

  // Computed: whether scan can be started (also available when paused — starts fresh scan)
  readonly canStartScan = computed(() =>
    !this.actionPending() &&
    !this.scanActive() &&
    this.dataManagement.canStartScan()
  );

  // Computed: whether scan can be paused
  readonly canPauseScan = computed(() =>
    !this.actionPending() &&
    this.scanActive()
  );

  // Computed: whether scan can be resumed
  readonly canResumeScan = computed(() =>
    !this.actionPending() &&
    this.scanPaused() &&
    !!this.dataManagement.lastScanMeta()
  );

  /**
   * Initiates a global scan of all spaces with user confirmation.
   * Business purpose: ensures user explicitly approves full scan before data purge.
   */
  startAll(): void {
    if (this.scanActive() || this.actionPending()) {
      return;
    }

    this.confirmationService.confirm({
      header: this.translocoService.translate('confirmations.globalScan.header'),
      message: this.translocoService.translate('confirmations.globalScan.message'),
      icon: 'pi pi-exclamation-triangle',
      acceptLabel: this.translocoService.translate('confirmations.globalScan.acceptLabel'),
      rejectLabel: this.translocoService.translate('confirmations.globalScan.rejectLabel'),
      acceptButtonStyleClass: 'p-button-info',
      rejectButtonStyleClass: 'p-button-secondary',
      accept: () => {
        this.executeStartAll();
      },
      reject: () => {
        this.uiStateService.append(
          this.translocoService.translate('dashboard.logs.scanCancelled')
        );
      }
    });
  }

  /**
   * Initiates a scan for selected spaces with user confirmation.
   */
  startSelected(spaceKeys: string[]): void {
    if (this.scanActive() || this.actionPending() || spaceKeys.length === 0) {
      return;
    }

    this.confirmationService.confirm({
      header: this.translocoService.translate('confirmations.selectedScan.header'),
      message: this.translocoService.translate('confirmations.selectedScan.message', { count: spaceKeys.length }),
      icon: 'pi pi-exclamation-triangle',
      acceptLabel: this.translocoService.translate('confirmations.selectedScan.acceptLabel'),
      rejectLabel: this.translocoService.translate('confirmations.selectedScan.rejectLabel'),
      acceptButtonStyleClass: 'p-button-info',
      rejectButtonStyleClass: 'p-button-secondary',
      accept: () => {
        this.executeStartSelected(spaceKeys);
      },
      reject: () => {
        this.uiStateService.append(
          this.translocoService.translate('dashboard.logs.scanCancelled')
        );
      }
    });
  }

  private executeStartSelected(spaceKeys: string[]): void {
    this.statusPolling.actionPending.set(true);
    this.disconnectSse();
    this.dataManagement.currentScanSpaceKeys.set(spaceKeys);
    this.resetDashboardForNewScan(spaceKeys);

    this.isStreaming.set(true);
    this.initializeOptimisticUi(spaceKeys);
    // Subscribe SSE first to trigger the scan on the backend before polling starts
    this.subscribeSse(this.sentinelleApiService.startSelectedSpacesStream(spaceKeys));
    this.startPollingWithCompletionDetection();
    // actionPending stays true until polling detects RUNNING (auto-cleared in applySummary)
  }

  private executeStartAll(): void {
    this.statusPolling.actionPending.set(true);
    this.disconnectSse();
    this.dataManagement.currentScanSpaceKeys.set(null);

    this.resetDashboardForNewScan();

    this.uiStateService.append(
      this.translocoService.translate('dashboard.logs.purging')
    );

    this.sentinelleApiService.purgeAllScans().subscribe({
      next: () => {
        this.uiStateService.append(
          this.translocoService.translate('dashboard.logs.purgeOk')
        );
        this.isStreaming.set(true);
        this.initializeOptimisticUi();
        // Subscribe SSE first to trigger the scan on the backend before polling starts
        this.subscribeSse(this.sentinelleApiService.startAllSpacesStream());
        this.startPollingWithCompletionDetection();
        // actionPending stays true until polling detects RUNNING (auto-cleared in applySummary)
      },
      error: (err) => {
        this.uiStateService.append(
          this.translocoService.translate('dashboard.logs.purgeError', {
            error: err?.message ?? err
          })
        );
        this.isStreaming.set(false);
        this.statusPolling.actionPending.set(false);
      }
    });
  }

  /**
   * Starts polling and subscribes to completion signal.
   * When all spaces are COMPLETED/FAILED, triggers onScanComplete().
   */
  private startPollingWithCompletionDetection(): void {
    this.statusPolling.start(3000);

    this.completionSub?.unsubscribe();
    this.completionSub = this.statusPolling.scanCompleted$.pipe(
      take(1)
    ).subscribe(() => {
      this.onScanComplete();
    });
  }

  /**
   * Optimistic UI initialization at scan start.
   * Sets spaces to PENDING immediately so the user gets instant feedback.
   * (The first poll will update with actual statuses within ~0ms)
   */
  private initializeOptimisticUi(spaceKeys?: string[]): void {
    const spaces = this.dataManagement.spaces();
    const scopeKeys = spaceKeys
      ? new Set(spaceKeys.map(k => k.trim().toLowerCase()))
      : null;

    const queueKeys = scopeKeys
      ? spaces.filter(s => scopeKeys.has(s.key.trim().toLowerCase())).map(s => s.key)
      : spaces.map(s => s.key);

    this.dataManagement.queue.set(queueKeys);

    for (const space of spaces) {
      if (!scopeKeys || scopeKeys.has(space.key.trim().toLowerCase())) {
        this.spacesDashboardUtils.updateSpace(space.key, { status: 'PENDING' });
      }
    }

    if (!this.uiStateService.selectedSpaceKey() && queueKeys.length > 0) {
      this.uiStateService.selectedSpaceKey.set(queueKeys[0]);
    }
  }

  /**
   * Resets dashboard state and UI decoration when starting a new scan.
   * Business rule: do not apply this on resume, as resume should continue displaying existing results.
   */
  private resetDashboardForNewScan(spaceKeys?: string[]): void {
    if (spaceKeys && spaceKeys.length > 0) {
      for (const key of spaceKeys) {
        this.piiItemsStorage.clearItemsForSpace(key);
        this.scanProgressService.resetProgress(key);
        this.spacesDashboardUtils.updateSpace(key, {
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
        this.spacesDashboardUtils.setSpaces(this.dataManagement.spaces());
      } catch {
        // no-op
      }
    }

    this.uiStateService.collapseAllRows();
    this.uiStateService.selectSpace(null);
    this.uiStateService.activeSpaceKey.set(null);

    this.toastService.clearScanErrors();
  }

  /**
   * Pauses the current scan. Shows spinner, calls backend, then fetches fresh state.
   */
  pauseScan(): void {
    const scanId = this.dataManagement.lastScanMeta()?.scanId;
    this.statusPolling.actionPending.set(true);
    this.statusPolling.stop();
    this.completionSub?.unsubscribe();
    this.disconnectSse();

    if (scanId) {
      this.sentinelleApiService.pauseScan(scanId).subscribe({
        next: () => {
          this.uiStateService.append(
            this.translocoService.translate('dashboard.logs.scanPaused', { scanId })
          );
          this.refreshAfterAction();
        },
        error: (err) => {
          this.uiStateService.append(
            this.translocoService.translate('dashboard.logs.pauseError', {
              error: err?.message ?? err
            })
          );
          this.refreshAfterAction();
        }
      });
    } else {
      this.refreshAfterAction();
    }
  }

  /**
   * After an action (pause/resume), immediately fetch backend state to update button panel.
   */
  private refreshAfterAction(): void {
    this.statusPolling.forceRefresh().then(() => {
      this.statusPolling.actionPending.set(false);
    });
  }

  private disconnectSse(): void {
    if (this.sseSubscription) {
      this.sseSubscription.unsubscribe();
      this.sseSubscription = undefined;
    }
    this.isStreaming.set(false);
  }

  /**
   * Subscribes to an SSE stream for live PII items.
   * SSE error is non-fatal: items won't appear live but statuses still update via polling.
   */
  private subscribeSse(stream$: Observable<StreamEvent>): void {
    this.sseSubscription = stream$.subscribe({
      next: (ev) => {
        this.sseEventHandler.routeStreamEvent(ev.type as StreamEventType, ev.data);
      },
      error: (err) => {
        // SSE error is non-fatal: items won't appear live but statuses still update via polling
        this.uiStateService.append(
          this.translocoService.translate('dashboard.logs.sseError', {
            error: err?.message ?? err
          })
        );
        this.sseSubscription?.unsubscribe();
        this.sseSubscription = undefined;
      }
    });
  }

  /**
   * Handles scan completion: stops polling, disconnects SSE and reloads final statuses.
   */
  private onScanComplete(): void {
    this.statusPolling.stop();
    this.completionSub?.unsubscribe();
    this.disconnectSse();
    this.dataManagement.currentScanSpaceKeys.set(null);
    // Final reload to ensure severity counts are exact
    this.dataManagement.loadLastSpaceStatuses().subscribe();
    this.dataManagement.loadLastScan().subscribe();
  }

  /**
   * Resumes a paused scan. Shows spinner, calls backend, fetches fresh state, reconnects SSE.
   */
  resumeLastScan(): void {
    const meta = this.dataManagement.lastScanMeta();
    if (!meta || this.actionPending()) return;

    this.statusPolling.actionPending.set(true);
    this.uiStateService.append(
      this.translocoService.translate('dashboard.logs.resumeRequest', { scanId: meta.scanId })
    );

    this.sentinelleApiService.resumeScan(meta.scanId).subscribe({
      next: () => {
        this.uiStateService.append(
          this.translocoService.translate('dashboard.logs.resumeAccepted')
        );
        this.isStreaming.set(true);
        this.startPollingWithCompletionDetection();
        this.subscribeSse(this.sentinelleApiService.startAllSpacesStream(meta.scanId));
        this.dataManagement.loadLastItems().subscribe();
        // forceRefresh clears actionPending via applySummary setting scanActive=true
        this.statusPolling.forceRefresh().then(() => {
          this.statusPolling.actionPending.set(false);
        });
      },
      error: (e) => {
        this.statusPolling.actionPending.set(false);
        this.uiStateService.append(
          this.translocoService.translate('dashboard.logs.resumeError', { error: e?.message ?? e })
        );
      }
    });
  }

  /**
   * Initializes button panel state and reconnects to running scan if needed.
   * Called during dashboard initialization after loading statuses.
   *
   * Always syncs button panel signals (scanActive, scanPaused) from backend.
   * If a scan is actively RUNNING, also starts polling + SSE reconnection.
   */
  reconnectIfScanRunning(): void {
    // Always sync button panel state from backend — regardless of scan state
    this.statusPolling.forceRefresh();

    if (this.isStreaming()) return;

    const meta = this.dataManagement.lastScanMeta();
    const statuses = this.dataManagement.lastSpaceStatuses();

    if (!meta?.scanId) return;

    const hasRunningScan = statuses.some(s => s.status === 'RUNNING');
    const hasPausedScan = statuses.some(s => s.status === 'PAUSED');

    if (!hasRunningScan || hasPausedScan) return;

    this.uiStateService.append(
      this.translocoService.translate('dashboard.logs.autoReconnect', { scanId: meta.scanId })
    );

    this.isStreaming.set(true);
    this.startPollingWithCompletionDetection();
    this.subscribeSse(this.sentinelleApiService.startAllSpacesStream(meta.scanId));
  }

  /**
   * Resets all scan control state to initial values.
   */
  reset(): void {
    this.statusPolling.stop();
    this.completionSub?.unsubscribe();
    this.disconnectSse();
    this.statusPolling.actionPending.set(false);
    this.uiStateService.activeSpaceKey.set(null);
  }
}
