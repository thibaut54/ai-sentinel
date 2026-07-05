import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import {
  LastScanMeta,
  PageSecretsResponse,
  ScanReportingSummaryDto,
  SentinelleApiService,
  SpaceScanStateDto
} from './sentinelle-api.service';
import { Space } from '../models/space';
import { SpaceUpdateInfo } from '../models/space-update-info.model';
import { ConfluenceContentPersonallyIdentifiableInformationScanResult } from '../models/stream-event-type';

describe('SentinelleApiService HTTP methods', () => {
  let service: SentinelleApiService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        SentinelleApiService,
        provideHttpClient(),
        provideHttpClientTesting()
      ]
    });
    service = TestBed.inject(SentinelleApiService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpTesting.verify();
  });

  // ========== loadRevealConfig ==========

  it('Should_SetRevealAllowedSignal_When_LoadRevealConfigSucceeds', () => {
    let result: boolean | undefined;
    service.loadRevealConfig().subscribe((value) => (result = value));

    httpTesting.expectOne('/api/v1/pii/config/reveal-allowed').flush(true);

    expect(result).toBe(true);
    expect(service.revealAllowed()).toBe(true);
  });

  it('Should_FallbackToFalse_When_LoadRevealConfigFails', () => {
    let result: boolean | undefined;
    service.loadRevealConfig().subscribe((value) => (result = value));

    httpTesting
      .expectOne('/api/v1/pii/config/reveal-allowed')
      .flush('boom', { status: 500, statusText: 'Server Error' });

    expect(result).toBe(false);
    expect(service.revealAllowed()).toBe(false);
  });

  // ========== getSpaces ==========

  it('Should_MapAndFilterSpaces_When_GetSpacesReturnsData', () => {
    let spaces: Space[] | undefined;
    service.getSpaces().subscribe((data) => (spaces = data));

    httpTesting.expectOne('/api/v1/confluence/spaces').flush([
      { key: 'A', name: 'Alpha', url: 'http://a' },
      { key: 'B' },
      { key: '' }
    ]);

    expect(spaces).toEqual([
      { key: 'A', name: 'Alpha', url: 'http://a' },
      { key: 'B', name: '', url: undefined }
    ]);
  });

  it('Should_PropagateError_When_GetSpacesFails', () => {
    let error: HttpErrorResponse | undefined;
    service.getSpaces().subscribe({ error: (err) => (error = err) });

    httpTesting
      .expectOne('/api/v1/confluence/spaces')
      .flush('nope', { status: 503, statusText: 'Unavailable' });

    expect(error?.status).toBe(503);
  });

  // ========== getSpacesUpdateInfo ==========

  it('Should_ReturnUpdateInfo_When_GetSpacesUpdateInfoReturnsArray', () => {
    const payload: SpaceUpdateInfo[] = [
      { spaceKey: 'A', spaceName: 'Alpha', hasBeenUpdated: true, lastModified: null, lastScanDate: null, updatedPages: [], updatedAttachments: [] }
    ];
    let result: SpaceUpdateInfo[] | undefined;
    service.getSpacesUpdateInfo().subscribe((data) => (result = data));

    httpTesting.expectOne('/api/v1/confluence/spaces/update-info').flush(payload);

    expect(result).toEqual(payload);
  });

  it('Should_ReturnEmptyArray_When_GetSpacesUpdateInfoReturnsNonArray', () => {
    let result: SpaceUpdateInfo[] | undefined;
    service.getSpacesUpdateInfo().subscribe((data) => (result = data));

    httpTesting.expectOne('/api/v1/confluence/spaces/update-info').flush(null);

    expect(result).toEqual([]);
  });

  it('Should_PropagateError_When_GetSpacesUpdateInfoFails', () => {
    let error: HttpErrorResponse | undefined;
    service.getSpacesUpdateInfo().subscribe({ error: (err) => (error = err) });

    httpTesting
      .expectOne('/api/v1/confluence/spaces/update-info')
      .flush('err', { status: 500, statusText: 'Server Error' });

    expect(error?.status).toBe(500);
  });

  // ========== getLastScanMeta ==========

  it('Should_ReturnMeta_When_GetLastScanMetaReturnsData', () => {
    const meta: LastScanMeta = { scanId: 'scan-1', lastUpdated: '2026-01-01', spacesCount: 3 };
    let result: LastScanMeta | null | undefined;
    service.getLastScanMeta().subscribe((data) => (result = data));

    httpTesting.expectOne('/api/v1/scans/last').flush(meta);

    expect(result).toEqual(meta);
  });

  it('Should_ReturnNull_When_GetLastScanMetaFails', () => {
    let result: LastScanMeta | null | undefined = undefined;
    service.getLastScanMeta().subscribe((data) => (result = data));

    httpTesting
      .expectOne('/api/v1/scans/last')
      .flush('no content', { status: 404, statusText: 'Not Found' });

    expect(result).toBeNull();
  });

  // ========== getLastScanSpaceStatuses ==========

  it('Should_ReturnStatuses_When_GetLastScanSpaceStatusesReturnsArray', () => {
    const statuses: SpaceScanStateDto[] = [
      { spaceKey: 'A', status: 'OK', pagesDone: 1, attachmentsDone: 0, lastEventTs: '2026-01-01', progressPercentage: 100 }
    ];
    let result: SpaceScanStateDto[] | undefined;
    service.getLastScanSpaceStatuses().subscribe((data) => (result = data));

    httpTesting.expectOne('/api/v1/scans/last/spaces').flush(statuses);

    expect(result).toEqual(statuses);
  });

  it('Should_ReturnEmptyArray_When_GetLastScanSpaceStatusesFails', () => {
    let result: SpaceScanStateDto[] | undefined;
    service.getLastScanSpaceStatuses().subscribe((data) => (result = data));

    httpTesting
      .expectOne('/api/v1/scans/last/spaces')
      .flush('err', { status: 500, statusText: 'Server Error' });

    expect(result).toEqual([]);
  });

  // ========== getLastScanItems ==========

  it('Should_ReturnItems_When_GetLastScanItemsReturnsArray', () => {
    const items: ConfluenceContentPersonallyIdentifiableInformationScanResult[] = [
      { scanId: 'scan-1', spaceKey: 'A', pageId: 1, pageTitle: 'Page' }
    ];
    let result: ConfluenceContentPersonallyIdentifiableInformationScanResult[] | undefined;
    service.getLastScanItems().subscribe((data) => (result = data));

    httpTesting.expectOne('/api/v1/scans/last/items').flush(items);

    expect(result).toEqual(items);
  });

  it('Should_ReturnEmptyArray_When_GetLastScanItemsFails', () => {
    let result: ConfluenceContentPersonallyIdentifiableInformationScanResult[] | undefined;
    service.getLastScanItems().subscribe((data) => (result = data));

    httpTesting
      .expectOne('/api/v1/scans/last/items')
      .flush('err', { status: 500, statusText: 'Server Error' });

    expect(result).toEqual([]);
  });

  // ========== getDashboardSpacesSummary ==========

  it('Should_ReturnSummary_When_GetDashboardSpacesSummaryReturnsData', () => {
    const summary: ScanReportingSummaryDto = {
      scanId: 'scan-1',
      lastUpdated: '2026-01-01',
      spacesCount: 1,
      spaces: [
        { spaceKey: 'A', status: 'OK', progressPercentage: 100, pagesDone: 1, attachmentsDone: 0, lastEventTs: '2026-01-01', severityCounts: { high: 1, medium: 0, low: 0, total: 1 } }
      ]
    };
    let result: ScanReportingSummaryDto | null | undefined;
    service.getDashboardSpacesSummary().subscribe((data) => (result = data));

    httpTesting.expectOne('/api/v1/scans/dashboard/spaces-summary').flush(summary);

    expect(result).toEqual(summary);
  });

  it('Should_ReturnNull_When_GetDashboardSpacesSummaryFails', () => {
    let result: ScanReportingSummaryDto | null | undefined = undefined;
    service.getDashboardSpacesSummary().subscribe((data) => (result = data));

    httpTesting
      .expectOne('/api/v1/scans/dashboard/spaces-summary')
      .flush('err', { status: 500, statusText: 'Server Error' });

    expect(result).toBeNull();
  });

  // ========== resumeScan / pauseScan ==========

  it('Should_PostResume_When_ResumeScanCalled', () => {
    let completed = false;
    service.resumeScan('scan 1').subscribe({ complete: () => (completed = true) });

    const request = httpTesting.expectOne('/api/v1/stream/scan%201/resume');
    expect(request.request.method).toBe('POST');
    request.flush(null);

    expect(completed).toBe(true);
  });

  it('Should_PropagateError_When_ResumeScanFails', () => {
    let error: HttpErrorResponse | undefined;
    service.resumeScan('scan-1').subscribe({ error: (err) => (error = err) });

    httpTesting
      .expectOne('/api/v1/stream/scan-1/resume')
      .flush('err', { status: 409, statusText: 'Conflict' });

    expect(error?.status).toBe(409);
  });

  it('Should_PostPause_When_PauseScanCalled', () => {
    let completed = false;
    service.pauseScan('scan-1').subscribe({ complete: () => (completed = true) });

    const request = httpTesting.expectOne('/api/v1/stream/scan-1/pause');
    expect(request.request.method).toBe('POST');
    request.flush(null);

    expect(completed).toBe(true);
  });

  it('Should_PropagateError_When_PauseScanFails', () => {
    let error: HttpErrorResponse | undefined;
    service.pauseScan('scan-1').subscribe({ error: (err) => (error = err) });

    httpTesting
      .expectOne('/api/v1/stream/scan-1/pause')
      .flush('err', { status: 500, statusText: 'Server Error' });

    expect(error?.status).toBe(500);
  });

  // ========== purgeAllScans ==========

  it('Should_PostPurge_When_PurgeAllScansCalled', () => {
    let completed = false;
    service.purgeAllScans().subscribe({ complete: () => (completed = true) });

    const request = httpTesting.expectOne('/api/v1/scans/purge');
    expect(request.request.method).toBe('POST');
    request.flush(null);

    expect(completed).toBe(true);
  });

  it('Should_PropagateError_When_PurgeAllScansFails', () => {
    let error: HttpErrorResponse | undefined;
    service.purgeAllScans().subscribe({ error: (err) => (error = err) });

    httpTesting
      .expectOne('/api/v1/scans/purge')
      .flush('err', { status: 500, statusText: 'Server Error' });

    expect(error?.status).toBe(500);
  });

  // ========== getRevealConfig ==========

  it('Should_ReturnAllowed_When_GetRevealConfigSucceeds', () => {
    let result: boolean | undefined;
    service.getRevealConfig().subscribe((value) => (result = value));

    httpTesting.expectOne('/api/v1/pii/config/reveal-allowed').flush(true);

    expect(result).toBe(true);
  });

  it('Should_PropagateError_When_GetRevealConfigFails', () => {
    let error: HttpErrorResponse | undefined;
    service.getRevealConfig().subscribe({ error: (err) => (error = err) });

    httpTesting
      .expectOne('/api/v1/pii/config/reveal-allowed')
      .flush('err', { status: 500, statusText: 'Server Error' });

    expect(error?.status).toBe(500);
  });

  // ========== revealPageSecrets ==========

  it('Should_PostScanAndPageId_When_RevealPageSecretsCalled', () => {
    const response: PageSecretsResponse = {
      scanId: 'scan-1',
      pageId: 'page-1',
      pageTitle: 'Page',
      secrets: [
        { startPosition: 0, endPosition: 5, sensitiveValue: 'secret', sensitiveContext: 'ctx', maskedContext: '*****' }
      ]
    };
    let result: PageSecretsResponse | undefined;
    service.revealPageSecrets('scan-1', 'page-1').subscribe((data) => (result = data));

    const request = httpTesting.expectOne('/api/v1/pii/reveal-page');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ scanId: 'scan-1', pageId: 'page-1' });
    request.flush(response);

    expect(result).toEqual(response);
  });

  it('Should_PropagateError_When_RevealPageSecretsFails', () => {
    let error: HttpErrorResponse | undefined;
    service.revealPageSecrets('scan-1', 'page-1').subscribe({ error: (err) => (error = err) });

    httpTesting
      .expectOne('/api/v1/pii/reveal-page')
      .flush('forbidden', { status: 403, statusText: 'Forbidden' });

    expect(error?.status).toBe(403);
  });
});
