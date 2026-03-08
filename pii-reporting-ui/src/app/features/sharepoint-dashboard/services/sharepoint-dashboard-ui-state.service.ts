import { computed, inject, Injectable, signal } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';
import { HistoryEntry } from '../../../core/models/history-entry';

@Injectable({
  providedIn: 'root'
})
export class SharePointDashboardUiStateService {
  private readonly translocoService = inject(TranslocoService);

  readonly expandedRowKeys = signal<Record<string, boolean>>({});
  readonly selectedSiteId = signal<string | null>(null);
  readonly selectedSites = signal<{ id: string }[]>([]);
  readonly activeSiteId = signal<string | null>(null);
  readonly maskByDefault = signal(true);
  readonly lines = signal<string[]>([]);
  readonly history = signal<HistoryEntry[]>([]);

  readonly selectedSitesCount = computed(() => {
    return this.selectedSites().length;
  });

  onRowExpand(event: { data?: { id?: string } }): void {
    const id = event?.data?.id;
    if (!id) return;
    const map = this.expandedRowKeys();
    if (!map[id]) {
      this.expandedRowKeys.set({ ...map, [id]: true });
    }
    this.selectedSiteId.set(id);
  }

  onRowCollapse(event: { data?: { id?: string } }): void {
    const id = event?.data?.id;
    if (!id) return;
    const map = { ...this.expandedRowKeys() };
    if (map[id]) {
      delete map[id];
      this.expandedRowKeys.set(map);
    }
  }

  collapseAllRows(): void {
    this.expandedRowKeys.set({});
  }

  selectSite(siteId: string | null): void {
    this.selectedSiteId.set(siteId);
  }

  append(line: string): void {
    const next = [...this.lines(), line];
    if (next.length > 1000) {
      next.splice(0, next.length - 1000);
    }
    this.lines.set(next);
  }

  clearHistory(): void {
    this.history.set([]);
  }

  upsertScanHistory(siteId: string, status: 'running' | 'completed' | 'failed'): void {
    const index = this.history().findIndex((h) => h.spaceKey === siteId);
    if (index >= 0) {
      const copy = [...this.history()];
      copy[index] = { ...copy[index], status };
      this.history.set(copy);
      return;
    }
    this.history.set([...this.history(), { spaceKey: siteId, status }]);
  }

  statusLabel(status?: string): string {
    return this.translocoService.translate(this.getStatusKey(status));
  }

  private getStatusKey(status?: string): string {
    switch (status?.toUpperCase()) {
      case 'RUNNING': return 'dashboard.status.running';
      case 'OK': return 'dashboard.status.ok';
      case 'FAILED': return 'dashboard.status.failed';
      case 'PENDING': return 'dashboard.status.pending';
      case 'PAUSED': return 'dashboard.status.paused';
      default: return 'dashboard.status.pending';
    }
  }

  statusStyle(status?: string): 'danger' | 'warning' | 'success' | 'info' | 'secondary' {
    if (status === 'FAILED') return 'danger';
    if (status === 'RUNNING') return 'warning';
    if (status === 'PAUSED') return 'info';
    if (status === 'PENDING' || !status) return 'secondary';
    return 'success';
  }

  reset(): void {
    this.expandedRowKeys.set({});
    this.selectedSiteId.set(null);
    this.activeSiteId.set(null);
    this.selectedSites.set([]);
    this.lines.set([]);
    this.history.set([]);
  }
}
