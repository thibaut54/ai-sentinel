import { computed, Injectable, inject, signal } from '@angular/core';
import { SortEvent } from 'primeng/api';
import { SharePointSitesDashboardUtils } from '../sharepoint-sites-dashboard.utils';

@Injectable({
  providedIn: 'root'
})
export class SharePointSiteFilteringService {
  private readonly dashboardUtils = inject(SharePointSitesDashboardUtils);

  readonly globalFilter = signal<string>('');
  readonly statusFilter = signal<string | null>(null);

  readonly sortField = signal<string | null>(null);
  readonly sortOrder = signal<number>(1);

  readonly filteredSites = computed(() => {
    return this.dashboardUtils.filteredSites();
  });

  readonly sortedSites = computed(() => {
    const sites = [...this.filteredSites()];
    const field = this.sortField();
    const order = this.sortOrder();

    if (!field) {
      return sites;
    }

    return sites.sort((a, b) => {
      let compareValue = 0;

      if (field === 'name') {
        const idxA = a.originalIndex ?? 0;
        const idxB = b.originalIndex ?? 0;
        compareValue = idxA - idxB;
      } else if (field === 'piiCount') {
        const priorities = ['high', 'medium', 'low'] as const;
        for (const priority of priorities) {
          const countA = a.counts?.[priority] ?? 0;
          const countB = b.counts?.[priority] ?? 0;
          if (countA !== countB) {
            compareValue = countB - countA;
            break;
          }
        }
      }

      return compareValue * order;
    });
  });

  readonly statusOptions = computed(() => {
    return this.dashboardUtils.statusOptions();
  });

  onGlobalChange(value: string): void {
    this.globalFilter.set(value);
    this.dashboardUtils.globalFilter.set(value);
  }

  onFilter(field: 'name' | 'status', value: string | null | undefined): void {
    if (field === 'status') {
      this.statusFilter.set(value ?? null);
    }
    this.dashboardUtils.onFilter(field, value);
  }

  onCustomSort(event: SortEvent): void {
    if (!event.field) {
      this.sortField.set(null);
      this.sortOrder.set(1);
      return;
    }
    this.sortField.set(event.field);
    this.sortOrder.set(event.order ?? 1);
  }

  reset(): void {
    this.globalFilter.set('');
    this.statusFilter.set(null);
    this.sortField.set(null);
    this.sortOrder.set(1);
    this.dashboardUtils.globalFilter.set('');
    this.onFilter('status', null);
  }
}
