import { computed, Injectable, signal } from '@angular/core';
import { SharePointSite, UISharePointSite } from '../../core/models/sharepoint-site.model';
import { SeverityCounts } from '../../core/models/severity-counts';

@Injectable({ providedIn: 'root' })
export class SharePointSitesDashboardUtils {

  private readonly uiSites = signal<UISharePointSite[]>([]);

  readonly globalFilter = signal<string>('');
  private readonly filters = signal<{ name?: string; status?: string | null }>({});

  readonly statusOptions = signal<Array<{ labelKey: string; value: string }>>([
    { labelKey: 'dashboard.status.pending', value: 'PENDING' },
    { labelKey: 'dashboard.status.paused', value: 'PAUSED' },
    { labelKey: 'dashboard.status.running', value: 'RUNNING' },
    { labelKey: 'dashboard.status.ok', value: 'OK' },
    { labelKey: 'dashboard.status.failed', value: 'FAILED' }
  ]);

  readonly filteredSites = computed(() => {
    const g = (this.globalFilter() ?? '').toLowerCase();
    const { name = '', status = null } = this.filters();
    return this.uiSites().filter(s =>
      (g ? (s.name ?? '').toLowerCase().includes(g) || s.id.toLowerCase().includes(g) : true) &&
      (name ? (s.name ?? '').toLowerCase().includes(name.toLowerCase()) : true) &&
      (status ? s.status === status : true)
    );
  });

  setSites(sites: SharePointSite[] | null | undefined): void {
    const mapped: UISharePointSite[] = (sites ?? []).map<UISharePointSite>((s, idx) => ({
      ...s,
      status: 'PENDING',
      lastScanTs: undefined,
      counts: { total: 0, high: 0, medium: 0, low: 0 },
      originalIndex: idx
    }));
    this.uiSites.set(mapped);
  }

  updateSite(siteId: string, patch: Partial<UISharePointSite>): void {
    const list = this.uiSites();
    const nk = (v: string | null | undefined) => String(v ?? '').trim().toLowerCase();
    const k = nk(siteId);
    const updated = list.map(s => (nk(s.id) === k ? { ...s, ...patch } : s));
    this.uiSites.set(updated);
  }

  onFilter(field: 'name' | 'status', value: string | null = null): void {
    this.filters.set({ ...this.filters(), [field]: value });
  }

  getSiteCounts(siteId: string): SeverityCounts {
    const nk = (v: string | null | undefined) => String(v ?? '').trim().toLowerCase();
    const k = nk(siteId);
    const site = this.uiSites().find(s => nk(s.id) === k);
    return site?.counts ?? { total: 0, high: 0, medium: 0, low: 0 };
  }
}
