import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { InputTextModule } from 'primeng/inputtext';
import { MultiSelectModule } from 'primeng/multiselect';
import { ButtonModule } from 'primeng/button';
import { MenuModule } from 'primeng/menu';
import { toSignal } from '@angular/core/rxjs-interop';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import { MenuItem } from 'primeng/api';
import {
  FacetCount,
  SeverityFilterValue,
  SpaceFilteringService
} from '../../../features/confluence-dashboard/services/space-filtering.service';

/** View-model for a single selectable PII type option (with facet + disabled state). */
interface PiiTypeOptionVm {
  code: string;
  labelKey: string;
  detectorLabel: string;
  facet: FacetCount;
  disabled: boolean;
}

/** View-model for a grouped category of PII type options. */
interface PiiTypeGroupVm {
  labelKey: string;
  items: PiiTypeOptionVm[];
}

/** View-model for a severity or status option (with facet + disabled state). */
interface SimpleOptionVm {
  value: string;
  labelKey: string;
  facet: FacetCount;
  disabled: boolean;
}

/**
 * Dashboard filter bar: 3-axis client-side filtering (PII type / severity /
 * status), a sort menu and a global text search.
 *
 * Design decision: it injects the providedIn:'root' SpaceFilteringService
 * directly (the single source of truth) instead of @Input/@Output plumbing,
 * which is far simpler for this many axes.
 */
@Component({
  selector: 'app-space-filters',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    InputTextModule,
    MultiSelectModule,
    ButtonModule,
    MenuModule,
    TranslocoModule
  ],
  templateUrl: './space-filters.component.html',
  styleUrls: ['./space-filters.component.css'],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class SpaceFiltersComponent {
  protected readonly filtering = inject(SpaceFilteringService);
  private readonly transloco = inject(TranslocoService);

  /** Active language signal so the sort menu re-translates on language change. */
  private readonly activeLang = toSignal(this.transloco.langChanges$, {
    initialValue: this.transloco.getActiveLang()
  });

  /** PII type groups enriched with facet counts and disabled state. */
  readonly piiTypeGroupVms = computed<PiiTypeGroupVm[]>(() => {
    const facets = this.filtering.piiTypeFacetCounts();
    return this.filtering.piiTypeGroups().map(group => ({
      labelKey: group.labelKey,
      items: group.items.map(item => {
        const facet = facets[item.code] ?? { nbSpaces: 0, totalOccurrences: 0 };
        return { ...item, facet, disabled: facet.nbSpaces === 0 };
      })
    }));
  });

  /** Severity options enriched with facet counts and disabled state. */
  readonly severityOptionVms = computed<SimpleOptionVm[]>(() => {
    const facets = this.filtering.severityFacetCounts();
    return this.filtering.severityOptions.map(o => {
      const facet = facets[o.value] ?? { nbSpaces: 0, totalOccurrences: 0 };
      return { value: o.value, labelKey: o.labelKey, facet, disabled: facet.nbSpaces === 0 };
    });
  });

  /** Status options enriched with facet counts and disabled state. */
  readonly statusOptionVms = computed<SimpleOptionVm[]>(() => {
    const facets = this.filtering.statusFacetCounts();
    return this.filtering.statusOptions().map(o => {
      const facet = facets[o.value] ?? { nbSpaces: 0, totalOccurrences: 0 };
      return { value: o.value, labelKey: o.labelKey, facet, disabled: facet.nbSpaces === 0 };
    });
  });

  /** Number of spaces shown after filtering/search/sort. */
  readonly shownCount = computed(() => this.filtering.sortedSpaces().length);

  /** Total number of spaces available. */
  readonly totalCount = computed(() => this.filtering.totalSpacesCount());

  /** Sort menu model (space attributes + per-type section). */
  readonly sortMenuItems = computed<MenuItem[]>(() => {
    // Read the active language so the menu re-translates on language change.
    this.activeLang();
    const attributeItems: MenuItem[] = [
      this.sortMenuItem('dashboard.filters.sort.totalDetections', 'totalDetections'),
      this.sortMenuItem('dashboard.filters.sort.severityScore', 'severityScore'),
      this.sortMenuItem('dashboard.filters.sort.lastScan', 'lastScan'),
      this.sortMenuItem('dashboard.filters.sort.name', 'name')
    ];
    const typeItems: MenuItem[] = this.filtering.piiTypeGroups().flatMap(g =>
      g.items.map(i => ({
        label: i.detectorLabel,
        command: () => this.filtering.setSortCriterion(`piiType:${i.code}`)
      }))
    );
    return [
      { label: this.transloco.translate('dashboard.filters.sort.byAttribute'), items: attributeItems },
      { label: this.transloco.translate('dashboard.filters.sort.byType'), items: typeItems }
    ];
  });

  /** Updates the global search term. */
  onSearchChange(value: string): void {
    this.filtering.onGlobalChange(value);
  }

  /** Updates the selected PII types. */
  onPiiTypeChange(values: string[]): void {
    this.filtering.piiTypeFilter.set(values ?? []);
  }

  /** Updates the selected severities. */
  onSeverityChange(values: string[]): void {
    this.filtering.severityFilter.set(values ?? []);
  }

  /** Updates the selected statuses. */
  onStatusChange(values: string[]): void {
    this.filtering.statusFilter.set(values ?? []);
  }

  /** Clears the PII type axis only. */
  clearPiiType(): void {
    this.filtering.piiTypeFilter.set([]);
  }

  /** Clears the severity axis only. */
  clearSeverity(): void {
    this.filtering.severityFilter.set([]);
  }

  /** Clears the status axis only. */
  clearStatus(): void {
    this.filtering.statusFilter.set([]);
  }

  /** Toggles the ascending/descending sort order. */
  toggleSortOrder(): void {
    this.filtering.toggleSortOrder();
  }

  /** Resets every filter, search and sort. */
  resetAll(): void {
    this.filtering.reset();
  }

  private sortMenuItem(labelKey: string, criterion: string): MenuItem {
    return {
      label: this.transloco.translate(labelKey),
      command: () => this.filtering.setSortCriterion(criterion)
    };
  }
}
