export type UiSpaceStatus = 'FAILED' | 'RUNNING' | 'OK' | 'PENDING' | 'NOT_STARTED' | 'INTERRUPTED' | 'PAUSED';

/**
 * Maps backend scan status to dashboard UI status.
 * Single source of truth — used by both ScanStatusPollingService and SpaceDataManagementService.
 *
 * Mapping:
 *  COMPLETED   -> OK
 *  NOT_STARTED -> NOT_STARTED (never scanned — "Non démarré")
 *  PENDING     -> PENDING     (waiting in queue during active scan — "En attente")
 *  Others      -> pass-through (RUNNING, FAILED, PAUSED)
 */
export function mapBackendStatusToUi(backendStatus: string): UiSpaceStatus {
  if (backendStatus === 'COMPLETED') return 'OK';
  return backendStatus as UiSpaceStatus;
}
