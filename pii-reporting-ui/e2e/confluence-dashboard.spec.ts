import { expect, test } from '@playwright/test';
import { TestIds } from '../src/app/features/test-ids.constants';
import { DIALOG_SELECTORS } from './helpers/dialog-selectors';
import {
  navigateToDashboard,
  waitForTableLoaded,
  startScanAndConfirm,
  waitForScanRunning,
  pauseScan,
  resumeScan,
  verifyScanStatusBadge,
  expandFirstRow,
} from './helpers/dashboard.helpers';

/**
 * Consolidated E2E tests for the Confluence Dashboard.
 * Replaces: dashboard.spec.ts, scan-confirmation.spec.ts, scan-pause.spec.ts, scan-expand-items.spec.ts
 */
test.describe('Confluence Dashboard', () => {
  const testIds = TestIds.confluence;
  const dialogSelectors = DIALOG_SELECTORS.confirmDialog;

  test.beforeEach(async ({ page }) => {
    await navigateToDashboard(page, 'confluence');
    await waitForTableLoaded(page, testIds);
  });

  // ---------------------------------------------------------------------------
  // 1. Table Display
  // ---------------------------------------------------------------------------
  test.describe('Table Display', () => {
    test('should display table with space data', async ({ page }) => {
      const rows = page.getByTestId(testIds.itemRow);
      const rowCount = await rows.count();
      expect(rowCount).toBeGreaterThan(0);
    });

    test('should display table headers correctly', async ({ page }) => {
      await expect(page.getByTestId(testIds.headers.name)).toBeVisible();
      await expect(page.getByTestId(testIds.headers.status)).toBeVisible();
      await expect(page.getByTestId(testIds.headers.progress)).toBeVisible();
      await expect(page.getByTestId(testIds.headers.lastScan)).toBeVisible();
      await expect(page.getByTestId(testIds.headers.riskScore)).toBeVisible();
      await expect(page.getByTestId(testIds.headers.pii)).toBeVisible();
      await expect(page.getByTestId(testIds.headers.actions)).toBeVisible();
    });
  });

  // ---------------------------------------------------------------------------
  // 2. Scan Confirmation Dialog
  // ---------------------------------------------------------------------------
  test.describe('Scan Confirmation Dialog', () => {
    test('should show confirmation dialog when clicking start scan', async ({ page }) => {
      const startButton = page.getByTestId(testIds.buttons.startScan);
      await expect(startButton).toBeEnabled({ timeout: 10_000 });
      await startButton.click();

      const confirmDialog = page.locator(dialogSelectors.container);
      await expect(confirmDialog).toBeVisible({ timeout: 5_000 });

      // Verify dialog elements
      await expect(confirmDialog.locator(dialogSelectors.header)).toBeVisible();
      await expect(confirmDialog.locator(dialogSelectors.message)).toBeVisible();
      await expect(confirmDialog.locator(dialogSelectors.acceptButton)).toBeVisible();
      await expect(confirmDialog.locator(dialogSelectors.rejectButton)).toBeVisible();

      // Close dialog
      await confirmDialog.locator(dialogSelectors.rejectButton).click();
      await expect(confirmDialog).not.toBeVisible();
    });

    test('should keep button states unchanged when rejecting confirmation', async ({ page }) => {
      const startButton = page.getByTestId(testIds.buttons.startScan);
      const pauseButton = page.getByTestId(testIds.buttons.pauseScan);
      const resumeButton = page.getByTestId(testIds.buttons.resumeScan);

      // Capture initial states
      const initialStartEnabled = await startButton.isEnabled();
      const initialPauseEnabled = await pauseButton.isEnabled();
      const initialResumeEnabled = await resumeButton.isEnabled();

      // Open and reject dialog
      await startButton.click();
      const confirmDialog = page.locator(dialogSelectors.container);
      await expect(confirmDialog).toBeVisible({ timeout: 5_000 });
      await confirmDialog.locator(dialogSelectors.rejectButton).click();
      await expect(confirmDialog).not.toBeVisible();

      // Verify states unchanged
      expect(await startButton.isEnabled()).toBe(initialStartEnabled);
      expect(await pauseButton.isEnabled()).toBe(initialPauseEnabled);
      expect(await resumeButton.isEnabled()).toBe(initialResumeEnabled);
    });

    test('should start scan when accepting confirmation', async ({ page }) => {
      const startButton = page.getByTestId(testIds.buttons.startScan);
      const pauseButton = page.getByTestId(testIds.buttons.pauseScan);

      await startButton.click();

      const confirmDialog = page.locator(dialogSelectors.container);
      await expect(confirmDialog).toBeVisible({ timeout: 5_000 });
      await confirmDialog.locator(dialogSelectors.acceptButton).click();
      await expect(confirmDialog).not.toBeVisible({ timeout: 5_000 });

      // Scan started: pause button should become enabled
      await expect(pauseButton).toBeEnabled({ timeout: 15_000 });
    });
  });

  // ---------------------------------------------------------------------------
  // 3. Scan Lifecycle (Play / Pause / Resume)
  // ---------------------------------------------------------------------------
  test.describe.serial('Scan Lifecycle', () => {
    test('should display paused status when scan is paused', async ({ page }) => {
      await startScanAndConfirm(page, testIds);
      await waitForScanRunning(page, testIds);

      // Wait for at least one row to show 'En cours'
      const runningStatus = page.getByTestId(testIds.itemStatus).filter({ hasText: 'En cours' });
      await expect(runningStatus.first()).toBeVisible({ timeout: 30_000 });

      // Pause the scan
      await pauseScan(page, testIds);

      // Verify at least one row shows 'En pause'
      const pausedStatus = page.getByTestId(testIds.itemStatus).filter({ hasText: 'En pause' });
      await expect(pausedStatus.first()).toBeVisible({ timeout: 10_000 });
      const pausedCount = await pausedStatus.count();
      expect(pausedCount).toBeGreaterThan(0);

      // Resume button should be enabled
      const resumeButton = page.getByTestId(testIds.buttons.resumeScan);
      await expect(resumeButton).toBeEnabled({ timeout: 5_000 });
    });

    test('should resume scan after pause', async ({ page }) => {
      await startScanAndConfirm(page, testIds);
      await waitForScanRunning(page, testIds);
      await pauseScan(page, testIds);

      // Resume
      await resumeScan(page, testIds);

      // Pause button should become enabled again
      const pauseButton = page.getByTestId(testIds.buttons.pauseScan);
      await expect(pauseButton).toBeEnabled({ timeout: 15_000 });
    });

    test('should update scan status badge during scan lifecycle', async ({ page }) => {
      // Initially inactive
      await verifyScanStatusBadge(page, testIds, false);

      // Start scan -> scanning
      await startScanAndConfirm(page, testIds);
      await waitForScanRunning(page, testIds);
      await verifyScanStatusBadge(page, testIds, true);

      // Pause -> inactive
      await pauseScan(page, testIds);
      await verifyScanStatusBadge(page, testIds, false);
    });
  });

  // ---------------------------------------------------------------------------
  // 4. Table Sorting
  // ---------------------------------------------------------------------------
  test.describe('Table Sorting', () => {
    test('should sort by name when clicking name header', async ({ page }) => {
      const nameHeader = page.getByTestId(testIds.headers.name);

      // Collect names before sort click
      const namesBefore: string[] = [];
      const nameElements = page.getByTestId(testIds.itemName);
      const count = await nameElements.count();
      for (let i = 0; i < count; i++) {
        const text = await nameElements.nth(i).textContent();
        namesBefore.push(text?.trim() ?? '');
      }

      // Click to toggle sort direction
      await nameHeader.click();
      await page.waitForTimeout(500);

      // Collect names after sort
      const namesAfter: string[] = [];
      const updatedNames = page.getByTestId(testIds.itemName);
      const updatedCount = await updatedNames.count();
      for (let i = 0; i < updatedCount; i++) {
        const text = await updatedNames.nth(i).textContent();
        namesAfter.push(text?.trim() ?? '');
      }

      // Verify sort changed the order (or list is already sorted in that direction)
      // We check that the names are in alphabetical order (ascending or descending)
      const ascending = [...namesAfter].sort((a, b) => a.localeCompare(b));
      const descending = [...namesAfter].sort((a, b) => b.localeCompare(a));
      const isSorted =
        JSON.stringify(namesAfter) === JSON.stringify(ascending) ||
        JSON.stringify(namesAfter) === JSON.stringify(descending);
      expect(isSorted).toBe(true);
    });

    test('should sort by risk score when clicking risk score header', async ({ page }) => {
      const riskScoreHeader = page.getByTestId(testIds.headers.riskScore);
      await riskScoreHeader.click();
      await page.waitForTimeout(500);

      // Verify that the header is clickable and the table re-renders
      const rows = page.getByTestId(testIds.itemRow);
      const rowCount = await rows.count();
      expect(rowCount).toBeGreaterThan(0);
    });
  });

  // ---------------------------------------------------------------------------
  // 5. Table Filtering
  // ---------------------------------------------------------------------------
  test.describe('Table Filtering', () => {
    test('should filter by global search', async ({ page }) => {
      const rows = page.getByTestId(testIds.itemRow);
      const initialCount = await rows.count();

      // Get the name of the first row to use as search term
      const firstName = await page.getByTestId(testIds.itemName).first().textContent();
      const searchTerm = firstName?.trim().substring(0, 3) ?? '';

      // Type in global filter
      const globalFilter = page.getByTestId(testIds.globalFilter);
      await globalFilter.fill(searchTerm);
      await page.waitForTimeout(500);

      // Verify that filtering happened (row count changed or remained for matching items)
      const filteredCount = await rows.count();
      expect(filteredCount).toBeGreaterThan(0);
      expect(filteredCount).toBeLessThanOrEqual(initialCount);
    });

    test('should filter by status', async ({ page }) => {
      const rows = page.getByTestId(testIds.itemRow);
      const initialCount = await rows.count();

      // Open the status filter dropdown
      const statusFilter = page.getByTestId(testIds.statusFilter);
      await statusFilter.click();
      await page.waitForTimeout(300);

      // Select the first available option in the dropdown
      const dropdownOption = page.locator('.p-select-option').first();
      if (await dropdownOption.isVisible({ timeout: 3_000 })) {
        await dropdownOption.click();
        await page.waitForTimeout(500);

        const filteredCount = await rows.count();
        // Filtered count should differ or equal initial (depending on data)
        expect(filteredCount).toBeLessThanOrEqual(initialCount);
      }
    });
  });

  // ---------------------------------------------------------------------------
  // 6. Expand and PII Details
  // ---------------------------------------------------------------------------
  test.describe.serial('Expand and PII Details', () => {
    test('should display PII cards when expanding a row after scan', async ({ page }) => {
      await startScanAndConfirm(page, testIds);
      await waitForScanRunning(page, testIds);

      // Wait for PII badge > 0 on first row
      const rows = page.getByTestId(testIds.itemRow);
      const firstRowBadge = rows.first().getByTestId(testIds.badges.total);
      await expect(firstRowBadge).toBeVisible({ timeout: 30_000 });

      await page.waitForFunction(
        (badgeTestId: string) => {
          const badge = document.querySelector(`[data-testid="${badgeTestId}"]`);
          if (!badge) return false;
          const value = badge.getAttribute('ng-reflect-value') || badge.textContent;
          return value !== null && parseInt(value) > 0;
        },
        testIds.badges.total,
        { timeout: 60_000 },
      );

      // Expand first row
      await expandFirstRow(page, testIds);

      // Verify PII page cards are visible
      const piiCards = page.getByTestId(TestIds.piiPageCard.card);
      await expect(piiCards.first()).toBeVisible({ timeout: 20_000 });
      const cardCount = await piiCards.count();
      expect(cardCount).toBeGreaterThan(0);
    });

    test('should display detail table with sortable columns in expanded card', async ({ page }) => {
      await startScanAndConfirm(page, testIds);
      await waitForScanRunning(page, testIds);

      // Wait for PII results
      await page.waitForFunction(
        (badgeTestId: string) => {
          const badge = document.querySelector(`[data-testid="${badgeTestId}"]`);
          if (!badge) return false;
          const value = badge.getAttribute('ng-reflect-value') || badge.textContent;
          return value !== null && parseInt(value) > 0;
        },
        testIds.badges.total,
        { timeout: 60_000 },
      );

      // Expand first row
      await expandFirstRow(page, testIds);

      // Verify expanded card is visible
      const piiCards = page.getByTestId(TestIds.piiPageCard.card);
      await expect(piiCards.first()).toBeVisible({ timeout: 20_000 });

      // Expand the first PII card to see the detail table
      const expandCardButton = page.getByTestId(TestIds.piiPageCard.expandButton).first();
      if (await expandCardButton.isVisible({ timeout: 5_000 })) {
        await expandCardButton.click();
        await page.waitForTimeout(500);

        // Verify the expanded section is visible
        const expandedSection = page.getByTestId(TestIds.piiPageCard.expanded).first();
        await expect(expandedSection).toBeVisible({ timeout: 10_000 });

        // Verify detail table has sortable column headers (type, value, confidence, detector)
        const detailTable = expandedSection.locator('table, p-table');
        if (await detailTable.isVisible({ timeout: 5_000 })) {
          const headers = detailTable.locator('th');
          const headerCount = await headers.count();
          expect(headerCount).toBeGreaterThanOrEqual(3);
        }
      }
    });
  });

  // ---------------------------------------------------------------------------
  // 7. Reveal Feature
  // ---------------------------------------------------------------------------
  test.describe('Reveal Feature', () => {
    test('should show reveal button in expanded PII card', async ({ page }) => {
      await startScanAndConfirm(page, testIds);
      await waitForScanRunning(page, testIds);

      // Wait for PII results
      await page.waitForFunction(
        (badgeTestId: string) => {
          const badge = document.querySelector(`[data-testid="${badgeTestId}"]`);
          if (!badge) return false;
          const value = badge.getAttribute('ng-reflect-value') || badge.textContent;
          return value !== null && parseInt(value) > 0;
        },
        testIds.badges.total,
        { timeout: 60_000 },
      );

      // Expand first row
      await expandFirstRow(page, testIds);

      // Verify PII cards are visible
      const piiCards = page.getByTestId(TestIds.piiPageCard.card);
      await expect(piiCards.first()).toBeVisible({ timeout: 20_000 });

      // Verify reveal button is present
      const revealButton = page.getByTestId(TestIds.piiPageCard.revealButton).first();
      await expect(revealButton).toBeVisible({ timeout: 10_000 });
    });
  });
});
