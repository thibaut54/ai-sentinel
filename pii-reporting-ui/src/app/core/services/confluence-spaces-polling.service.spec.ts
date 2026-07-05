import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ConfluenceSpacesPollingService, SpaceChangeDetection } from './confluence-spaces-polling.service';
import { SentinelleApiService } from './sentinelle-api.service';
import { Space } from '../models/space';
import { SpaceUpdateInfo } from '../models/space-update-info.model';

describe('ConfluenceSpacesPollingService', () => {
  let service: ConfluenceSpacesPollingService;
  let httpTesting: HttpTestingController;
  let apiMock: { getSpaces: ReturnType<typeof vi.fn>; getSpacesUpdateInfo: ReturnType<typeof vi.fn> };

  const twoSpaces: Space[] = [{ key: 'A' }, { key: 'B' }];
  const threeSpaces: Space[] = [{ key: 'A' }, { key: 'B' }, { key: 'C' }];

  beforeEach(() => {
    apiMock = {
      getSpaces: vi.fn().mockReturnValue(of(twoSpaces)),
      getSpacesUpdateInfo: vi.fn().mockReturnValue(of([]))
    };

    TestBed.configureTestingModule({
      providers: [
        ConfluenceSpacesPollingService,
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: SentinelleApiService, useValue: apiMock }
      ]
    });
    service = TestBed.inject(ConfluenceSpacesPollingService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpTesting.verify();
    vi.useRealTimers();
  });

  // ========== loadPollingConfig ==========

  it('Should_ConfigureInterval_When_LoadPollingConfigSucceeds', async () => {
    const promise = service.loadPollingConfig();

    httpTesting.expectOne('/api/v1/config/polling').flush({
      backendRefreshIntervalMs: 5000,
      frontendPollingIntervalMs: 10000
    });

    await expect(promise).resolves.toBeUndefined();
  });

  it('Should_KeepDefaultInterval_When_LoadPollingConfigFails', async () => {
    const promise = service.loadPollingConfig();

    httpTesting.expectOne('/api/v1/config/polling').flush('boom', { status: 500, statusText: 'Server Error' });

    await expect(promise).resolves.toBeUndefined();
  });

  // ========== startPolling ==========

  it('Should_DetectNewSpaces_When_CountIncreases', () => {
    vi.useFakeTimers();
    apiMock.getSpaces.mockReturnValue(of(threeSpaces));

    let detection: SpaceChangeDetection | undefined;
    service.startPolling(2).subscribe((value) => (detection = value));

    // timer(interval, interval).skip(1) -> first emission at 2 * interval
    vi.advanceTimersByTime(120000);

    expect(detection?.hasNewSpaces).toBe(true);
    expect(detection?.newSpacesCount).toBe(1);
    expect(detection?.totalCount).toBe(3);
  });

  it('Should_ReportNoNewSpaces_When_CountUnchanged', () => {
    vi.useFakeTimers();
    apiMock.getSpaces.mockReturnValue(of(twoSpaces));

    let detection: SpaceChangeDetection | undefined;
    service.startPolling(2).subscribe((value) => (detection = value));

    vi.advanceTimersByTime(120000);

    expect(detection?.hasNewSpaces).toBe(false);
    expect(detection?.newSpacesCount).toBe(0);
  });

  // ========== startUpdateInfoPolling ==========

  it('Should_EmitUpdateInfos_When_PollingSucceeds', () => {
    vi.useFakeTimers();
    const infos: SpaceUpdateInfo[] = [
      { spaceKey: 'A', spaceName: 'A', hasBeenUpdated: true, lastModified: null, lastScanDate: null, updatedPages: [], updatedAttachments: [] }
    ];
    apiMock.getSpacesUpdateInfo.mockReturnValue(of(infos));

    let received: SpaceUpdateInfo[] | undefined;
    service.startUpdateInfoPolling().subscribe((value) => (received = value));

    vi.advanceTimersByTime(120000);

    expect(received).toEqual(infos);
  });

  it('Should_EmitEmptyList_When_UpdateInfoPollingFails', () => {
    vi.useFakeTimers();
    apiMock.getSpacesUpdateInfo.mockReturnValue(throwError(() => new Error('network')));

    let received: SpaceUpdateInfo[] | undefined;
    service.startUpdateInfoPolling().subscribe((value) => (received = value));

    vi.advanceTimersByTime(120000);

    expect(received).toEqual([]);
  });
});
