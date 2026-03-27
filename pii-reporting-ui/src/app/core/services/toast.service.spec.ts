import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { ToastService, ErrorToastData } from './toast.service';
import { MessageService } from 'primeng/api';

describe('ToastService', () => {
  let service: ToastService;
  let msgMock: { add: ReturnType<typeof vi.fn>; clear: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    msgMock = { add: vi.fn(), clear: vi.fn() };

    TestBed.configureTestingModule({
      providers: [
        ToastService,
        { provide: MessageService, useValue: msgMock }
      ]
    });
    service = TestBed.inject(ToastService);
  });

  // ========== showScanError ==========

  it('Should_AddStickyError_When_ShowScanError', () => {
    const data: ErrorToastData = {
      scanId: 'scan-1',
      spaceKey: 'SPACE1',
      pageTitle: 'Test Page',
      errorMessage: 'Connection timeout',
      errorType: 'TIMEOUT_REACTOR'
    };

    service.showScanError(data);

    expect(msgMock.add).toHaveBeenCalledWith(expect.objectContaining({
      severity: 'error',
      summary: 'Reactor Timeout',
      sticky: true,
      key: 'scan-errors'
    }));
  });

  it('Should_FormatGrpcError_When_ErrorTypeIsGrpc', () => {
    const data: ErrorToastData = {
      scanId: 'scan-1',
      spaceKey: 'SPACE1',
      pageTitle: 'Page A',
      errorMessage: 'gRPC call failed',
      errorType: 'ERROR_GRPC'
    };

    service.showScanError(data);

    const call = msgMock.add.mock.calls[0][0];
    expect(call.summary).toBe('Analyse du contenu impossible');
    expect(call.detail).toContain('Service d\'analyse indisponible');
    expect(call.detail).toContain('Espace confluence: SPACE1');
    expect(call.detail).not.toContain('gRPC call failed'); // No tech details for gRPC
  });

  it('Should_IncludeAttachmentName_When_Present', () => {
    const data: ErrorToastData = {
      scanId: 'scan-1',
      spaceKey: 'SPACE1',
      attachmentName: 'report.pdf',
      errorMessage: 'Timeout',
      errorType: 'ERROR_GENERAL'
    };

    service.showScanError(data);

    const call = msgMock.add.mock.calls[0][0];
    expect(call.detail).toContain('Pièce jointe: "report.pdf"');
  });

  it('Should_ShowPageId_When_NoPageTitle', () => {
    const data: ErrorToastData = {
      scanId: 'scan-1',
      spaceKey: 'SPACE1',
      pageId: '12345',
      errorMessage: 'Error',
      errorType: 'ERROR_GENERAL'
    };

    service.showScanError(data);

    const call = msgMock.add.mock.calls[0][0];
    expect(call.detail).toContain('Page ID: 12345');
  });

  // ========== clearScanErrors ==========

  it('Should_ClearScanErrors_When_ClearScanErrors', () => {
    service.clearScanErrors();
    expect(msgMock.clear).toHaveBeenCalledWith('scan-errors');
  });

  // ========== detectErrorType ==========

  it('Should_DetectReactorTimeout_When_MessageContainsReactorTimeout', () => {
    expect(service.detectErrorType('Connection timeout in reactor pipeline')).toBe('TIMEOUT_REACTOR');
  });

  it('Should_DetectGrpcTimeout_When_MessageContainsGrpcTimeout', () => {
    expect(service.detectErrorType('gRPC timeout exceeded')).toBe('TIMEOUT_GRPC');
  });

  it('Should_DetectGrpcTimeout_When_DeadlineExceeded', () => {
    expect(service.detectErrorType('DEADLINE_EXCEEDED')).toBe('TIMEOUT_GRPC');
  });

  it('Should_DetectGrpcError_When_MessageContainsGrpc', () => {
    expect(service.detectErrorType('gRPC call failed')).toBe('ERROR_GRPC');
  });

  it('Should_DetectGeneralError_When_NoSpecificPattern', () => {
    expect(service.detectErrorType('Something went wrong')).toBe('ERROR_GENERAL');
  });

  // ========== All error type labels ==========

  it('Should_MapAllErrorTypes_When_ShowScanError', () => {
    const types: ErrorToastData['errorType'][] = ['TIMEOUT_REACTOR', 'TIMEOUT_GRPC', 'ERROR_GRPC', 'ERROR_GENERAL'];
    const expectedSummaries = ['Reactor Timeout', 'gRPC Timeout', 'Analyse du contenu impossible', 'Scan error'];

    types.forEach((type, i) => {
      msgMock.add.mockClear();
      service.showScanError({
        scanId: 'scan-1', spaceKey: 'SPACE1', errorMessage: 'err', errorType: type
      });
      expect(msgMock.add.mock.calls[0][0].summary).toBe(expectedSummaries[i]);
    });
  });
});
