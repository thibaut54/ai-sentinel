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
          { spaceKey: 'SPACE1', status: 'COMPLETED', progressPercentage: 100, pagesDone: 10, attachmentsDone: 5, lastEventTs: '2026-01-01', severityCounts: { high: 1, medium: 0, low: 0, total: 1 } },
          { spaceKey: 'SPACE2', status: 'RUNNING', progressPercentage: 50, pagesDone: 5, attachmentsDone: 2, lastEventTs: '2026-01-01', severityCounts: { high: 0, medium: 1, low: 0, total: 1 } }
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
});
