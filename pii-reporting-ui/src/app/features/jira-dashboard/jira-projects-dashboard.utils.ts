import { computed, Injectable, signal } from '@angular/core';
import { JiraProject } from '../../core/models/jira-project.model';
import { SeverityCounts } from '../../core/models/severity-counts';

export interface UIJiraProject extends JiraProject {
  status?: 'FAILED' | 'RUNNING' | 'OK' | 'PENDING' | 'INTERRUPTED' | 'PAUSED';
  lastScanTs?: string;
  counts?: SeverityCounts;
  originalIndex: number;
}

@Injectable({ providedIn: 'root' })
export class JiraProjectsDashboardUtils {

  private readonly uiProjects = signal<UIJiraProject[]>([]);

  readonly globalFilter = signal<string>('');
  private readonly filters = signal<{ name?: string; status?: string | null }>({});

  readonly statusOptions = signal<Array<{ labelKey: string; value: string }>>([
    { labelKey: 'dashboard.status.pending', value: 'PENDING' },
    { labelKey: 'dashboard.status.paused', value: 'PAUSED' },
    { labelKey: 'dashboard.status.running', value: 'RUNNING' },
    { labelKey: 'dashboard.status.ok', value: 'OK' },
    { labelKey: 'dashboard.status.failed', value: 'FAILED' }
  ]);

  readonly filteredProjects = computed(() => {
    const g = (this.globalFilter() ?? '').toLowerCase();
    const { name = '', status = null } = this.filters();
    return this.uiProjects().filter(p =>
      (g ? (p.name ?? '').toLowerCase().includes(g) || p.key.toLowerCase().includes(g) : true) &&
      (name ? (p.name ?? '').toLowerCase().includes(name.toLowerCase()) : true) &&
      (status ? p.status === status : true)
    );
  });

  setProjects(projects: JiraProject[] | null | undefined): void {
    const mapped: UIJiraProject[] = (projects ?? []).map<UIJiraProject>((p, idx) => ({
      ...p,
      status: 'PENDING',
      lastScanTs: undefined,
      counts: { total: 0, high: 0, medium: 0, low: 0 },
      url: p.url,
      originalIndex: idx
    }));
    this.uiProjects.set(mapped);
  }

  updateProject(key: string, patch: Partial<UIJiraProject>): void {
    const list = this.uiProjects();
    const nk = (v: string | null | undefined) => String(v ?? '').trim().toLowerCase();
    const k = nk(key);
    const updated = list.map(p => (nk(p.key) === k ? { ...p, ...patch } : p));
    this.uiProjects.set(updated);
  }

  onFilter(field: 'name' | 'status', value: string | null = null): void {
    this.filters.set({ ...this.filters(), [field]: value });
  }

  getProjectCounts(key: string): SeverityCounts {
    const nk = (v: string | null | undefined) => String(v ?? '').trim().toLowerCase();
    const k = nk(key);
    const project = this.uiProjects().find(p => nk(p.key) === k);
    return project?.counts ?? { total: 0, high: 0, medium: 0, low: 0 };
  }
}
