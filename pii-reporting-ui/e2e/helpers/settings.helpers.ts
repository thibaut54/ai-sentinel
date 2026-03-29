import { expect, Page } from '@playwright/test';

/**
 * Open the settings dialog from the app shell header.
 */
export async function openSettingsDialog(page: Page): Promise<void> {
  const settingsButton = page.getByTestId('settings-open-button');
  await settingsButton.click();
  // Wait for dialog to appear
  const dialog = page.locator('.settings-fullscreen-dialog');
  await expect(dialog).toBeVisible({ timeout: 5_000 });
}

/**
 * Navigate to a specific settings section via sidebar.
 */
export async function navigateToSettingsSection(page: Page, section: 'detectors' | 'thresholds' | 'pii_types'): Promise<void> {

  // Click the sidebar button matching the section
  page.locator(`.sidebar-nav-item`).filter({ hasText: new RegExp(section === 'pii_types' ? 'PII|Types' : section, 'i') });
  // Fallback: find by active class or nth position
  const buttons = page.locator('.sidebar-nav-item');
  const sectionIndexMap: Record<string, number> = { detectors: 0, thresholds: 1, pii_types: 2 };
  const index = sectionIndexMap[section];
  await buttons.nth(index).click();
}

/**
 * Close the settings dialog.
 */
export async function closeSettingsDialog(page: Page): Promise<void> {
  // Click the close button on the dialog
  const closeButton = page.locator('.settings-fullscreen-dialog .p-dialog-close-button');
  if (await closeButton.isVisible()) {
    await closeButton.click();
  }
}
