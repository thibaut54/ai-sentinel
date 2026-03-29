import { expect, test } from '@playwright/test';
import { openSettingsDialog, navigateToSettingsSection, closeSettingsDialog } from './helpers/settings.helpers';

/**
 * E2E tests for the PII Settings modal.
 * Covers dialog navigation, detectors section, PII types section, and save/reset actions.
 */
test.describe('PII Settings', () => {

  // ===== 1. Settings Dialog Navigation =====
  test.describe('Settings Dialog Navigation', () => {
    test.beforeEach(async ({ page }) => {
      await page.goto('/');
      await openSettingsDialog(page);
    });

    test('should open settings dialog from header button', async ({ page }) => {
      const dialog = page.locator('.settings-fullscreen-dialog');
      await expect(dialog).toBeVisible();

      // Verify the dialog has a title
      const dialogTitle = dialog.locator('.p-dialog-title');
      await expect(dialogTitle).toBeVisible();
      await expect(dialogTitle).toHaveText(/Settings/i);
    });

    test('should navigate between settings sections', async ({ page }) => {
      const sidebarButtons = page.locator('.sidebar-nav-item');

      // Navigate to Detectors (first button — should be active by default)
      await sidebarButtons.nth(0).click();
      await expect(sidebarButtons.nth(0)).toHaveClass(/active/);
      const detectorsSection = page.locator('.section-title');
      await expect(detectorsSection).toBeVisible();

      // Navigate to Thresholds (second button)
      await sidebarButtons.nth(1).click();
      await expect(sidebarButtons.nth(1)).toHaveClass(/active/);
      const thresholdCard = page.locator('.threshold-card').first();
      await expect(thresholdCard).toBeVisible();

      // Navigate to PII Types (third button)
      await sidebarButtons.nth(2).click();
      await expect(sidebarButtons.nth(2)).toHaveClass(/active/);
      const piiTypesHeader = page.locator('.pii-types-header');
      await expect(piiTypesHeader).toBeVisible();
    });

    test('should close settings dialog', async ({ page }) => {
      const dialog = page.locator('.settings-fullscreen-dialog');
      await expect(dialog).toBeVisible();

      await closeSettingsDialog(page);
      await expect(dialog).not.toBeVisible({ timeout: 5_000 });
    });
  });

  // ===== 2. Detectors Section =====
  test.describe('Detectors Section', () => {
    test.beforeEach(async ({ page }) => {
      await page.goto('/');
      await openSettingsDialog(page);
      // Detectors section is the default active section
    });

    test('should display all three detector cards', async ({ page }) => {
      const detectorCards = page.locator('.detector-card');
      await expect(detectorCards).toHaveCount(3);

      // Verify GLiNER card
      const glinerCard = page.locator('.detector-card.gliner');
      await expect(glinerCard).toBeVisible();
      await expect(glinerCard.locator('.detector-avatar')).toHaveText('GL');

      // Verify Presidio card
      const presidioCard = page.locator('.detector-card.presidio');
      await expect(presidioCard).toBeVisible();
      await expect(presidioCard.locator('.detector-avatar')).toHaveText('PR');

      // Verify Regex card
      const regexCard = page.locator('.detector-card.regex');
      await expect(regexCard).toBeVisible();
      await expect(regexCard.locator('.detector-avatar')).toHaveText('RE');
    });

    test('should toggle detector on/off', async ({ page }) => {
      const glinerCard = page.locator('.detector-card.gliner');
      await expect(glinerCard).toBeVisible();

      // The card should initially be enabled
      await expect(glinerCard).toHaveClass(/enabled/);

      // Click the toggle switch inside the GLiNER card to disable it
      const toggleSwitch = glinerCard.locator('p-toggleswitch').first();
      await toggleSwitch.click();

      // Verify the card now has the 'disabled' class
      await expect(glinerCard).toHaveClass(/disabled/);

      // Toggle back on
      await toggleSwitch.click();

      // Verify the card is enabled again
      await expect(glinerCard).toHaveClass(/enabled/);
    });

    test('should show validation error when all detectors disabled', async ({ page }) => {
      // Disable all three detectors
      const glinerToggle = page.locator('.detector-card.gliner p-toggleswitch').first();
      const presidioToggle = page.locator('.detector-card.presidio p-toggleswitch').first();
      const regexToggle = page.locator('.detector-card.regex p-toggleswitch').first();

      await glinerToggle.click();
      await presidioToggle.click();
      await regexToggle.click();

      // The form needs to be touched for the validation error to appear
      // Clicking the toggles should mark the form as touched
      const validationError = page.locator('.validation-error');
      await expect(validationError).toBeVisible({ timeout: 5_000 });
      await expect(validationError).toContainText(/at least one detector/i);

      // Re-enable one detector to clear the error
      await glinerToggle.click();
      await expect(validationError).not.toBeVisible();
    });
  });

  // ===== 3. PII Types Section =====
  test.describe('PII Types Section', () => {
    test.beforeEach(async ({ page }) => {
      await page.goto('/');
      await openSettingsDialog(page);
      await navigateToSettingsSection(page, 'pii_types');
      // Wait for PII types content to load
      await expect(page.locator('.pii-types-header')).toBeVisible({ timeout: 10_000 });
    });

    test('should display PII types grouped by detector', async ({ page }) => {
      // Verify at least one detector group is visible
      const detectorGroups = page.locator('.detector-group');
      await expect(detectorGroups.first()).toBeVisible({ timeout: 10_000 });

      const groupCount = await detectorGroups.count();
      expect(groupCount).toBeGreaterThan(0);

      // Verify detector badge text is present (GLINER or PRESIDIO)
      const detectorBadge = page.locator('.detector-badge').first();
      await expect(detectorBadge).toBeVisible();
      const badgeText = await detectorBadge.textContent();
      expect(badgeText?.trim()).toMatch(/GLINER|PRESIDIO/);
    });

    test('should toggle PII type on/off', async ({ page }) => {
      // Find the first PII type row
      const piiTypeRow = page.locator('.pii-type-row').first();
      await expect(piiTypeRow).toBeVisible({ timeout: 10_000 });

      // Determine initial state
      const isInitiallyDisabled = await piiTypeRow.evaluate(el => el.classList.contains('disabled'));

      // Click its toggle switch
      const toggleSwitch = piiTypeRow.locator('p-toggleswitch').first();
      await toggleSwitch.click();

      // Verify the row class changed
      if (isInitiallyDisabled) {
        // Was disabled, now should be enabled (no 'disabled' class)
        await expect(piiTypeRow).not.toHaveClass(/disabled/);
      } else {
        // Was enabled, now should be disabled
        await expect(piiTypeRow).toHaveClass(/disabled/);
      }

      // The unsaved-changes-banner should appear
      const unsavedBanner = page.locator('.unsaved-changes-banner');
      await expect(unsavedBanner).toBeVisible({ timeout: 5_000 });
    });

    test('should search PII types', async ({ page }) => {
      // Wait for PII type rows to be loaded
      const allRows = page.locator('.pii-type-row');
      await expect(allRows.first()).toBeVisible({ timeout: 10_000 });

      // Count initial rows
      const initialCount = await allRows.count();
      expect(initialCount).toBeGreaterThan(0);

      // Type a search term in the search input
      const searchInput = page.locator('.pii-search-input');
      await expect(searchInput).toBeVisible();
      await searchInput.fill('email');

      // Wait for filtering to take effect
      await page.waitForTimeout(500);

      // Verify the list is filtered (fewer types or same if all match)
      const filteredCount = await allRows.count();
      expect(filteredCount).toBeLessThan(initialCount);
      expect(filteredCount).toBeGreaterThan(0);
    });

    test('should clear search', async ({ page }) => {
      // Wait for rows to load
      const allRows = page.locator('.pii-type-row');
      await expect(allRows.first()).toBeVisible({ timeout: 10_000 });
      const initialCount = await allRows.count();

      // Perform a search
      const searchInput = page.locator('.pii-search-input');
      await searchInput.fill('email');
      await page.waitForTimeout(500);

      // Verify filtered
      const filteredCount = await allRows.count();
      expect(filteredCount).toBeLessThan(initialCount);

      // Click the clear button (pi-times icon button next to search)
      const clearButton = page.locator('.pii-search-group p-button').filter({ has: page.locator('.pi-times') });
      await expect(clearButton).toBeVisible();
      await clearButton.click();

      // Verify all types are shown again
      await page.waitForTimeout(500);
      const restoredCount = await allRows.count();
      expect(restoredCount).toBe(initialCount);
    });

    test('should collapse/expand detector groups', async ({ page }) => {
      // Wait for detector groups to load
      const detectorGroup = page.locator('.detector-group').first();
      await expect(detectorGroup).toBeVisible({ timeout: 10_000 });

      // Verify categories are visible initially (expanded state)
      const categories = detectorGroup.locator('.category-subheader');
      await expect(categories.first()).toBeVisible();

      // Click the collapse toggle button in the detector group header
      const collapseToggle = detectorGroup.locator('.collapse-toggle').first();
      await collapseToggle.click();

      // Verify categories are hidden (collapsed state)
      await expect(categories.first()).not.toBeVisible();

      // Verify the header has the collapsed class
      const groupHeader = detectorGroup.locator('.detector-group-header');
      await expect(groupHeader).toHaveClass(/collapsed/);

      // Click again to expand
      await collapseToggle.click();

      // Verify categories reappear
      await expect(categories.first()).toBeVisible();
    });
  });

  // ===== 4. Save and Reset =====
  test.describe('Save and Reset', () => {
    test.beforeEach(async ({ page }) => {
      await page.goto('/');
      await openSettingsDialog(page);
    });

    test('should save all settings', async ({ page }) => {
      // Make a change: toggle the GLiNER detector off then on (to mark form as dirty)
      const glinerCard = page.locator('.detector-card.gliner');
      await expect(glinerCard).toBeVisible();

      const toggleSwitch = glinerCard.locator('p-toggleswitch').first();
      // Toggle off
      await toggleSwitch.click();
      // Toggle back on (so the saved state is the same as before)
      await toggleSwitch.click();

      // The form should be dirty now — but since we toggled back, try a single toggle
      // to ensure the save button is enabled
      await toggleSwitch.click(); // now disabled
      await expect(glinerCard).toHaveClass(/disabled/);

      // Click the save all button
      const saveButton = page.locator('.settings-footer p-button').filter({ has: page.locator('.pi-save') });
      await expect(saveButton).toBeVisible();
      await saveButton.click();

      // Verify success toast appears
      const toast = page.locator('.p-toast-message-success');
      await expect(toast).toBeVisible({ timeout: 5_000 });

      // Re-enable the detector for cleanup (toggle back on)
      await toggleSwitch.click();
      await expect(glinerCard).toHaveClass(/enabled/);
      // Save the restored state
      await saveButton.click();
      await expect(toast).toBeVisible({ timeout: 5_000 });
    });

    test('should reset all settings', async ({ page }) => {
      // Make a change: toggle GLiNER detector
      const glinerCard = page.locator('.detector-card.gliner');
      await expect(glinerCard).toBeVisible();

      // Remember initial state
      const wasEnabled = await glinerCard.evaluate(el => el.classList.contains('enabled'));

      const toggleSwitch = glinerCard.locator('p-toggleswitch').first();
      await toggleSwitch.click();

      // Verify the state changed
      if (wasEnabled) {
        await expect(glinerCard).toHaveClass(/disabled/);
      } else {
        await expect(glinerCard).toHaveClass(/enabled/);
      }

      // Click the reset all button (button with pi-replay icon in footer)
      const resetButton = page.locator('.settings-footer p-button').filter({ has: page.locator('.pi-replay') });
      await expect(resetButton).toBeVisible();
      await resetButton.click();

      // Verify the change is reverted to original state
      if (wasEnabled) {
        await expect(glinerCard).toHaveClass(/enabled/);
      } else {
        await expect(glinerCard).toHaveClass(/disabled/);
      }
    });
  });
});
