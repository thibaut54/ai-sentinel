import { computed, inject, Injectable, signal } from '@angular/core';
import { EMPTY, firstValueFrom, Subject, Subscription, timer } from 'rxjs';
import { catchError, switchMap } from 'rxjs/operators';
import { ScanReportingSummaryDto, SentinelleApiService, SpaceSummaryDto } from './sentinelle-api.service';
import { SpacesDashboardUtils } from '../../features/confluence-dashboard/spaces-dashboard.utils';
import { ScanProgressService } from './scan-progress.service';
import { DashboardUiStateService } from '../../features/confluence-dashboard/services/dashboard-ui-state.service';
import { SpaceDataManagementService } from '../../features/confluence-dashboard/services/space-data-management.service';
import { mapBackendStatusToUi } from '../../features/confluence-dashboard/scan-status.utils';

/** Maps backend scan statuses to UI history entries. */
const HISTORY_STATUS_MAP: Record<string, 'running' | 'completed' | 'failed'> = {
  RUNNING: 'running',
  COMPLETED: 'completed',
  FAILED: 'failed'
};

/**
 * Backend-authoritative scan status service.
 * Polls /dashboard/spaces-summary and derives all UI state from the backend response.
 *
 * Button panel states (scanActive, scanPaused, scanIdle) are computed from backend data,
 * not from frontend signals. After each user action (start/pause/resume), forceRefresh()
 * fetches the backend state immediately so buttons update without waiting for the next poll.
 */
@Injectable({ providedIn: 'root' })
export class ScanStatusPollingService {
  private readonly api = inject(SentinelleApiService);
  private readonly spacesUtils = inject(SpacesDashboardUtils);
  private readonly progressService = inject(ScanProgressService);
  private readonly uiStateService = inject(DashboardUiStateService);
  private readonly dataManagement = inject(SpaceDataManagementService);

  private pollingSub?: Subscription;

  /** Emits once when all spaces in the scan are COMPLETED or FAILED. */
  private readonly _scanCompleted = new Subject<void>();
  readonly scanCompleted$ = this._scanCompleted.asObservable();

  // --- Backend-authoritative panel state ---

  /** Whether a scan is actively running (at least one RUNNING space). */
  readonly scanActive = signal(false);

  /** Whether a scan is paused (at least one PAUSED space, none RUNNING). */
  readonly scanPaused = signal(false);

  /** Whether a button action is in progress (spinner). */
  readonly actionPending = signal(false);

  /** Computed: scan is idle (not active, not paused). */
  readonly scanIdle = computed(() => !this.scanActive() && !this.scanPaused());

  /**
   * Starts polling. First poll is immediate, then repeats at intervalMs.
   */
  start(intervalMs: number = 3000): void {
    this.stop();

    this.pollingSub = timer(0, intervalMs).pipe(
      switchMap(() => this.api.getDashboardSpacesSummary().pipe(
        catchError(() => EMPTY)
      ))
    ).subscribe(summary => {
      if (summary) {
        this.applySummary(summary);
      }
    });
  }

  /** Stops polling. */
  stop(): void {
    this.pollingSub?.unsubscribe();
    this.pollingSub = undefined;
  }

  /**
   * Immediately fetches backend state. Used after user actions (pause/resume)
   * so button panel updates instantly without waiting for next poll cycle.
   */
  async forceRefresh(): Promise<void> {
    try {
      const summary = await firstValueFrom(this.api.getDashboardSpacesSummary());
      if (summary) {
        this.applySummary(summary, true);
      }
    } catch {
      // Ignore errors — next poll will catch up
    }
  }

  /**
   * Applies a backend summary to the UI.
   * @param summary The backend summary.
   * @param force When true, always apply statuses (used by forceRefresh after confirmed API calls).
   *              When false (polling), skip applying stale statuses while actionPending to preserve
   *              the optimistic UI (PENDING) set by initializeOptimisticUi().
   */
  private applySummary(summary: ScanReportingSummaryDto, force: boolean = false): void {
    const isNewScan = this.syncLastScanMeta(summary);

    const hasRunning = summary.spaces.some(s => s.status === 'RUNNING');
    const hasPaused = summary.spaces.some(s => s.status === 'PAUSED') && !hasRunning;
    const isScanActive = hasRunning || hasPaused;

    this.scanActive.set(hasRunning);
    this.scanPaused.set(hasPaused);

    // Auto-clear actionPending when backend confirms the new scan is RUNNING
    // or when a new scanId appears (covers the case where scan fails before RUNNING).
    if (this.actionPending() && (hasRunning || isNewScan)) {
      this.actionPending.set(false);
    }

    // When actionPending is true and backend doesn't yet show RUNNING, the summary
    // reflects stale data from a prior scan. Skip applying space statuses to preserve
    // the optimistic UI (PENDING) set by initializeOptimisticUi().
    // forceRefresh() passes force=true to bypass this guard.
    if (!force && this.actionPending() && !hasRunning) {
      return;
    }

    const { completedOrFailedCount, runningSpaceKey } = this.applySpaceStatuses(summary.spaces, isScanActive);
    this.markUnreportedSpacesAsPending(summary.spaces, isScanActive);
    this.uiStateService.activeSpaceKey.set(runningSpaceKey);
    this.detectScanCompletion(summary.spaces.length, completedOrFailedCount);
  }

  /** Syncs lastScanMeta when backend reports a different scanId. Returns true if a new scan was detected. */
  private syncLastScanMeta(summary: ScanReportingSummaryDto): boolean {
    if (!summary.scanId) {
      return false;
    }
    if (this.dataManagement.lastScanMeta()?.scanId === summary.scanId) {
      return false;
    }
    this.dataManagement.lastScanMeta.set({
      scanId: summary.scanId,
      lastUpdated: summary.lastUpdated,
      spacesCount: summary.spacesCount
    });
    return true;
  }

  /** Applies status, progress, and history for each space in the summary. */
  private applySpaceStatuses(spaces: SpaceSummaryDto[], isScanActive: boolean): { completedOrFailedCount: number; runningSpaceKey: string | null } {
    let completedOrFailedCount = 0;
    let runningSpaceKey: string | null = null;

    for (const space of spaces) {
      this.applySpaceUiState(space, isScanActive);
      this.trackScanHistory(space);

      if (space.status === 'RUNNING') {
        runningSpaceKey = space.spaceKey;
      }
      if (space.status === 'COMPLETED' || space.status === 'FAILED') {
        completedOrFailedCount++;
      }
    }

    return { completedOrFailedCount, runningSpaceKey };
  }

  /** Updates a single space's UI state (status badge, timestamp, counts, progress). */
  private applySpaceUiState(space: SpaceSummaryDto, isScanActive: boolean): void {
    let uiStatus = mapBackendStatusToUi(space.status);
    if (isScanActive && uiStatus === 'NOT_STARTED') {
      uiStatus = 'PENDING';
    }

    this.spacesUtils.updateSpace(space.spaceKey, {
      status: uiStatus,
      lastScanTs: space.lastEventTs,
      counts: space.severityCounts ?? { high: 0, medium: 0, low: 0, total: 0 }
    });

    const percent = space.status === 'COMPLETED' ? 100 : (space.progressPercentage ?? undefined);
    if (percent != null) {
      this.progressService.updateProgress(space.spaceKey, { percent });
    }
  }

  /** Tracks scan history for spaces with a reportable status (RUNNING, COMPLETED, FAILED). */
  private trackScanHistory(space: SpaceSummaryDto): void {
    const historyStatus = HISTORY_STATUS_MAP[space.status];
    if (historyStatus) {
      this.uiStateService.upsertScanHistory(space.spaceKey, historyStatus);
    }
  }

  /** Marks spaces not yet in the summary as PENDING during an active scan. */
  private markUnreportedSpacesAsPending(summarySpaces: SpaceSummaryDto[], isScanActive: boolean): void {
    if (!isScanActive) {
      return;
    }
    const summaryKeys = new Set(summarySpaces.map(s => s.spaceKey));
    for (const space of this.dataManagement.spaces()) {
      if (!summaryKeys.has(space.key)) {
        this.spacesUtils.updateSpace(space.key, { status: 'PENDING' });
      }
    }
  }

  /** Signals scan completion when all expected spaces are COMPLETED or FAILED. */
  private detectScanCompletion(reportedCount: number, completedOrFailedCount: number): void {
    const expectedSpaces = this.dataManagement.currentScanSpaceKeys()?.length
      ?? this.dataManagement.spaces().length;

    if (reportedCount >= expectedSpaces && completedOrFailedCount === reportedCount) {
      this._scanCompleted.next();
    }
  }
}
