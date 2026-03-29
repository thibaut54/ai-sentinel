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
import { coerceSpaceKey } from '../../confluence-dashboard/spaces-dashboard-stream.utils';

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
        this.reapplyLastScanUi();
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

  loadLastScan(): Observable<void> {
    return new Observable(observer => {
      this.sentinelleApiService.getJiraLastScanMeta().subscribe({
        next: (meta) => {
          this.lastScanMeta.set(meta);
          observer.next();
          observer.complete();
        },
        error: () => {
          this.lastScanMeta.set(null);
          observer.complete();
        }
      });
    });
  }

  loadLastProjectStatuses(isActive: boolean, alsoLoadItems: boolean = true): Observable<void> {
    return new Observable(observer => {
      this.sentinelleApiService.getJiraDashboardSummary().subscribe({
        next: (summary) => {
          if (!summary) {
            this.lastProjectStatuses.set([]);
            observer.next();
            observer.complete();
            return;
          }

          const projectKeys = new Set(this.projects().map(p => p.key.trim().toLowerCase()));

          const spaceScanStateList = summary.spaces
            .filter(space => projectKeys.has(space.spaceKey.trim().toLowerCase()))
            .map(space => ({
              spaceKey: space.spaceKey,
              status: space.status,
              pagesDone: space.pagesDone,
              attachmentsDone: space.attachmentsDone,
              lastEventTs: space.lastEventTs,
              progressPercentage: space.progressPercentage ?? undefined
            }));

          this.lastProjectStatuses.set(spaceScanStateList);

          for (const spaceSummary of summary.spaces) {
            if (!projectKeys.has(spaceSummary.spaceKey.trim().toLowerCase())) continue;
            if (isActive && spaceSummary.status !== 'COMPLETED') continue;

            const uiStatus = this.computeUiStatus(
              { spaceKey: spaceSummary.spaceKey, status: spaceSummary.status, pagesDone: spaceSummary.pagesDone, attachmentsDone: spaceSummary.attachmentsDone, lastEventTs: spaceSummary.lastEventTs, progressPercentage: spaceSummary.progressPercentage ?? undefined },
              isActive
            );

            this.dashboardUtils.updateProject(spaceSummary.spaceKey, {
              status: uiStatus,
              lastScanTs: spaceSummary.lastEventTs
            });

            if (spaceSummary.status === 'COMPLETED') {
              this.scanProgressService.updateProgress(spaceSummary.spaceKey, { percent: 100 });
            } else if (spaceSummary.progressPercentage != null) {
              this.scanProgressService.updateProgress(spaceSummary.spaceKey, {
                percent: spaceSummary.progressPercentage
              });
            }

            const counts = spaceSummary.severityCounts ?? { high: 0, medium: 0, low: 0, total: 0 };
            this.dashboardUtils.updateProject(spaceSummary.spaceKey, { counts });
          }

          if (alsoLoadItems) {
            this.loadLastItems().subscribe({
              next: () => { observer.next(); observer.complete(); },
              error: () => { observer.next(); observer.complete(); }
            });
          } else {
            observer.next();
            observer.complete();
          }
        },
        error: () => {
          this.lastProjectStatuses.set([]);
          observer.complete();
        }
      });
    });
  }

  private computeUiStatus(
    state: SpaceScanStateDto,
    isActive: boolean
  ): 'FAILED' | 'RUNNING' | 'OK' | 'PENDING' | 'PAUSED' | undefined {
    if (state.status === 'COMPLETED') return 'OK';
    if (state.status === 'FAILED') return 'FAILED';
    if (state.status === 'PENDING') return 'PAUSED';
    if (state.status === 'RUNNING' && isActive) return 'RUNNING';
    const workDone = (state.pagesDone ?? 0) + (state.attachmentsDone ?? 0);
    if (workDone > 0) return 'PAUSED';
    return 'PENDING';
  }

  private loadLastItems(): Observable<void> {
    return new Observable(observer => {
      const projectKeys = new Set(this.projects().map(p => p.key.trim().toLowerCase()));

      this.sentinelleApiService.getJiraLastScanItems().subscribe({
        next: (events) => {
          for (const event of events) {
            const type = (event as Record<string, unknown>)?.eventType as string | undefined;
            if (type !== 'item' && type !== 'attachmentItem') continue;

            const incomingKey = coerceSpaceKey(event);
            if (!incomingKey) continue;
            if (!projectKeys.has(incomingKey.trim().toLowerCase())) continue;

            this.piiItemsStorage.addPiiItemToProject(incomingKey, event);
          }
          observer.next();
          observer.complete();
        },
        error: () => {
          observer.complete();
        }
      });
    });
  }

  private reapplyLastScanUi(): void {
    const list = this.lastProjectStatuses();
    if (!Array.isArray(list) || list.length === 0) return;

    for (const s of list) {
      const uiStatus = this.computeUiStatus(s, false);
      this.dashboardUtils.updateProject(s.spaceKey, {
        status: uiStatus,
        lastScanTs: s.lastEventTs
      });
      if (s.status === 'COMPLETED') {
        this.scanProgressService.updateProgress(s.spaceKey, {
          percent: s.progressPercentage ?? 100
        });
      }
    }
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
