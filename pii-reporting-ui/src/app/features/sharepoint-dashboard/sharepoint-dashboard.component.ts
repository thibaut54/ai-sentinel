import {
    ChangeDetectionStrategy,
    Component,
    computed,
    DestroyRef,
    inject,
    OnDestroy,
    OnInit,
    signal
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ButtonModule } from 'primeng/button';
import { TranslocoModule } from '@jsverse/transloco';
import { PiiPageCardComponent } from '../pii-page-card/pii-page-card.component';
import { ToggleButtonModule } from 'primeng/togglebutton';
import { InputTextModule } from 'primeng/inputtext';
import { SelectModule } from 'primeng/select';
import { TableModule } from 'primeng/table';
import { TagModule } from 'primeng/tag';
import { Ripple } from 'primeng/ripple';
import { TooltipModule } from 'primeng/tooltip';
import { SkeletonModule } from 'primeng/skeleton';
import { ScanProgressBarComponent } from '../../shared/components/scan-progress-bar/scan-progress-bar.component';
import { PiiHelpDialogComponent } from '../../shared/components/pii-help-dialog/pii-help-dialog.component';
import { PiiSeverityBadgesComponent } from '../../shared/components/pii-severity-badges/pii-severity-badges.component';
import { SortEvent } from 'primeng/api';
import { SeverityCardsComponent } from '../severity-cards/severity-cards.component';
import { SeverityCounts } from '../../core/models/severity-counts';
import { SharePointConnectionConfigService } from '../../core/services/sharepoint-connection-config.service';
import { SharePointSiteFilteringService } from './services/sharepoint-site-filtering.service';
import { SharePointDashboardUiStateService } from './services/sharepoint-dashboard-ui-state.service';
import { SharePointPiiItemsStorageService } from './services/sharepoint-pii-items-storage.service';
import { SharePointSiteDataManagementService } from './services/sharepoint-site-data-management.service';
import { SharePointScanControlService } from './services/sharepoint-scan-control.service';
import { SharePointSitesDashboardUtils } from './sharepoint-sites-dashboard.utils';
import { SettingsDialogService } from '../../core/services/settings-dialog.service';

@Component({
  selector: 'app-sharepoint-dashboard',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    ButtonModule,
    ToggleButtonModule,
    PiiPageCardComponent,
    InputTextModule,
    SelectModule,
    TableModule,
    TagModule,
    Ripple,
    TooltipModule,
    SkeletonModule,
    TranslocoModule,
    ScanProgressBarComponent,
    SeverityCardsComponent,
    PiiHelpDialogComponent,
    PiiSeverityBadgesComponent
  ],
  templateUrl: './sharepoint-dashboard.component.html',
  styleUrl: './sharepoint-dashboard.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class SharePointDashboardComponent implements OnInit, OnDestroy {
  private readonly filteringService = inject(SharePointSiteFilteringService);
  private readonly uiStateService = inject(SharePointDashboardUiStateService);
  private readonly piiItemsStorage = inject(SharePointPiiItemsStorageService);
  private readonly dataManagement = inject(SharePointSiteDataManagementService);
  private readonly scanControl = inject(SharePointScanControlService);
  private readonly spConfigService = inject(SharePointConnectionConfigService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly settingsDialog = inject(SettingsDialogService);
  readonly dashboardUtils = inject(SharePointSitesDashboardUtils);

  readonly skeletonRows: number[] = Array.from({ length: 10 }, (_, i) => i);
  readonly showPiiHelpDialog = signal(false);
  first = 0;
  rows = 20;

  readonly spConfigMissing = signal(false);

  // Filtering
  readonly globalFilter = computed(() => this.filteringService.globalFilter());
  readonly statusFilter = computed(() => this.filteringService.statusFilter());
  readonly sortedSites = computed(() => this.filteringService.sortedSites());
  readonly statusOptions = computed(() => this.filteringService.statusOptions());

  // UI State
  readonly expandedRowKeys = computed(() => this.uiStateService.expandedRowKeys());
  readonly selectedSiteId = computed(() => this.uiStateService.selectedSiteId());
  readonly maskByDefault = computed(() => this.uiStateService.maskByDefault());
  readonly selectedSitesCount = computed(() => this.uiStateService.selectedSitesCount());

  get selectedSites(): { id: string }[] {
    return this.uiStateService.selectedSites();
  }

  set selectedSites(val: { id: string }[]) {
    this.uiStateService.selectedSites.set(val);
  }

  // PII Items
  readonly itemsBySite = computed(() => this.piiItemsStorage.itemsBySite());

  // Data
  readonly sites = computed(() => this.dataManagement.sites());
  readonly queue = computed(() => this.dataManagement.queue());
  readonly isSitesLoading = computed(() => this.dataManagement.isSitesLoading());
  readonly lastRefresh = computed(() => this.dataManagement.lastRefresh());
  readonly isRefreshing = computed(() => this.dataManagement.isRefreshing());

  // Scan Control
  readonly isStreaming = computed(() => this.scanControl.isStreaming());
  readonly canStartScan = computed(() => this.scanControl.canStartScan());
  readonly canResumeScan = computed(() => this.scanControl.canResumeScan());

  readonly globalSeverityCounts = computed<SeverityCounts>(() => {
    const sites = this.sortedSites();
    const result = { total: 0, high: 0, medium: 0, low: 0 };
    for (const s of sites) {
      if (s.counts) {
        result.total += s.counts.total;
        result.high += s.counts.high;
        result.medium += s.counts.medium;
        result.low += s.counts.low;
      }
    }
    return result;
  });

  ngOnInit(): void {
    this.spConfigService.getConfig().subscribe({
      next: (config) => {
        if (!config.configured) {
          this.spConfigMissing.set(true);
          this.dataManagement.isSitesLoading.set(false);
          return;
        }
        this.initializeDataLoading();
      },
      error: () => {
        this.spConfigMissing.set(true);
        this.dataManagement.isSitesLoading.set(false);
      }
    });

    this.spConfigService.configSaved$.pipe(
      takeUntilDestroyed(this.destroyRef)
    ).subscribe(() => {
      this.spConfigMissing.set(false);
      if (this.sites().length === 0) {
        this.initializeDataLoading();
      }
    });
  }

  private initializeDataLoading(): void {
    this.dataManagement.loadLastScan().subscribe();
    this.dataManagement.fetchSites().subscribe({
      next: () => {
        this.dataManagement.loadLastSiteStatuses(false, true).subscribe({
          next: () => {
            setTimeout(() => {
              this.scanControl.checkAndReconnectToRunningScan();
            }, 100);
          }
        });
      }
    });
  }

  ngOnDestroy(): void {
    this.scanControl.reset();
  }

  startAll(): void {
    this.scanControl.startAll();
  }

  startSelected(): void {
    const ids = this.selectedSites.map(s => s.id);
    this.scanControl.startSelected(ids);
  }

  pauseScan(): void {
    this.scanControl.pauseScan();
  }

  onGlobalChange(value: string): void {
    this.filteringService.onGlobalChange(value);
  }

  onFilter(field: 'name' | 'status', value: string | null | undefined): void {
    this.filteringService.onFilter(field, value);
  }

  onCustomSort(event: SortEvent): void {
    this.filteringService.onCustomSort(event);
  }

  onRowExpand(event: { data?: { id?: string } }): void {
    this.uiStateService.onRowExpand(event);
  }

  onRowCollapse(event: { data?: { id?: string } }): void {
    this.uiStateService.onRowCollapse(event);
  }

  refreshSites(): void {
    this.dataManagement.refreshSites();
  }

  statusLabel(status?: string): string {
    return this.uiStateService.statusLabel(status);
  }

  statusStyle(status?: string): 'danger' | 'warning' | 'success' | 'info' | 'secondary' {
    return this.uiStateService.statusStyle(status);
  }

  openSharePoint(site: { webUrl?: string }): void {
    if (site.webUrl) {
      window.open(site.webUrl, '_blank', 'noopener');
    }
  }

  openPiiHelpDialog(): void {
    this.showPiiHelpDialog.set(true);
  }

  requestOpenSettings(tab: number = 0): void {
    this.settingsDialog.open(tab);
  }

  dismissSpConfigBanner(): void {
    this.spConfigMissing.set(false);
  }

  onPageChange(event: { first: number; rows: number }): void {
    this.first = event.first;
    this.rows = event.rows;
  }

  nextPage(): void {
    this.first = this.first + this.rows;
  }

  prevPage(): void {
    this.first = Math.max(0, this.first - this.rows);
  }

  resetPage(): void {
    this.first = 0;
  }

  isLastPage(): boolean {
    const total = this.sortedSites().length;
    return total === 0 || this.first + this.rows >= total;
  }

  isFirstPage(): boolean {
    return this.first === 0;
  }

  get currentPage(): number {
    return Math.floor(this.first / this.rows) + 1;
  }

  get totalPages(): number {
    return Math.max(1, Math.ceil(this.sortedSites().length / this.rows));
  }
}
