/**
 * Centralized test ID constants for E2E testing.
 * These constants are shared between Angular components and Playwright tests
 * to prevent typos and make refactoring easier.
 */

/** Common dashboard test IDs, prefixed per source. */
function dashboardIds(prefix: string) {
  return {
    table: `${prefix}-table`,
    itemRow: `${prefix}-row`,
    itemName: `${prefix}-name`,
    itemStatus: `${prefix}-status`,
    expandButton: `${prefix}-expand-button`,
    buttons: {
      startScan: `${prefix}-btn-start-scan`,
      pauseScan: `${prefix}-btn-pause-scan`,
      resumeScan: `${prefix}-btn-resume-scan`,
    },
    headers: {
      name: `${prefix}-header-name`,
      status: `${prefix}-header-status`,
      progress: `${prefix}-header-progress`,
      lastScan: `${prefix}-header-last-scan`,
      pii: `${prefix}-header-pii`,
      riskScore: `${prefix}-header-risk-score`,
      actions: `${prefix}-header-actions`,
    },
    badges: {
      total: `${prefix}-pii-badge-total`,
      high: `${prefix}-pii-badge-high`,
      medium: `${prefix}-pii-badge-medium`,
      low: `${prefix}-pii-badge-low`,
    },
    scanStatusBadge: `${prefix}-scan-status-badge`,
    globalFilter: `${prefix}-global-filter`,
    statusFilter: `${prefix}-status-filter`,
  } as const;
}

export type DashboardTestIds = ReturnType<typeof dashboardIds>;

export const TestIds = {
  /** @deprecated Use source-specific ids (confluence, jira, sharepoint) */
  dashboard: {
    table: 'spaces-table',
    spaceRow: 'space-row',
    spaceName: 'space-name',
    spaceStatus: 'space-status',
    expandButton: 'expand-button',
    buttons: {
      startScan: 'btn-start-scan',
      pauseScan: 'btn-pause-scan',
      resumeScan: 'btn-resume-scan',
      purgeData: 'btn-purge-data'
    },
    headers: {
      space: 'header-space',
      status: 'header-status',
      progress: 'header-progress',
      lastScan: 'header-last-scan',
      pii: 'header-pii',
      riskScore: 'header-risk-score',
      actions: 'header-actions'
    },
    badges: {
      total: 'pii-badge-total',
      high: 'pii-badge-high',
      medium: 'pii-badge-medium',
      low: 'pii-badge-low'
    }
  },
  confluence: dashboardIds('confluence'),
  jira: dashboardIds('jira'),
  sharepoint: dashboardIds('sharepoint'),
  piiPageCard: {
    card: 'pii-page-card',
    collapsed: 'pii-card-collapsed',
    expanded: 'pii-card-expanded',
    expandButton: 'pii-card-expand',
    collapseButton: 'pii-card-collapse',
    revealButton: 'pii-card-reveal',
    sourceLink: 'pii-card-source-link',
  },
  settings: {
    dialog: 'settings-dialog',
    openButton: 'settings-open-button',
    sidebar: {
      detectors: 'settings-nav-detectors',
      thresholds: 'settings-nav-thresholds',
      piiTypes: 'settings-nav-pii-types',
    },
    detectors: {
      glinerToggle: 'detector-gliner-toggle',
      presidioToggle: 'detector-presidio-toggle',
      regexToggle: 'detector-regex-toggle',
    },
    piiTypes: {
      searchInput: 'pii-types-search',
      typeToggle: 'pii-type-toggle',
      unsavedBanner: 'pii-types-unsaved-banner',
    },
    actions: {
      saveAll: 'settings-save-all',
      resetAll: 'settings-reset-all',
      cancel: 'settings-cancel',
    },
  },
} as const;

// Type exports for type safety
export type TestIdKey = typeof TestIds;
