import { computed, inject, Injectable, signal } from '@angular/core';
import { Observable, throwError } from 'rxjs';
import { tap, map, catchError, finalize } from 'rxjs/operators';
import { SentinelleApiService, LastScanMeta, SpaceScanStateDto } from '../../../core/services/sentinelle-api.service';
import { SharePointSite } from '../../../core/models/sharepoint-site.model';
import { SharePointSitesDashboardUtils } from '../sharepoint-sites-dashboard.utils';
import { ScanProgressService } from '../../../core/services/scan-progress.service';
import { SharePointPiiItemsStorageService } from './sharepoint-pii-items-storage.service';
import { SharePointDashboardUiStateService } from './sharepoint-dashboard-ui-state.service';
import { TranslocoService } from '@jsverse/transloco';
import { coerceSpaceKey } from '../../confluence-dashboard/spaces-dashboard-stream.utils';

@Injectable({
  providedIn: 'root'
})
export class SharePointSiteDataManagementService {
  private readonly sentinelleApiService = inject(SentinelleApiService);
  private readonly dashboardUtils = inject(SharePointSitesDashboardUtils);
  private readonly scanProgressService = inject(ScanProgressService);
  private readonly piiItemsStorage = inject(SharePointPiiItemsStorageService);
  private readonly uiStateService = inject(SharePointDashboardUiStateService);
  private readonly translocoService = inject(TranslocoService);

  readonly sites = signal<SharePointSite[]>([]);
  readonly queue = signal<string[]>([]);
  readonly isSitesLoading = signal<boolean>(true);

  readonly lastScanMeta = signal<LastScanMeta | null>(null);
  readonly lastSiteStatuses = signal<SpaceScanStateDto[]>([]);

  readonly lastRefresh = signal<Date | null>(null);
  readonly isRefreshing = signal<boolean>(false);

  readonly canStartScan = computed(() =>
    !this.isSitesLoading() && this.sites().length > 0
  );

  fetchSites(): Observable<void> {
    this.isSitesLoading.set(true);
    this.isRefreshing.set(true);

    return this.sentinelleApiService.getSharePointSites().pipe(
      tap((sites) => {
        this.sites.set(sites);
        this.queue.set(sites.map((s) => s.id));
        this.dashboardUtils.setSites(sites);
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
        this.isSitesLoading.set(false);
        this.isRefreshing.set(false);
      })
    );
  }

  loadLastScan(): Observable<void> {
    return new Observable(observer => {
      this.sentinelleApiService.getLastScanMeta().subscribe({
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

  loadLastSpaceStatuses(isActive: boolean, alsoLoadItems: boolean = true): Observable<void> {
    return new Observable(observer => {
      this.sentinelleApiService.getDashboardSpacesSummary().subscribe({
        next: (summary) => {
          if (!summary) {
            this.lastSiteStatuses.set([]);
            observer.next();
            observer.complete();
            return;
          }

          const siteIds = new Set(this.sites().map(s => s.id.trim().toLowerCase()));

          const spaceScanStateList = summary.spaces
            .filter(space => siteIds.has(space.spaceKey.trim().toLowerCase()))
            .map(space => ({
              spaceKey: space.spaceKey,
              status: space.status,
              pagesDone: space.pagesDone,
              attachmentsDone: space.attachmentsDone,
              lastEventTs: space.lastEventTs,
              progressPercentage: space.progressPercentage ?? undefined
            }));

          this.lastSiteStatuses.set(spaceScanStateList);

          for (const spaceSummary of summary.spaces) {
            if (!siteIds.has(spaceSummary.spaceKey.trim().toLowerCase())) continue;
            if (isActive && spaceSummary.status !== 'COMPLETED') continue;

            const uiStatus = this.computeUiStatus(
              { spaceKey: spaceSummary.spaceKey, status: spaceSummary.status, pagesDone: spaceSummary.pagesDone, attachmentsDone: spaceSummary.attachmentsDone, lastEventTs: spaceSummary.lastEventTs, progressPercentage: spaceSummary.progressPercentage ?? undefined },
              isActive
            );

            this.dashboardUtils.updateSite(spaceSummary.spaceKey, {
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
            this.dashboardUtils.updateSite(spaceSummary.spaceKey, { counts });
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
          this.lastSiteStatuses.set([]);
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
      const siteIds = new Set(this.sites().map(s => s.id.trim().toLowerCase()));

      this.sentinelleApiService.getLastScanItems().subscribe({
        next: (events) => {
          for (const event of events) {
            const type = (event as any)?.eventType as string | undefined;
            if (type !== 'item' && type !== 'attachmentItem') continue;

            const incomingKey = coerceSpaceKey(event);
            if (!incomingKey) continue;
            if (!siteIds.has(incomingKey.trim().toLowerCase())) continue;

            this.piiItemsStorage.addPiiItemToSite(incomingKey, event);
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
    const list = this.lastSiteStatuses();
    if (!Array.isArray(list) || list.length === 0) return;

    for (const s of list) {
      const uiStatus = this.computeUiStatus(s, false);
      this.dashboardUtils.updateSite(s.spaceKey, {
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

  refreshSites(): void {
    this.fetchSites().subscribe();
  }

  reset(): void {
    this.sites.set([]);
    this.queue.set([]);
    this.lastScanMeta.set(null);
    this.lastSiteStatuses.set([]);
    this.lastRefresh.set(null);
  }
}
