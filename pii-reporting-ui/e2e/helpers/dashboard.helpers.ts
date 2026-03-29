import { expect, Page } from '@playwright/test';
import type { DashboardTestIds } from '../../src/app/features/test-ids.constants';
import { DIALOG_SELECTORS } from './dialog-selectors';

/**
 * Navigate to a specific dashboard tab.
 */
export async function navigateToDashboard(page: Page, source: 'confluence' | 'jira' | 'sharepoint'): Promise<void> {
  await page.goto(`/${source}`);
}

/**
 * Wait for the dashboard table to be loaded with data rows.
 */
export async function waitForTableLoaded(page: Page, testIds: DashboardTestIds): Promise<void> {
  const table = page.getByTestId(testIds.table);
  await expect(table).toBeVisible({ timeout: 15_000 });
  const rows = page.getByTestId(testIds.itemRow);
  await expect(rows.first()).toBeVisible({ timeout: 15_000 });
}

/**
 * Start a scan by clicking start, accepting confirmation dialog, and waiting for scan to begin.
 */
export async function startScanAndConfirm(page: Page, testIds: DashboardTestIds): Promise<void> {
  const startButton = page.getByTestId(testIds.buttons.startScan);
  await expect(startButton).toBeEnabled({ timeout: 15_000 });
  await startButton.click();

  // Accept confirmation dialog
  const confirmDialog = page.locator(DIALOG_SELECTORS.confirmDialog.container);
  await expect(confirmDialog).toBeVisible({ timeout: 5_000 });
  const acceptButton = confirmDialog.locator(DIALOG_SELECTORS.confirmDialog.acceptButton);
  await acceptButton.click();
  await expect(confirmDialog).not.toBeVisible({ timeout: 5_000 });
}

/**
 * Wait for the scan to actually start (pause button becomes enabled).
 */
export async function waitForScanRunning(page: Page, testIds: DashboardTestIds): Promise<void> {
  const pauseButton = page.getByTestId(testIds.buttons.pauseScan);
  await expect(pauseButton).toBeEnabled({ timeout: 20_000 });
}

/**
 * Pause a running scan and verify the state transition.
 */
export async function pauseScan(page: Page, testIds: DashboardTestIds): Promise<void> {
  const pauseButton = page.getByTestId(testIds.buttons.pauseScan);
  await pauseButton.click();
  await expect(pauseButton).toBeDisabled({ timeout: 10_000 });
}

/**
 * Resume a paused scan and verify the state transition.
 */
export async function resumeScan(page: Page, testIds: DashboardTestIds): Promise<void> {
  const resumeButton = page.getByTestId(testIds.buttons.resumeScan);
  await expect(resumeButton).toBeEnabled({ timeout: 5_000 });
  await resumeButton.click();
}

/**
 * Verify the scan status badge reflects the expected streaming state.
 */
export async function verifyScanStatusBadge(page: Page, testIds: DashboardTestIds, expectActive: boolean): Promise<void> {
  const badge = page.getByTestId(testIds.scanStatusBadge);
  await expect(badge).toBeVisible();
  if (expectActive) {
    await expect(badge).toHaveClass(/scanning/);
  } else {
    await expect(badge).toHaveClass(/inactive/);
  }
}

/**
 * Expand the first data row in the table.
 */
export async function expandFirstRow(page: Page, testIds: DashboardTestIds): Promise<void> {
  const rows = page.getByTestId(testIds.itemRow);
  const firstExpandButton = rows.first().getByTestId(testIds.expandButton);
  await firstExpandButton.click();
  // Wait for expansion animation
  await page.waitForTimeout(1_000);
}

/**
 * Click a sortable column header by its th id attribute.
 */
export async function clickSortHeader(page: Page, headerLocator: string): Promise<void> {
  const header = page.locator(headerLocator);
  await header.click();
}
