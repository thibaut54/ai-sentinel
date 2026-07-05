import { Injectable, signal } from '@angular/core';
import {
  ObfuscationPlanDto,
  RemediationFindingsSearchResponse,
  RemediationGroupBy,
  RemediationStatusFilter
} from '../../../core/models/remediation.model';

/**
 * Pure UI state for the obfuscation view: grouping axis, open accordions,
 * pagination, filters and the last backend response, stored verbatim.
 */
@Injectable()
export class ObfuscationViewStateService {
  readonly groupBy = signal<RemediationGroupBy>('type');
  readonly openAccordions = signal<ReadonlySet<string>>(new Set());
  readonly page = signal(0);
  readonly pageSize = signal(20);
  readonly statusFilter = signal<RemediationStatusFilter>('ALL');
  readonly searchText = signal('');
  readonly itemFilter = signal<string | null>(null);
  readonly lastSearchResponse = signal<RemediationFindingsSearchResponse | null>(null);
  readonly lastPlan = signal<ObfuscationPlanDto | null>(null);
  readonly loading = signal(false);

  toggleAccordion(key: string): void {
    this.openAccordions.update((open) => {
      const next = new Set(open);
      if (next.has(key)) {
        next.delete(key);
      } else {
        next.add(key);
      }
      return next;
    });
  }

  openAll(keys: string[]): void {
    this.openAccordions.set(new Set(keys));
  }

  collapseAll(): void {
    this.openAccordions.set(new Set());
  }
}
