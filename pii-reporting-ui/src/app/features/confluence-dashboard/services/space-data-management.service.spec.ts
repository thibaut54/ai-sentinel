import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { vi } from 'vitest';
import { SpaceDataManagementService } from './space-data-management.service';
import { SentinelleApiService } from '../../../core/services/sentinelle-api.service';
import { ConfluenceSpacesPollingService } from '../../../core/services/confluence-spaces-polling.service';
import { SpacesDashboardUtils } from '../spaces-dashboard.utils';
import { ScanProgressService } from '../../../core/services/scan-progress.service';
import { PiiItemsStorageService } from './pii-items-storage.service';
import { DashboardUiStateService } from './dashboard-ui-state.service';
import { TranslocoService } from '@jsverse/transloco';
import { of, throwError } from 'rxjs';

describe('SpaceDataManagementService', () => {
  let service: SpaceDataManagementService;

  let apiMock: Record<string, ReturnType<typeof vi.fn>>;
  let pollingMock: Record<string, ReturnType<typeof vi.fn>>;
  let utilsMock: Record<string, ReturnType<typeof vi.fn>>;
  let progressMock: Record<string, ReturnType<typeof vi.fn>>;
  let storageMock: Record<string, ReturnType<typeof vi.fn>>;
  let uiStateMock: Record<string, any>;
  let translocoMock: { translate: ReturnType<typeof vi.fn>; getActiveLang: ReturnType<typeof vi.fn> };

  const mockSpaces = [
    { key: 'SPACE1', name: 'Space 1' },
    { key: 'SPACE2', name: 'Space 2' }
  ];

  beforeEach(() => {
    apiMock = {
      getSpaces: vi.fn().mockReturnValue(of(mockSpaces)),
      getLastScanMeta: vi.fn().mockReturnValue(of({ scanId: 'scan-1', spacesCount: 2, lastUpdated: '' })),
      getDashboardSpacesSummary: vi.fn().mockReturnValue(of({
        scanId: 'scan-1',
        lastUpdated: '',
        spacesCount: 2,
        spaces: [
          { spaceKey: 'SPACE1', status: 'COMPLETED', progressPercentage: 100, pagesDone: 10, attachmentsDone: 5, lastEventAt: '2026-01-01', severityCounts: { high: 1, medium: 0, low: 0, total: 1 } },
          { spaceKey: 'SPACE2', status: 'RUNNING', progressPercentage: 50, pagesDone: 5, attachmentsDone: 2, lastEventAt: '2026-01-01', severityCounts: { high: 0, medium: 1, low: 0, total: 1 } }
        ]
      })),
      getLastScanItems: vi.fn().mockReturnValue(of([])),
      getSpacesUpdateInfo: vi.fn().mockReturnValue(of([]))
    };

    pollingMock = {
      startPolling: vi.fn().mockReturnValue(of({ hasNewSpaces: false, newSpacesCount: 0 })),
      startUpdateInfoPolling: vi.fn().mockReturnValue(of([]))
    };

    utilsMock = { setSpaces: vi.fn(), updateSpace: vi.fn() };
    progressMock = { updateProgress: vi.fn() };
    storageMock = { addPiiItemToSpace: vi.fn() };

    uiStateMock = {
      append: vi.fn(),
      lines: signal([])
    };

    translocoMock = {
      translate: vi.fn((key: string) => key),
      getActiveLang: vi.fn().mockReturnValue('en')
    };

    TestBed.configureTestingModule({
      providers: [
        SpaceDataManagementService,
        { provide: SentinelleApiService, useValue: apiMock },
        { provide: ConfluenceSpacesPollingService, useValue: pollingMock },
        { provide: SpacesDashboardUtils, useValue: utilsMock },
        { provide: ScanProgressService, useValue: progressMock },
        { provide: PiiItemsStorageService, useValue: storageMock },
        { provide: DashboardUiStateService, useValue: uiStateMock },
        { provide: TranslocoService, useValue: translocoMock }
      ]
    });
    service = TestBed.inject(SpaceDataManagementService);
  });

  // ========== Initial State ==========

  it('Should_HaveEmptySpaces_When_Created', () => {
    expect(service.spaces()).toEqual([]);
    expect(service.isSpacesLoading()).toBe(true);
    expect(service.lastScanMeta()).toBeNull();
  });

  it('Should_ComputeCanStartScan_When_SpacesLoaded', () => {
    expect(service.canStartScan()).toBe(false); // loading=true

    service.fetchSpaces().subscribe();

    expect(service.canStartScan()).toBe(true);
  });

  // ========== fetchSpaces ==========

  it('Should_LoadSpaces_When_FetchSpaces', () => {
    let completed = false;
    service.fetchSpaces().subscribe({ complete: () => completed = true });

    expect(service.spaces()).toEqual(mockSpaces);
    expect(service.queue()).toEqual(['SPACE1', 'SPACE2']);
    expect(utilsMock.setSpaces).toHaveBeenCalledWith(mockSpaces);
    expect(service.isSpacesLoading()).toBe(false);
    expect(completed).toBe(true);
  });

  it('Should_HandleError_When_FetchSpacesFails', () => {
    apiMock.getSpaces.mockReturnValue(throwError(() => new Error('Network error')));

    let errored = false;
    service.fetchSpaces().subscribe({ error: () => errored = true });

    expect(errored).toBe(true);
    expect(service.isSpacesLoading()).toBe(false);
    expect(uiStateMock.append).toHaveBeenCalled();
  });

  it('Should_StartPolling_When_FetchSpacesSucceeds', () => {
    service.fetchSpaces().subscribe();

    expect(pollingMock.startPolling).toHaveBeenCalledWith(2);
  });

  // ========== loadLastScan ==========

  it('Should_SetLastScanMeta_When_LoadLastScan', () => {
    let completed = false;
    service.loadLastScan().subscribe({ complete: () => completed = true });

    expect(service.lastScanMeta()).toEqual({ scanId: 'scan-1', spacesCount: 2, lastUpdated: '' });
    expect(uiStateMock.append).toHaveBeenCalled();
    expect(completed).toBe(true);
  });

  it('Should_SetNull_When_NoLastScan', () => {
    apiMock.getLastScanMeta.mockReturnValue(of(null));

    service.loadLastScan().subscribe();

    expect(service.lastScanMeta()).toBeNull();
    expect(uiStateMock.append).toHaveBeenCalled();
  });

  it('Should_HandleError_When_LoadLastScanFails', () => {
    apiMock.getLastScanMeta.mockReturnValue(throwError(() => new Error('fail')));

    let completed = false;
    service.loadLastScan().subscribe({ complete: () => completed = true });

    expect(service.lastScanMeta()).toBeNull();
    expect(completed).toBe(true);
  });

  // ========== loadLastSpaceStatuses ==========

  it('Should_ApplyStatuses_When_LoadLastSpaceStatuses', () => {
    service.loadLastSpaceStatuses(false).subscribe();

    expect(utilsMock.updateSpace).toHaveBeenCalledWith('SPACE1', expect.objectContaining({ status: 'OK' }));
    expect(utilsMock.updateSpace).toHaveBeenCalledWith('SPACE2', expect.objectContaining({ status: 'RUNNING' }));
    expect(progressMock.updateProgress).toHaveBeenCalledWith('SPACE1', { percent: 100 });
    expect(progressMock.updateProgress).toHaveBeenCalledWith('SPACE2', { percent: 50 });
  });

  it('Should_SetEmptyStatuses_When_SummaryIsNull', () => {
    apiMock.getDashboardSpacesSummary.mockReturnValue(of(null));

    service.loadLastSpaceStatuses(false).subscribe();

    expect(service.lastSpaceStatuses()).toEqual([]);
  });

  it('Should_HandleError_When_LoadStatusesFails', () => {
    apiMock.getDashboardSpacesSummary.mockReturnValue(throwError(() => new Error('fail')));

    let completed = false;
    service.loadLastSpaceStatuses(false).subscribe({ complete: () => completed = true });

    expect(service.lastSpaceStatuses()).toEqual([]);
    expect(completed).toBe(true);
  });

  // ========== Notification ==========

  it('Should_DismissNotification_When_DismissNotification', () => {
    service.hasNewSpaces.set(true);
    service.newSpacesCount.set(5);

    service.dismissNotification();

    expect(service.hasNewSpaces()).toBe(false);
    expect(service.newSpacesCount()).toBe(0);
  });

  // ========== Space Update Info ==========

  it('Should_LoadUpdateInfo_When_LoadSpacesUpdateInfo', () => {
    const infos = [{ spaceKey: 'SPACE1', spaceName: 'Space 1', hasBeenUpdated: true, updatedPages: ['Page 1'], updatedAttachments: [], lastModified: '', lastScanDate: null }];
    apiMock.getSpacesUpdateInfo.mockReturnValue(of(infos));

    service.loadSpacesUpdateInfo().subscribe();

    expect(service.spacesUpdateInfo()).toEqual(infos);
  });

  it('Should_ReturnTrue_When_SpaceHasBeenUpdated', () => {
    service.spacesUpdateInfo.set([
      { spaceKey: 'SPACE1', spaceName: 'Space 1', hasBeenUpdated: true, updatedPages: [], updatedAttachments: [], lastModified: '', lastScanDate: null }
    ]);

    expect(service.hasSpaceBeenUpdated('SPACE1')).toBe(true);
    expect(service.hasSpaceBeenUpdated('SPACE2')).toBe(false);
  });

  it('Should_ReturnTooltip_When_SpaceHasUpdatedPages', () => {
    service.spacesUpdateInfo.set([{
      spaceKey: 'SPACE1',
      spaceName: 'Space 1',
      hasBeenUpdated: true,
      updatedPages: ['Page A', 'Page B'],
      updatedAttachments: [],
      lastModified: '2026-01-15T10:00:00Z',
      lastScanDate: null
    }]);

    const tooltip = service.getSpaceUpdateTooltip('SPACE1');
    expect(tooltip).toBeTruthy();
    expect(translocoMock.translate).toHaveBeenCalled();
  });

  it('Should_ReturnEmpty_When_SpaceNotUpdated', () => {
    service.spacesUpdateInfo.set([{
      spaceKey: 'SPACE1',
      spaceName: 'Space 1',
      hasBeenUpdated: false,
      updatedPages: [],
      updatedAttachments: [],
      lastModified: '',
      lastScanDate: null
    }]);

    expect(service.getSpaceUpdateTooltip('SPACE1')).toBe('');
  });

  // ========== Background Polling ==========

  it('Should_StartAndStopPolling_When_BackgroundPolling', () => {
    service.startBackgroundPolling();
    expect(pollingMock.startPolling).toHaveBeenCalled();

    service.stopBackgroundPolling();
    // No assertion needed — just ensure no error
  });

  it('Should_StartAndStopUpdateInfoPolling_When_UpdateInfoPolling', () => {
    service.startUpdateInfoBackgroundPolling();
    expect(pollingMock.startUpdateInfoPolling).toHaveBeenCalled();

    service.stopUpdateInfoBackgroundPolling();
    // No assertion needed — just ensure no error
  });

  // ========== Reset ==========

  it('Should_ResetAllState_When_Reset', () => {
    service.spaces.set(mockSpaces as any);
    service.lastScanMeta.set({ scanId: 'x', spacesCount: 1, lastUpdated: '' });
    service.hasNewSpaces.set(true);

    service.reset();

    expect(service.spaces()).toEqual([]);
    expect(service.lastScanMeta()).toBeNull();
    expect(service.hasNewSpaces()).toBe(false);
    expect(service.currentScanSpaceKeys()).toBeNull();
  });

  // ========== loadLastSpaceStatuses with items ==========

  it('Should_LoadItems_When_AlsoLoadItemsTrue', () => {
    let completed = false;
    service.loadLastSpaceStatuses(true).subscribe({ complete: () => (completed = true) });

    expect(apiMock.getLastScanItems).toHaveBeenCalled();
    expect(completed).toBe(true);
  });

  it('Should_ApplyHundredPercent_When_SpaceCompleted', () => {
    apiMock.getDashboardSpacesSummary.mockReturnValue(of({
      scanId: 'scan-1',
      lastUpdated: '',
      spacesCount: 1,
      spaces: [
        { spaceKey: 'SPACE1', status: 'COMPLETED', progressPercentage: 80, pagesDone: 10, attachmentsDone: 5, lastEventAt: '2026-01-01', severityCounts: null }
      ]
    }));

    service.loadLastSpaceStatuses(false).subscribe();

    expect(progressMock.updateProgress).toHaveBeenCalledWith('SPACE1', { percent: 100 });
  });

  // ========== loadLastItems ==========

  it('Should_StoreItemAndAttachmentEvents_When_LoadLastItems', () => {
    apiMock.getLastScanItems.mockReturnValue(of([
      { eventType: 'item', spaceKey: 'SPACE1', pageId: 1 },
      { eventType: 'attachmentItem', spaceKey: 'SPACE2', pageId: 2 },
      { eventType: 'pageComplete', spaceKey: 'SPACE1' },
      { eventType: 'item', pageId: 3 }
    ]));

    service.loadLastItems().subscribe();

    expect(storageMock.addPiiItemToSpace).toHaveBeenCalledTimes(2);
    expect(storageMock.addPiiItemToSpace).toHaveBeenCalledWith('SPACE1', expect.objectContaining({ pageId: 1 }));
    expect(storageMock.addPiiItemToSpace).toHaveBeenCalledWith('SPACE2', expect.objectContaining({ pageId: 2 }));
  });

  it('Should_CompleteSilently_When_LoadLastItemsFails', () => {
    apiMock.getLastScanItems.mockReturnValue(throwError(() => new Error('fail')));

    let completed = false;
    service.loadLastItems().subscribe({ complete: () => (completed = true) });

    expect(completed).toBe(true);
    expect(storageMock.addPiiItemToSpace).not.toHaveBeenCalled();
  });

  // ========== reapplyLastScanUi via fetchSpaces ==========

  it('Should_ReapplyCachedStatuses_When_FetchSpacesAfterStatusesLoaded', () => {
    service.lastSpaceStatuses.set([
      { spaceKey: 'SPACE1', status: 'COMPLETED', pagesDone: 1, attachmentsDone: 0, lastEventAt: '2026-01-01', progressPercentage: 90 }
    ]);

    service.fetchSpaces().subscribe();

    expect(utilsMock.updateSpace).toHaveBeenCalledWith('SPACE1', expect.objectContaining({ status: 'OK' }));
    expect(progressMock.updateProgress).toHaveBeenCalledWith('SPACE1', { percent: 90 });
  });

  // ========== refreshSpaces ==========

  it('Should_ClearNotification_When_RefreshSpaces', () => {
    service.hasNewSpaces.set(true);
    service.newSpacesCount.set(3);

    service.refreshSpaces();

    expect(service.hasNewSpaces()).toBe(false);
    expect(service.newSpacesCount()).toBe(0);
  });

  // ========== Background polling new-spaces detection ==========

  it('Should_RaiseNotification_When_PollingDetectsNewSpaces', () => {
    pollingMock.startPolling.mockReturnValue(of({ hasNewSpaces: true, newSpacesCount: 4 }));

    service.startBackgroundPolling();

    expect(service.hasNewSpaces()).toBe(true);
    expect(service.newSpacesCount()).toBe(4);
  });

  // ========== getSpaceUpdateTooltip branches ==========

  it('Should_ListPagesAndAttachmentsWithOverflow_When_TooltipForUpdatedSpace', () => {
    service.spacesUpdateInfo.set([{
      spaceKey: 'SPACE1',
      spaceName: 'Space 1',
      hasBeenUpdated: true,
      updatedPages: ['P1', 'P2', 'P3', 'P4', 'P5', 'P6'],
      updatedAttachments: ['A1', 'A2'],
      lastModified: '2026-01-15T10:00:00Z',
      lastScanDate: null
    }]);

    const tooltip = service.getSpaceUpdateTooltip('SPACE1');

    expect(tooltip).toContain('- P1');
    expect(tooltip).toContain('- A1');
    expect(translocoMock.translate).toHaveBeenCalledWith(
      'dashboard.notifications.spaceUpdated.tooltip.andMore',
      { count: 1 }
    );
  });

  it('Should_ReturnContentModifiedFallback_When_NoSpecificListsProvided', () => {
    service.spacesUpdateInfo.set([{
      spaceKey: 'SPACE1',
      spaceName: 'Space 1',
      hasBeenUpdated: true,
      updatedPages: [],
      updatedAttachments: [],
      lastModified: null,
      lastScanDate: null
    }]);

    const tooltip = service.getSpaceUpdateTooltip('SPACE1');

    expect(tooltip).toBe('dashboard.notifications.spaceUpdated.tooltip.contentModified');
  });

  it('Should_UseEnglishLocale_When_ActiveLangIsEnglish', () => {
    translocoMock.getActiveLang.mockReturnValue('en');
    service.spacesUpdateInfo.set([{
      spaceKey: 'SPACE1',
      spaceName: 'Space 1',
      hasBeenUpdated: true,
      updatedPages: ['P1'],
      updatedAttachments: [],
      lastModified: '2026-01-15T10:00:00Z',
      lastScanDate: null
    }]);

    const tooltip = service.getSpaceUpdateTooltip('SPACE1');

    expect(tooltip).toContain('- P1');
    expect(translocoMock.getActiveLang).toHaveBeenCalled();
  });
});
