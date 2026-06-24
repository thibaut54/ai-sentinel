import { effect, inject, Injectable } from '@angular/core';
import { ActivatedRoute, Params, Router } from '@angular/router';
import { SpaceFilteringService } from './space-filtering.service';

/**
 * Persists the dashboard filter / search / sort state in the URL query string
 * and restores it on load.
 *
 * Compact, stable schema (empty axes omit their param):
 *   piiTypes=EMAIL,PHONE&sev=HIGH&status=COMPLETED&sort=lastScan:desc&q=text
 *
 * - piiTypes : comma-separated PII type codes
 * - sev      : comma-separated severity values (HIGH|MEDIUM|LOW)
 * - status   : comma-separated status values
 * - sort     : "<criterion>:<asc|desc>"
 * - q        : free-text search
 *
 * SOLID: single responsibility (URL <-> filter-state synchronization).
 */
@Injectable({
  providedIn: 'root'
})
export class FilterUrlStateService {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly filtering = inject(SpaceFilteringService);

  /** Guards against the URL->state->URL feedback loop. */
  private syncing = false;

  constructor() {
    this.hydrateFromUrl();
    this.registerUrlWriter();
  }

  /** Reads the current query params and applies them to the filter signals. */
  private hydrateFromUrl(): void {
    const map = this.route.snapshot.queryParamMap;
    this.syncing = true;
    try {
      this.filtering.piiTypeFilter.set(this.parseList(map.get('piiTypes')));
      this.filtering.severityFilter.set(this.parseList(map.get('sev')));
      this.filtering.statusFilter.set(this.parseList(map.get('status')));
      this.filtering.onGlobalChange(map.get('q') ?? '');
      this.applySort(map.get('sort'));
    } finally {
      this.syncing = false;
    }
  }

  /** Mirrors the filter state into the URL on every change (loop-guarded). */
  private registerUrlWriter(): void {
    effect(() => {
      const next = this.buildQueryParams();
      if (this.syncing) {
        return;
      }
      void this.router.navigate([], {
        relativeTo: this.route,
        queryParams: next,
        queryParamsHandling: 'merge',
        replaceUrl: true
      });
    });
  }

  /** Builds the query-param object; empty axes are nulled to drop them. */
  private buildQueryParams(): Params {
    const piiTypes = this.filtering.piiTypeFilter();
    const sev = this.filtering.severityFilter();
    const status = this.filtering.statusFilter();
    const q = (this.filtering.globalFilter() ?? '').trim();
    const criterion = this.filtering.sortCriterion();
    const order = this.filtering.sortOrder() === 1 ? 'asc' : 'desc';
    return {
      piiTypes: piiTypes.length > 0 ? piiTypes.join(',') : null,
      sev: sev.length > 0 ? sev.join(',') : null,
      status: status.length > 0 ? status.join(',') : null,
      sort: criterion ? `${criterion}:${order}` : null,
      q: q.length > 0 ? q : null
    };
  }

  private applySort(raw: string | null): void {
    if (!raw) {
      return;
    }
    const [criterion, order] = raw.split(':');
    if (criterion) {
      this.filtering.setSortCriterion(criterion, order === 'asc' ? 1 : -1);
    }
  }

  private parseList(raw: string | null): string[] {
    if (!raw) {
      return [];
    }
    return raw.split(',').map(v => v.trim()).filter(v => v.length > 0);
  }
}
