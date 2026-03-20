import { computed, inject, Injectable, signal } from '@angular/core';
import { Observable, throwError } from 'rxjs';
import { tap, map, catchError, finalize } from 'rxjs/operators';
import { SentinelleApiService, LastScanMeta, SpaceScanStateDto } from '../../../core/services/sentinelle-api.service';
import { JiraProject } from '../../../core/models/jira-project.model';
import { JiraProjectsDashboardUtils } from '../jira-projects-dashboard.utils';
import { ScanProgressService } from '../../../core/services/scan-progress.service';
import { JiraPiiItemsStorageService } from './jira-pii-items-storage.service';
import { JiraDashboardUiStateService } from './jira-dashboard-ui-state.service';
import { TranslocoService } from '@jsverse/transloco';

@Injectable({
  providedIn: 'root'
})
export class JiraProjectDataManagementService {
  private readonly sentinelleApiService = inject(SentinelleApiService);
  private readonly dashboardUtils = inject(JiraProjectsDashboardUtils);
  private readonly scanProgressService = inject(ScanProgressService);
  private readonly piiItemsStorage = inject(JiraPiiItemsStorageService);
  private readonly uiStateService = inject(JiraDashboardUiStateService);
  private readonly translocoService = inject(TranslocoService);

  readonly projects = signal<JiraProject[]>([]);
  readonly queue = signal<string[]>([]);
  readonly isProjectsLoading = signal<boolean>(true);

  readonly lastScanMeta = signal<LastScanMeta | null>(null);
  readonly lastProjectStatuses = signal<SpaceScanStateDto[]>([]);

  readonly lastRefresh = signal<Date | null>(null);
  readonly isRefreshing = signal<boolean>(false);

  readonly canStartScan = computed(() =>
    !this.isProjectsLoading() && this.projects().length > 0
  );

  fetchProjects(): Observable<void> {
    this.isProjectsLoading.set(true);
    this.isRefreshing.set(true);

    return this.sentinelleApiService.getJiraProjects().pipe(
      tap((projects) => {
        this.projects.set(projects);
        this.queue.set(projects.map((p) => p.key));
        this.dashboardUtils.setProjects(projects);
        this.lastRefresh.set(new Date());
      }),
      map(() => void 0 as void),
      catchError((e) => {
        this.uiStateService.append(
          this.translocoService.translate('dashboard.logs.fetchProjectsError', {
            error: e?.message ?? e
          })
        );
        return throwError(() => e);
      }),
      finalize(() => {
        this.isProjectsLoading.set(false);
        this.isRefreshing.set(false);
      })
    );
  }

  refreshProjects(): void {
    this.fetchProjects().subscribe();
  }

  reset(): void {
    this.projects.set([]);
    this.queue.set([]);
    this.lastScanMeta.set(null);
    this.lastProjectStatuses.set([]);
    this.lastRefresh.set(null);
  }
}
