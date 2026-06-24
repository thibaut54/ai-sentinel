import { computed, inject, Injectable, signal, Signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { catchError, of } from 'rxjs';
import { SortEvent } from 'primeng/api';
import { SpacesDashboardUtils, UISpace } from '../spaces-dashboard.utils';
import { SpaceDataManagementService } from './space-data-management.service';
import { PiiDetectionConfigService } from '../../../core/services/pii-detection-config.service';
import { PiiTypeConfig } from '../../../core/models/pii-detection-config.model';

/** Severity filter values, each mapped to a UISpace severity bucket. */
export type SeverityFilterValue = 'HIGH' | 'MEDIUM' | 'LOW';

/** A facet entry exposing the bi-level counters for a single option. */
export interface FacetCount {
  /** Number of spaces matching this option within the current context. */
  nbSpaces: number;
  /** Total occurrences across those spaces. */
  totalOccurrences: number;
}

/** A selectable PII type option grouped under its category. */
export interface PiiTypeOption {
  /** PII type code (e.g. "EMAIL"). */
  code: string;
  /** i18n key for the type label. */
  labelKey: string;
  /** Detector name shown as subtitle (e.g. "PRESIDIO"). */
  detector: string;
}

/** A category group of PII type options for the grouped multi-select. */
export interface PiiTypeGroup {
  /** Category code (used to build the i18n label). */
  category: string;
  /** i18n key for the category label. */
  labelKey: string;
  /** PII type options belonging to this category. */
  items: PiiTypeOption[];
}

/** Maps a severity filter value to the matching UISpace counts bucket. */
const SEVERITY_BUCKET: Record<SeverityFilterValue, 'high' | 'medium' | 'low'> = {
  HIGH: 'high',
  MEDIUM: 'medium',
  LOW: 'low'
};

/** Weights for the derived severity score (high >> medium >> low). */
const SEVERITY_SCORE_WEIGHTS = { high: 1_000_000, medium: 1_000, low: 1 };

/** Prefix identifying a "sort by PII type" criterion. */
const SORT_PII_TYPE_PREFIX = 'piiType:';

/**
 * Single source of truth for the dashboard filter / search / sort pipeline.
 *
 * Business purpose:
 * - 3-axis client-side filtering (PII type / severity / status) with OR within a
 *   category and AND across categories.
 * - Client-side text search applied on the already-filtered result.
 * - Client-side sorting, including a derived severity score and per-type sorting.
 * - Bi-level facet counters recomputed on every interaction.
 *
 * SOLID Principles:
 * - Single Responsibility: owns the filter/search/sort pipeline only.
 * - Dependency Inversion: depends on SpacesDashboardUtils and config abstractions.
 */
@Injectable({
  providedIn: 'root'
})
export class SpaceFilteringService {
  private readonly spacesDashboardUtils = inject(SpacesDashboardUtils);
  private readonly dataManagement = inject(SpaceDataManagementService);
  private readonly piiConfigService = inject(PiiDetectionConfigService);

  // ===== Filter state =====
  readonly globalFilter = signal<string>('');
  readonly piiTypeFilter = signal<string[]>([]);
  readonly severityFilter = signal<string[]>([]);
  readonly statusFilter = signal<string[]>([]);
  readonly modifiedOnlyFilter = signal<boolean>(false);

  // ===== Sort state =====
  /**
   * Sort criterion: 'name' | 'totalDetections' | 'severityScore' | 'lastScan'
   * | 'piiType:<TYPE>' | null (no explicit sort).
   */
  readonly sortCriterion = signal<string | null>(null);
  readonly sortOrder = signal<number>(1); // 1 ascending, -1 descending

  // ===== Legacy sort field (kept for PrimeNG header sort compatibility) =====
  readonly sortField = signal<string | null>(null);

  // ===== PII type universe loaded from backend, grouped by category =====
  private readonly piiTypeConfigs: Signal<PiiTypeConfig[]> = toSignal(
    this.piiConfigService.getAllPiiTypeConfigs().pipe(catchError(() => of([] as PiiTypeConfig[]))),
    { initialValue: [] as PiiTypeConfig[] }
  );

  /** PII type options grouped by category for the multi-select. */
  readonly piiTypeGroups = computed<PiiTypeGroup[]>(() => {
    const byCategory = new Map<string, Map<string, PiiTypeOption>>();
    for (const cfg of this.piiTypeConfigs()) {
      const category = cfg.category || 'CUSTOM';
      const group = byCategory.get(category) ?? new Map<string, PiiTypeOption>();
      if (!group.has(cfg.piiType)) {
        group.set(cfg.piiType, {
          code: cfg.piiType,
          labelKey: `settings.piiTypes.typeNames.${cfg.piiType}`,
          detector: cfg.detector
        });
      }
      byCategory.set(category, group);
    }
    return Array.from(byCategory.entries()).map(([category, items]) => ({
      category,
      labelKey: `settings.piiTypes.categoryNames.${category}`,
      items: Array.from(items.values())
    }));
  });

  /** Available status options for filtering (delegates to utils). */
  readonly statusOptions = computed(() => this.spacesDashboardUtils.statusOptions());

  /** Severity filter options (Critique / Modéré / Faible). */
  readonly severityOptions: ReadonlyArray<{ labelKey: string; value: SeverityFilterValue }> = [
    { labelKey: 'dashboard.filters.severityCritical', value: 'HIGH' },
    { labelKey: 'dashboard.filters.severityModerate', value: 'MEDIUM' },
    { labelKey: 'dashboard.filters.severityLow', value: 'LOW' }
  ];

  // ===== Pipeline: filteredSpaces -> searchedSpaces -> sortedSpaces =====

  /**
   * Spaces matching the 3-axis filters (OR within a category, AND across
   * categories) plus the optional "modified only" toggle.
   */
  readonly filteredSpaces = computed<UISpace[]>(() => {
    const piiTypes = this.piiTypeFilter();
    const severities = this.severityFilter();
    const statuses = this.statusFilter();
    let spaces = this.spacesDashboardUtils.allSpaces().filter(s =>
      this.matchesPiiTypes(s, piiTypes)
      && this.matchesSeverities(s, severities)
      && this.matchesStatuses(s, statuses)
    );

    if (this.modifiedOnlyFilter()) {
      const updateInfos = this.dataManagement.spacesUpdateInfo();
      spaces = spaces.filter(s => {
        const info = updateInfos.find(i => i.spaceKey === s.key);
        return info?.hasBeenUpdated ?? false;
      });
    }
    return spaces;
  });

  /** Filtered spaces narrowed by the global text search (name and key). */
  readonly searchedSpaces = computed<UISpace[]>(() => {
    const term = (this.globalFilter() ?? '').trim().toLowerCase();
    if (!term) {
      return this.filteredSpaces();
    }
    return this.filteredSpaces().filter(s =>
      (s.name ?? '').toLowerCase().includes(term) || s.key.toLowerCase().includes(term)
    );
  });

  /** Final, sorted list bound to the table. */
  readonly sortedSpaces = computed<UISpace[]>(() => {
    const criterion = this.sortCriterion();
    const order = this.sortOrder();
    const spaces = this.applyPiiTypeSortVisibility(this.searchedSpaces(), criterion);
    if (!criterion) {
      return spaces;
    }
    return [...spaces].sort((a, b) => this.compareSpaces(a, b, criterion, order));
  });

  // ===== Facets (bi-level, recomputed on every interaction) =====

  /** PII type facet counts computed against the other axes (type axis excluded). */
  readonly piiTypeFacetCounts = computed<Record<string, FacetCount>>(() => {
    const context = this.spacesForFacet('piiType');
    const result: Record<string, FacetCount> = {};
    for (const group of this.piiTypeGroups()) {
      for (const option of group.items) {
        result[option.code] = this.countByPiiType(context, option.code);
      }
    }
    return result;
  });

  /** Severity facet counts computed against the other axes (severity axis excluded). */
  readonly severityFacetCounts = computed<Record<string, FacetCount>>(() => {
    const context = this.spacesForFacet('severity');
    const result: Record<string, FacetCount> = {};
    for (const option of this.severityOptions) {
      result[option.value] = this.countBySeverity(context, option.value);
    }
    return result;
  });

  /** Status facet counts computed against the other axes (status axis excluded). */
  readonly statusFacetCounts = computed<Record<string, FacetCount>>(() => {
    const context = this.spacesForFacet('status');
    const result: Record<string, FacetCount> = {};
    for (const option of this.statusOptions()) {
      result[option.value] = this.countByStatus(context, option.value);
    }
    return result;
  });

  /** Total number of spaces available (unfiltered). */
  readonly totalSpacesCount = computed<number>(() => this.spacesDashboardUtils.allSpaces().length);

  /** True when any filter, search or sort is active. */
  readonly isResettable = computed<boolean>(() =>
    (this.globalFilter() ?? '').trim().length > 0
    || this.piiTypeFilter().length > 0
    || this.severityFilter().length > 0
    || this.statusFilter().length > 0
    || this.sortCriterion() != null
    || this.modifiedOnlyFilter()
  );

  // ===== Public API (preserved + extended) =====

  /** Updates the global search filter and keeps SpacesDashboardUtils in sync. */
  onGlobalChange(value: string): void {
    this.globalFilter.set(value);
    this.spacesDashboardUtils.globalFilter.set(value);
  }

  /** Updates a single legacy filter field (status migrates to multi). */
  onFilter(field: 'name' | 'status', value: string | null | undefined): void {
    if (field === 'status') {
      this.statusFilter.set(value ? [value] : []);
    }
    this.spacesDashboardUtils.onFilter(field, value);
  }

  /** Toggles the "Modified Only" filter. */
  onModifiedOnlyChange(value: boolean): void {
    this.modifiedOnlyFilter.set(value);
  }

  /**
   * Handles a PrimeNG header sort event by mapping its field to a criterion.
   * Maps the legacy 'piiCount' header to the 'severityScore' criterion.
   */
  onCustomSort(event: SortEvent): void {
    if (!event.field) {
      this.sortCriterion.set(null);
      this.sortField.set(null);
      this.sortOrder.set(1);
      return;
    }
    this.sortField.set(event.field);
    this.sortOrder.set(event.order ?? 1);
    this.sortCriterion.set(event.field === 'piiCount' ? 'severityScore' : event.field);
  }

  /** Sets the active sort criterion (and resets the legacy field). */
  setSortCriterion(criterion: string | null, order: number = -1): void {
    this.sortCriterion.set(criterion);
    this.sortOrder.set(order);
    this.sortField.set(null);
  }

  /** Toggles the ascending/descending order. */
  toggleSortOrder(): void {
    this.sortOrder.update(o => (o === 1 ? -1 : 1));
  }

  /** Resets all filter, search and sort state. */
  reset(): void {
    this.globalFilter.set('');
    this.piiTypeFilter.set([]);
    this.severityFilter.set([]);
    this.statusFilter.set([]);
    this.modifiedOnlyFilter.set(false);
    this.sortCriterion.set(null);
    this.sortField.set(null);
    this.sortOrder.set(1);
    this.spacesDashboardUtils.globalFilter.set('');
    this.spacesDashboardUtils.onFilter('status', null);
  }

  // ===== Matching helpers (one axis each) =====

  private matchesPiiTypes(space: UISpace, selected: string[]): boolean {
    if (selected.length === 0) {
      return true;
    }
    const counts = space.piiTypeCounts ?? {};
    return selected.some(type => (counts[type] ?? 0) > 0);
  }

  private matchesSeverities(space: UISpace, selected: string[]): boolean {
    if (selected.length === 0) {
      return true;
    }
    return selected.some(sev => {
      const bucket = SEVERITY_BUCKET[sev as SeverityFilterValue];
      return bucket != null && (space.counts?.[bucket] ?? 0) > 0;
    });
  }

  private matchesStatuses(space: UISpace, selected: string[]): boolean {
    return selected.length === 0 || selected.includes(space.status ?? '');
  }

  // ===== Sorting helpers =====

  /** Hides spaces with zero occurrences of T when sorting by 'piiType:<T>'. */
  private applyPiiTypeSortVisibility(spaces: UISpace[], criterion: string | null): UISpace[] {
    if (!criterion?.startsWith(SORT_PII_TYPE_PREFIX)) {
      return spaces;
    }
    const type = criterion.slice(SORT_PII_TYPE_PREFIX.length);
    return spaces.filter(s => (s.piiTypeCounts?.[type] ?? 0) > 0);
  }

  private compareSpaces(a: UISpace, b: UISpace, criterion: string, order: number): number {
    const raw = this.compareByCriterion(a, b, criterion);
    if (raw !== 0) {
      return raw * order;
    }
    // Stable fallback on the backend-provided original order.
    return (a.originalIndex ?? 0) - (b.originalIndex ?? 0);
  }

  /**
   * Natural ASCENDING comparator for a criterion. Direction is applied by the
   * caller via sortOrder (1 ascending, -1 descending). The default order chosen
   * in setSortCriterion makes numeric/score criteria descending out of the box.
   */
  private compareByCriterion(a: UISpace, b: UISpace, criterion: string): number {
    if (criterion.startsWith(SORT_PII_TYPE_PREFIX)) {
      const type = criterion.slice(SORT_PII_TYPE_PREFIX.length);
      return (a.piiTypeCounts?.[type] ?? 0) - (b.piiTypeCounts?.[type] ?? 0);
    }
    if (criterion === 'name') {
      return (a.name ?? '').localeCompare(b.name ?? '');
    }
    if (criterion === 'totalDetections') {
      return (a.counts?.total ?? 0) - (b.counts?.total ?? 0);
    }
    if (criterion === 'severityScore') {
      return this.severityScore(a) - this.severityScore(b);
    }
    if (criterion === 'lastScan') {
      return (a.lastScanTs ?? '').localeCompare(b.lastScanTs ?? '');
    }
    return 0;
  }

  /** Derived severity score weighted high >> medium >> low. */
  private severityScore(space: UISpace): number {
    const c = space.counts ?? { high: 0, medium: 0, low: 0, total: 0 };
    return c.high * SEVERITY_SCORE_WEIGHTS.high
      + c.medium * SEVERITY_SCORE_WEIGHTS.medium
      + c.low * SEVERITY_SCORE_WEIGHTS.low;
  }

  // ===== Facet helpers =====

  /**
   * Spaces filtered by every axis EXCEPT the given one, so a facet reflects the
   * current context without constraining itself.
   */
  private spacesForFacet(exclude: 'piiType' | 'severity' | 'status'): UISpace[] {
    const piiTypes = exclude === 'piiType' ? [] : this.piiTypeFilter();
    const severities = exclude === 'severity' ? [] : this.severityFilter();
    const statuses = exclude === 'status' ? [] : this.statusFilter();
    return this.spacesDashboardUtils.allSpaces().filter(s =>
      this.matchesPiiTypes(s, piiTypes)
      && this.matchesSeverities(s, severities)
      && this.matchesStatuses(s, statuses)
    );
  }

  private countByPiiType(spaces: UISpace[], type: string): FacetCount {
    let nbSpaces = 0;
    let totalOccurrences = 0;
    for (const s of spaces) {
      const count = s.piiTypeCounts?.[type] ?? 0;
      if (count > 0) {
        nbSpaces++;
        totalOccurrences += count;
      }
    }
    return { nbSpaces, totalOccurrences };
  }

  private countBySeverity(spaces: UISpace[], severity: SeverityFilterValue): FacetCount {
    const bucket = SEVERITY_BUCKET[severity];
    let nbSpaces = 0;
    let totalOccurrences = 0;
    for (const s of spaces) {
      const count = s.counts?.[bucket] ?? 0;
      if (count > 0) {
        nbSpaces++;
        totalOccurrences += count;
      }
    }
    return { nbSpaces, totalOccurrences };
  }

  private countByStatus(spaces: UISpace[], status: string): FacetCount {
    const matching = spaces.filter(s => s.status === status);
    const totalOccurrences = matching.reduce((sum, s) => sum + (s.counts?.total ?? 0), 0);
    return { nbSpaces: matching.length, totalOccurrences };
  }
}
