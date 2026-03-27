import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { ScanStatusPollingService } from './scan-status-polling.service';
import { SentinelleApiService, ScanReportingSummaryDto } from './sentinelle-api.service';
import { SpacesDashboardUtils } from '../../features/confluence-dashboard/spaces-dashboard.utils';
import { ScanProgressService } from './scan-progress.service';
import { DashboardUiStateService } from '../../features/confluence-dashboard/services/dashboard-ui-state.service';
import { of } from 'rxjs';

describe('ScanStatusPollingService', () => {
  let service: ScanStatusPollingService;
  let apiMock: { getDashboardSpacesSummary: ReturnType<typeof vi.fn> };
  let utilsMock: { updateSpace: ReturnType<typeof vi.fn> };
  let progressMock: { updateProgress: ReturnType<typeof vi.fn> };
  let uiStateMock: {
    upsertScanHistory: ReturnType<typeof vi.fn>;
    activeSpaceKey: { set: ReturnType<typeof vi.fn> };
  };

  const mockSummary: ScanReportingSummaryDto = {
    scanId: 'scan-1',
    lastUpdated: '2026-03-25T10:00:00Z',
    spacesCount: 2,
    spaces: [
      {
        spaceKey: 'SPACE1',
        status: 'RUNNING',
        progressPercentage: 45,
        pagesDone: 10,
        attachmentsDone: 5,
        lastEventTs: '2026-03-25T10:00:00Z',
        severityCounts: { high: 2, medium: 3, low: 1, total: 6 }
      },
      {
        spaceKey: 'SPACE2',
        status: 'COMPLETED',
        progressPercentage: 100,
        pagesDone: 20,
        attachmentsDone: 10,
        lastEventTs: '2026-03-25T09:55:00Z',
        severityCounts: { high: 0, medium: 1, low: 0, total: 1 }
      }
    ]
  };

  beforeEach(() => {
    vi.useFakeTimers();

    apiMock = { getDashboardSpacesSummary: vi.fn().mockReturnValue(of(mockSummary)) };
    utilsMock = { updateSpace: vi.fn() };
    progressMock = { updateProgress: vi.fn() };
    uiStateMock = {
      upsertScanHistory: vi.fn(),
      activeSpaceKey: { set: vi.fn() }
    };

    TestBed.configureTestingModule({
      providers: [
        ScanStatusPollingService,
        { provide: SentinelleApiService, useValue: apiMock },
        { provide: SpacesDashboardUtils, useValue: utilsMock },
        { provide: ScanProgressService, useValue: progressMock },
        { provide: DashboardUiStateService, useValue: uiStateMock }
      ]
    });
    service = TestBed.inject(ScanStatusPollingService);
  });

  afterEach(() => {
    service.stop();
    vi.useRealTimers();
  });

  it('Should_PollImmediatelyThenAtInterval_When_StartCalled', () => {
    service.start(3000);

    // First poll immediate (timer(0, interval))
    vi.advanceTimersByTime(0);
    expect(apiMock.getDashboardSpacesSummary).toHaveBeenCalledTimes(1);

    vi.advanceTimersByTime(3000);
    expect(apiMock.getDashboardSpacesSummary).toHaveBeenCalledTimes(2);
  });

  it('Should_ApplyStatusesToSpaces_When_SummaryReceived', () => {
    service.start(3000);
    vi.advanceTimersByTime(0);

    expect(utilsMock.updateSpace).toHaveBeenCalledWith('SPACE1', expect.objectContaining({
      status: 'RUNNING',
      counts: { high: 2, medium: 3, low: 1, total: 6 }
    }));
    expect(utilsMock.updateSpace).toHaveBeenCalledWith('SPACE2', expect.objectContaining({
      status: 'OK'
    }));
    expect(progressMock.updateProgress).toHaveBeenCalledWith('SPACE1', { percent: 45 });
    expect(progressMock.updateProgress).toHaveBeenCalledWith('SPACE2', { percent: 100 });
  });

  it('Should_UpdateActiveSpaceKey_When_SpaceIsRunning', () => {
    service.start(3000);
    vi.advanceTimersByTime(0);

    expect(uiStateMock.activeSpaceKey.set).toHaveBeenCalledWith('SPACE1');
  });

  it('Should_UpdateScanHistory_When_StatusChanges', () => {
    service.start(3000);
    vi.advanceTimersByTime(0);

    expect(uiStateMock.upsertScanHistory).toHaveBeenCalledWith('SPACE1', 'running');
    expect(uiStateMock.upsertScanHistory).toHaveBeenCalledWith('SPACE2', 'completed');
  });

  it('Should_StopPolling_When_StopCalled', () => {
    service.start(3000);
    vi.advanceTimersByTime(0);
    expect(apiMock.getDashboardSpacesSummary).toHaveBeenCalledTimes(1);

    service.stop();
    vi.advanceTimersByTime(3000);
    expect(apiMock.getDashboardSpacesSummary).toHaveBeenCalledTimes(1); // No new call
  });

  it('Should_EmitScanCompleted_When_AllSpacesCompleted', () => {
    const allCompleteSummary: ScanReportingSummaryDto = {
      ...mockSummary,
      spaces: mockSummary.spaces.map(s => ({ ...s, status: 'COMPLETED', progressPercentage: 100 }))
    };
    apiMock.getDashboardSpacesSummary.mockReturnValue(of(allCompleteSummary));

    let completed = false;
    service.scanCompleted$.subscribe(() => completed = true);

    service.start(3000);
    vi.advanceTimersByTime(0);

    expect(completed).toBe(true);
  });

  it('Should_NotCrash_When_SummaryIsNull', () => {
    apiMock.getDashboardSpacesSummary.mockReturnValue(of(null));

    service.start(3000);
    vi.advanceTimersByTime(0);

    expect(utilsMock.updateSpace).not.toHaveBeenCalled();
  });
});
