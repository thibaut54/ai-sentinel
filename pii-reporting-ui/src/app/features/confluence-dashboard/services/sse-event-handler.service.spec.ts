import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { vi } from 'vitest';
import { SseEventHandlerService } from './sse-event-handler.service';
import { TranslocoService } from '@jsverse/transloco';
import { ToastService } from '../../../core/services/toast.service';
import { PiiItemsStorageService } from './pii-items-storage.service';
import { DashboardUiStateService } from './dashboard-ui-state.service';
import { SpacesDashboardUtils } from '../spaces-dashboard.utils';

describe('SseEventHandlerService', () => {
  let service: SseEventHandlerService;
  let translocoMock: { translate: ReturnType<typeof vi.fn> };
  let toastMock: { showScanError: ReturnType<typeof vi.fn>; detectErrorType: ReturnType<typeof vi.fn> };
  let storageMock: { addPiiItemToSpace: ReturnType<typeof vi.fn> };
  let uiStateMock: { append: ReturnType<typeof vi.fn>; activeSpaceKey: ReturnType<typeof signal> };
  let utilsMock: { updateSpace: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    translocoMock = { translate: vi.fn((key: string) => key) };
    toastMock = { showScanError: vi.fn(), detectErrorType: vi.fn().mockReturnValue('ERROR_GENERAL') };
    storageMock = { addPiiItemToSpace: vi.fn().mockReturnValue(true) };
    uiStateMock = { append: vi.fn(), activeSpaceKey: signal('ACTIVE-SPACE') };
    utilsMock = { updateSpace: vi.fn() };

    TestBed.configureTestingModule({
      providers: [
        SseEventHandlerService,
        { provide: TranslocoService, useValue: translocoMock },
        { provide: ToastService, useValue: toastMock },
        { provide: PiiItemsStorageService, useValue: storageMock },
        { provide: DashboardUiStateService, useValue: uiStateMock },
        { provide: SpacesDashboardUtils, useValue: utilsMock }
      ]
    });
    service = TestBed.inject(SseEventHandlerService);
  });

  // ========== routeStreamEvent ==========

  it('Should_LogEvent_When_AnyEventReceived', () => {
    service.routeStreamEvent('keepalive', undefined);

    expect(uiStateMock.append).toHaveBeenCalled();
  });

  it('Should_DoNothing_When_PayloadIsUndefined', () => {
    service.routeStreamEvent('item', undefined);

    expect(storageMock.addPiiItemToSpace).not.toHaveBeenCalled();
  });

  // ========== item events ==========

  it('Should_AddItemToStorage_When_ItemEvent', () => {
    const payload = { spaceKey: 'SPACE1', pageId: 'page-1', detectedPIIList: [{ piiType: 'EMAIL' }] } as any;

    service.routeStreamEvent('item', payload);

    expect(storageMock.addPiiItemToSpace).toHaveBeenCalledWith('SPACE1', payload);
  });

  it('Should_AddItemToStorage_When_AttachmentItemEvent', () => {
    const payload = { spaceKey: 'SPACE1', attachmentName: 'file.pdf', detectedPIIList: [{ piiType: 'NAME' }] } as any;

    service.routeStreamEvent('attachmentItem', payload);

    expect(storageMock.addPiiItemToSpace).toHaveBeenCalledWith('SPACE1', payload);
  });

  it('Should_UseActiveSpaceKey_When_AttachmentMissingSpaceKey', () => {
    const payload = { attachmentName: 'file.pdf', attachmentUrl: 'url', detectedPIIList: [{ piiType: 'NAME' }] } as any;

    service.routeStreamEvent('attachmentItem', payload);

    expect(storageMock.addPiiItemToSpace).toHaveBeenCalledWith('ACTIVE-SPACE', payload);
    expect(uiStateMock.append).toHaveBeenCalledWith(expect.stringContaining('[DEBUG_LOG]'));
  });

  it('Should_SkipItem_When_NoSpaceKeyAndNotAttachment', () => {
    const payload = { pageId: 'page-1', detectedPIIList: [{ piiType: 'EMAIL' }] } as any;

    service.routeStreamEvent('item', payload);

    expect(storageMock.addPiiItemToSpace).not.toHaveBeenCalled();
  });

  // ========== scanError events ==========

  it('Should_ShowToast_When_ScanError', () => {
    const payload = { spaceKey: 'SPACE1', scanId: 'scan-1', message: 'Connection timeout in reactor pipeline' } as any;

    service.routeStreamEvent('scanError', payload);

    expect(toastMock.detectErrorType).toHaveBeenCalledWith('Connection timeout in reactor pipeline');
    expect(toastMock.showScanError).toHaveBeenCalledWith(expect.objectContaining({
      spaceKey: 'SPACE1',
      scanId: 'scan-1'
    }));
  });

  it('Should_UpdateTimestamp_When_ScanError', () => {
    const payload = { spaceKey: 'SPACE1', message: 'Error' } as any;

    service.routeStreamEvent('scanError', payload);

    expect(utilsMock.updateSpace).toHaveBeenCalledWith('SPACE1', expect.objectContaining({
      lastScanTs: expect.any(String)
    }));
  });

  it('Should_SkipError_When_NoSpaceKey', () => {
    const payload = { message: 'Error' } as any;

    service.routeStreamEvent('scanError', payload);

    expect(toastMock.showScanError).not.toHaveBeenCalled();
  });

  it('Should_UseErrorMessageField_When_NoMessageField', () => {
    const payload = { spaceKey: 'SPACE1', errorMessage: 'gRPC timeout' } as any;

    service.routeStreamEvent('scanError', payload);

    expect(toastMock.detectErrorType).toHaveBeenCalledWith('gRPC timeout');
  });

  it('Should_UseFallback_When_NoErrorMessage', () => {
    const payload = { spaceKey: 'SPACE1' } as any;

    service.routeStreamEvent('scanError', payload);

    expect(toastMock.detectErrorType).toHaveBeenCalledWith('Erreur inconnue');
  });

  // ========== Status events (ignored by SSE handler) ==========

  it('Should_OnlyLog_When_StatusEvent', () => {
    service.routeStreamEvent('start', { spaceKey: 'SPACE1' } as any);
    service.routeStreamEvent('complete', { spaceKey: 'SPACE1' } as any);
    service.routeStreamEvent('multiStart', {} as any);
    service.routeStreamEvent('multiComplete', {} as any);
    service.routeStreamEvent('pageStart', { spaceKey: 'SPACE1' } as any);

    expect(storageMock.addPiiItemToSpace).not.toHaveBeenCalled();
    expect(toastMock.showScanError).not.toHaveBeenCalled();
    // Only logging should have happened
    expect(uiStateMock.append).toHaveBeenCalledTimes(5);
  });
});
