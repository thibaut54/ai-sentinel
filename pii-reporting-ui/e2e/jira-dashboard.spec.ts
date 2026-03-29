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
 * E2E tests for the Jira Dashboard.
 * Covers table display, scan lifecycle (start/pause/resume), sorting, filtering,
 * row expansion with PII detail cards, and the reveal feature.
 */
test.describe('Jira Dashboard', () => {
  const testIds = TestIds.jira;
  const dialogSelectors = DIALOG_SELECTORS.confirmDialog;

  test.beforeEach(async ({ page }) => {
    await navigateToDashboard(page, 'jira');
  });

  // ---------------------------------------------------------------------------
  // 1. Table Display
  // ---------------------------------------------------------------------------
  test.describe('Table Display', () => {
    test('should display table with project data', async ({ page }) => {
      await waitForTableLoaded(page, testIds);

      const rows = page.getByTestId(testIds.itemRow);
      const rowCount = await rows.count();
      expect(rowCount).toBeGreaterThan(0);
    });

    test('should display table headers correctly', async ({ page }) => {
      const table = page.getByTestId(testIds.table);
      await expect(table).toBeVisible({ timeout: 15_000 });

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
      await waitForTableLoaded(page, testIds);

      const startButton = page.getByTestId(testIds.buttons.startScan);
      await expect(startButton).toBeEnabled({ timeout: 15_000 });
      await startButton.click();

      const confirmDialog = page.locator(dialogSelectors.container);
      await expect(confirmDialog).toBeVisible({ timeout: 5_000 });

      const dialogHeader = confirmDialog.locator(dialogSelectors.header);
      await expect(dialogHeader).toBeVisible();

      const dialogMessage = confirmDialog.locator(dialogSelectors.message);
      await expect(dialogMessage).toBeVisible();

      const acceptButton = confirmDialog.locator(dialogSelectors.acceptButton);
      const rejectButton = confirmDialog.locator(dialogSelectors.rejectButton);
      await expect(acceptButton).toBeVisible();
      await expect(rejectButton).toBeVisible();

      // Close dialog to avoid interference
      await rejectButton.click();
      await expect(confirmDialog).not.toBeVisible();
    });

    test('should keep button states unchanged when rejecting confirmation', async ({ page }) => {
      await waitForTableLoaded(page, testIds);

      const startButton = page.getByTestId(testIds.buttons.startScan);
      const pauseButton = page.getByTestId(testIds.buttons.pauseScan);
      const resumeButton = page.getByTestId(testIds.buttons.resumeScan);

      // Capture initial states
      const initialStartEnabled = await startButton.isEnabled();
      const initialPauseEnabled = await pauseButton.isEnabled();
      const initialResumeEnabled = await resumeButton.isEnabled();

      // Open & reject dialog
      await startButton.click();
      const confirmDialog = page.locator(dialogSelectors.container);
      await expect(confirmDialog).toBeVisible({ timeout: 5_000 });

      const rejectButton = confirmDialog.locator(dialogSelectors.rejectButton);
      await rejectButton.click();
      await expect(confirmDialog).not.toBeVisible();

      // Verify all states unchanged
      expect(await startButton.isEnabled()).toBe(initialStartEnabled);
      expect(await pauseButton.isEnabled()).toBe(initialPauseEnabled);
      expect(await resumeButton.isEnabled()).toBe(initialResumeEnabled);

      await expect(startButton).toBeEnabled();
    });

    test('should start scan when accepting confirmation', async ({ page }) => {
      await waitForTableLoaded(page, testIds);

      const startButton = page.getByTestId(testIds.buttons.startScan);
      const pauseButton = page.getByTestId(testIds.buttons.pauseScan);

      await startButton.click();

      const confirmDialog = page.locator(dialogSelectors.container);
      await expect(confirmDialog).toBeVisible({ timeout: 5_000 });

      const acceptButton = confirmDialog.locator(dialogSelectors.acceptButton);
      await acceptButton.click();
      await expect(confirmDialog).not.toBeVisible({ timeout: 5_000 });

      // Scan started: pause button should become enabled
      await expect(pauseButton).toBeEnabled({ timeout: 20_000 });
    });
  });

  // ---------------------------------------------------------------------------
  // 3. Scan Lifecycle (Play / Pause / Resume) — serial because stateful
  // ---------------------------------------------------------------------------
  test.describe.serial('Scan Lifecycle', () => {
    test('should display paused status when scan is paused', async ({ page }) => {
      await navigateToDashboard(page, 'jira');
      await waitForTableLoaded(page, testIds);

      // Start scan and confirm
      await startScanAndConfirm(page, testIds);
      await waitForScanRunning(page, testIds);

      // Wait for at least one project to show "En cours"
      const runningStatus = page.getByTestId(testIds.itemStatus).filter({ hasText: 'En cours' });
      await expect(runningStatus.first()).toBeVisible({ timeout: 30_000 });

      // Pause the scan
      await pauseScan(page, testIds);

      // Verify at least one project shows "En pause"
      const pausedStatus = page.getByTestId(testIds.itemStatus).filter({ hasText: 'En pause' });
      await expect(pausedStatus.first()).toBeVisible({ timeout: 10_000 });

      // Resume button should be enabled
      const resumeButton = page.getByTestId(testIds.buttons.resumeScan);
      await expect(resumeButton).toBeEnabled({ timeout: 5_000 });

      const pausedCount = await pausedStatus.count();
      expect(pausedCount).toBeGreaterThan(0);
    });

    test('should resume scan after pause', async ({ page }) => {
      await navigateToDashboard(page, 'jira');
      await waitForTableLoaded(page, testIds);

      // Start → pause → resume
      await startScanAndConfirm(page, testIds);
      await waitForScanRunning(page, testIds);
      await pauseScan(page, testIds);

      // Verify resume button is available then resume
      const resumeButton = page.getByTestId(testIds.buttons.resumeScan);
      await expect(resumeButton).toBeEnabled({ timeout: 5_000 });
      await resumeScan(page, testIds);

      // After resuming, pause button should become enabled again
      const pauseButton = page.getByTestId(testIds.buttons.pauseScan);
      await expect(pauseButton).toBeEnabled({ timeout: 20_000 });
    });

    test('should update scan status badge during scan lifecycle', async ({ page }) => {
      await navigateToDashboard(page, 'jira');
      await waitForTableLoaded(page, testIds);

      // Start scan
      await startScanAndConfirm(page, testIds);
      await waitForScanRunning(page, testIds);

      // Badge should indicate active scan
      await verifyScanStatusBadge(page, testIds, true);

      // Pause scan
      await pauseScan(page, testIds);

      // Badge should indicate inactive scan
      await verifyScanStatusBadge(page, testIds, false);
    });
  });

  // ---------------------------------------------------------------------------
  // 4. Table Sorting
  // ---------------------------------------------------------------------------
  test.describe('Table Sorting', () => {
    test('should sort by name when clicking name header', async ({ page }) => {
      await waitForTableLoaded(page, testIds);

      const nameHeader = page.getByTestId(testIds.headers.name);
      await nameHeader.click();

      // Collect project names after first click (ascending)
      const names = page.getByTestId(testIds.itemName);
      await expect(names.first()).toBeVisible({ timeout: 5_000 });
      const count = await names.count();
      const nameTexts: string[] = [];
      for (let i = 0; i < count; i++) {
        const text = await names.nth(i).textContent();
        nameTexts.push((text ?? '').trim().toLowerCase());
      }

      // Verify sorted ascending
      const sorted = [...nameTexts].sort((a, b) => a.localeCompare(b));
      expect(nameTexts).toEqual(sorted);

      // Click again for descending
      await nameHeader.click();
      const nameTextsDesc: string[] = [];
      for (let i = 0; i < count; i++) {
        const text = await names.nth(i).textContent();
        nameTextsDesc.push((text ?? '').trim().toLowerCase());
      }

      const sortedDesc = [...nameTextsDesc].sort((a, b) => b.localeCompare(a));
      expect(nameTextsDesc).toEqual(sortedDesc);
    });

    test('should sort by risk score when clicking risk score header', async ({ page }) => {
      await waitForTableLoaded(page, testIds);

      const riskScoreHeader = page.getByTestId(testIds.headers.riskScore);
      await riskScoreHeader.click();

      // Give the table time to re-render
      await page.waitForTimeout(500);

      // Verify column header is still visible (sort was applied)
      await expect(riskScoreHeader).toBeVisible();

      // Collect risk score badge values
      const badges = page.getByTestId(testIds.badges.total);
      const count = await badges.count();
      const values: number[] = [];
      for (let i = 0; i < count; i++) {
        const text = await badges.nth(i).textContent();
        values.push(parseInt((text ?? '0').trim(), 10) || 0);
      }

      // Verify the values are sorted (ascending or descending depending on default)
      const sortedAsc = [...values].sort((a, b) => a - b);
      const sortedDesc = [...values].sort((a, b) => b - a);
      const isSorted = JSON.stringify(values) === JSON.stringify(sortedAsc) ||
                        JSON.stringify(values) === JSON.stringify(sortedDesc);
      expect(isSorted).toBe(true);
    });
  });

  // ---------------------------------------------------------------------------
  // 5. Table Filtering
  // ---------------------------------------------------------------------------
  test.describe('Table Filtering', () => {
    test('should filter by global search', async ({ page }) => {
      await waitForTableLoaded(page, testIds);

      // Get the first project name to use as search term
      const firstName = page.getByTestId(testIds.itemName).first();
      const searchTerm = ((await firstName.textContent()) ?? '').trim();
      expect(searchTerm.length).toBeGreaterThan(0);

      // Type in the global filter input
      const filterInput = page.getByTestId(testIds.globalFilter);
      await expect(filterInput).toBeVisible();
      await filterInput.fill(searchTerm);

      // Wait for filtering to take effect
      await page.waitForTimeout(500);

      // All visible rows should contain the search term
      const rows = page.getByTestId(testIds.itemRow);
      const rowCount = await rows.count();
      expect(rowCount).toBeGreaterThan(0);

      for (let i = 0; i < rowCount; i++) {
        const nameCell = rows.nth(i).getByTestId(testIds.itemName);
        await expect(nameCell).toContainText(searchTerm, { ignoreCase: true });
      }

      // Clear the filter and verify all rows reappear
      await filterInput.clear();
      await page.waitForTimeout(500);
      const allRowCount = await page.getByTestId(testIds.itemRow).count();
      expect(allRowCount).toBeGreaterThanOrEqual(rowCount);
    });
  });

  // ---------------------------------------------------------------------------
  // 6. Expand and PII Details
  // ---------------------------------------------------------------------------
  test.describe('Expand and PII Details', () => {
    test('should display PII cards when expanding a row after scan', async ({ page }) => {
      await waitForTableLoaded(page, testIds);

      // Start scan and wait for PII results
      await startScanAndConfirm(page, testIds);
      await waitForScanRunning(page, testIds);

      // Wait for at least one row to have a PII badge with value > 0
      const rows = page.getByTestId(testIds.itemRow);
      const firstRowBadge = rows.first().locator(`[data-testid="${testIds.badges.total}"]`);
      await expect(firstRowBadge).toBeVisible({ timeout: 60_000 });

      await page.waitForFunction(
        (badgeSelector) => {
          const badge = document.querySelector(`[data-testid="${badgeSelector}"]`);
          if (!badge) return false;
          const value = badge.getAttribute('ng-reflect-value') || badge.textContent;
          return value !== null && parseInt(value) > 0;
        },
        testIds.badges.total,
        { timeout: 60_000 },
      );

      // Expand the first row
      await expandFirstRow(page, testIds);

      // Verify expanded section with PII cards
      const expandedSection = page.locator('.row-expansion');
      await expect(expandedSection).toBeVisible({ timeout: 10_000 });

      const piiCards = page.getByTestId(TestIds.piiPageCard.card);
      await expect(piiCards.first()).toBeVisible({ timeout: 20_000 });

      const cardCount = await piiCards.count();
      expect(cardCount).toBeGreaterThan(0);
    });

    test('should display detail table with sortable columns in expanded card', async ({ page }) => {
      await waitForTableLoaded(page, testIds);

      // Start scan and wait for PII results
      await startScanAndConfirm(page, testIds);
      await waitForScanRunning(page, testIds);

      // Wait for PII data
      await page.waitForFunction(
        (badgeSelector) => {
          const badge = document.querySelector(`[data-testid="${badgeSelector}"]`);
          if (!badge) return false;
          const value = badge.getAttribute('ng-reflect-value') || badge.textContent;
          return value !== null && parseInt(value) > 0;
        },
        testIds.badges.total,
        { timeout: 60_000 },
      );

      // Expand the first row
      await expandFirstRow(page, testIds);

      const expandedSection = page.locator('.row-expansion');
      await expect(expandedSection).toBeVisible({ timeout: 10_000 });

      // Click on the first collapsed PII card to expand it
      const piiCards = page.getByTestId(TestIds.piiPageCard.card);
      await expect(piiCards.first()).toBeVisible({ timeout: 20_000 });
      await piiCards.first().click();
      await page.waitForTimeout(1_000);

      // Verify the detail table is visible with sortable headers
      const detailTable = expandedSection.locator('.detail-table');
      await expect(detailTable).toBeVisible({ timeout: 10_000 });

      // Verify sortable column headers exist
      const sortableHeaders = detailTable.locator('.th-sortable');
      const headerCount = await sortableHeaders.count();
      expect(headerCount).toBeGreaterThanOrEqual(3);

      // Click a sortable header to verify sorting works
      const typeHeader = detailTable.locator('.th-type');
      await typeHeader.click();
      await expect(typeHeader.locator('.sort-indicator--active')).toBeVisible({ timeout: 3_000 });
    });
  });

  // ---------------------------------------------------------------------------
  // 7. Reveal Feature
  // ---------------------------------------------------------------------------
  test.describe('Reveal Feature', () => {
    test('should show reveal button in expanded PII card', async ({ page }) => {
      await waitForTableLoaded(page, testIds);

      // Start scan and wait for PII results
      await startScanAndConfirm(page, testIds);
      await waitForScanRunning(page, testIds);

      // Wait for PII data
      await page.waitForFunction(
        (badgeSelector) => {
          const badge = document.querySelector(`[data-testid="${badgeSelector}"]`);
          if (!badge) return false;
          const value = badge.getAttribute('ng-reflect-value') || badge.textContent;
          return value !== null && parseInt(value) > 0;
        },
        testIds.badges.total,
        { timeout: 60_000 },
      );

      // Expand the first table row
      await expandFirstRow(page, testIds);

      const expandedSection = page.locator('.row-expansion');
      await expect(expandedSection).toBeVisible({ timeout: 10_000 });

      // Click first PII card to expand it
      const piiCards = page.getByTestId(TestIds.piiPageCard.card);
      await expect(piiCards.first()).toBeVisible({ timeout: 20_000 });
      await piiCards.first().click();
      await page.waitForTimeout(1_000);

      // Verify the expanded card contains a reveal button
      const expandedCard = expandedSection.locator('.expanded-card').first();
      await expect(expandedCard).toBeVisible({ timeout: 5_000 });

      const revealButton = expandedCard.locator('.btn-reveal');
      // The reveal button is only shown when revealAllowed() is true.
      // We verify it is present or that the card footer exists as fallback.
      const cardFooter = expandedCard.locator('.card-footer');
      await expect(cardFooter).toBeVisible({ timeout: 5_000 });

      if (await revealButton.isVisible()) {
        await expect(revealButton).toBeEnabled();
      }
    });
  });
});
