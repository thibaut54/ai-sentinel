import { TestBed } from '@angular/core/testing';
import { MessageService } from 'primeng/api';
import { TranslocoService } from '@jsverse/transloco';
import { vi } from 'vitest';
import {
  ErrorNotificationService,
  classifyError,
  toTranslocoKey
} from './error-notification.service';

describe('ErrorNotificationService', () => {
  let service: ErrorNotificationService;
  let messageServiceMock: { add: ReturnType<typeof vi.fn> };
  let translocoServiceMock: { translate: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    messageServiceMock = { add: vi.fn() };
    translocoServiceMock = { translate: vi.fn((key: string) => `translated:${key}`) };

    TestBed.configureTestingModule({
      providers: [
        ErrorNotificationService,
        { provide: MessageService, useValue: messageServiceMock },
        { provide: TranslocoService, useValue: translocoServiceMock }
      ]
    });

    service = TestBed.inject(ErrorNotificationService);
  });

  it('Should_ShowToast_When_TransientError', () => {
    service.notify({ errorKey: 'error.confluence.connection.failed', status: 503, category: 'transient' });

    expect(messageServiceMock.add).toHaveBeenCalledWith(
      expect.objectContaining({
        severity: 'error',
        summary: 'Erreur',
        detail: 'translated:errors.confluence.connectionFailed',
        life: 8000
      })
    );
    expect(service.banner()).toBeNull();
  });

  it('Should_SetBannerSignal_When_ConfigurationError', () => {
    service.notify({ errorKey: 'error.confluence.auth.failed', status: 401, category: 'configuration' });

    expect(service.banner()).toEqual({
      errorKey: 'error.confluence.auth.failed',
      message: 'translated:errors.confluence.authFailed',
      status: 401
    });
    expect(messageServiceMock.add).not.toHaveBeenCalled();
  });

  it('Should_ShowToast_When_ConflictError', () => {
    service.notify({ errorKey: 'error.scan.invalid_status_transition', status: 409, category: 'conflict' });

    expect(messageServiceMock.add).toHaveBeenCalledWith(
      expect.objectContaining({
        severity: 'error',
        detail: 'translated:errors.scan.invalidStatusTransition'
      })
    );
  });

  it('Should_ClearBanner_When_ErrorResolved', () => {
    service.notify({ errorKey: 'error.access.denied', status: 403, category: 'configuration' });
    expect(service.banner()).not.toBeNull();

    service.clearBanner();
    expect(service.banner()).toBeNull();
  });

  it('Should_DeduplicateSameErrorKey_When_MultipleErrorsWithin3s', () => {
    vi.useFakeTimers();
    try {
      service.notify({ errorKey: 'error.internal', status: 500, category: 'transient' });
      service.notify({ errorKey: 'error.internal', status: 500, category: 'transient' });

      expect(messageServiceMock.add).toHaveBeenCalledTimes(1);

      vi.advanceTimersByTime(3000);

      service.notify({ errorKey: 'error.internal', status: 500, category: 'transient' });
      expect(messageServiceMock.add).toHaveBeenCalledTimes(2);
    } finally {
      vi.useRealTimers();
    }
  });

  it('Should_ShowToast_When_ValidationError', () => {
    service.notify({ errorKey: 'error.validation.invalid_argument', status: 400, category: 'validation' });

    expect(messageServiceMock.add).toHaveBeenCalledWith(
      expect.objectContaining({
        severity: 'error',
        detail: 'translated:errors.validation.invalidArgument'
      })
    );
  });

  it('Should_ShowToast_When_NotFoundError', () => {
    service.notify({ errorKey: 'error.scan.not_found', status: 404, category: 'not_found' });

    expect(messageServiceMock.add).toHaveBeenCalledWith(
      expect.objectContaining({
        severity: 'error',
        detail: 'translated:errors.scan.notFound'
      })
    );
  });
});

describe('classifyError', () => {
  it('Should_ReturnConfiguration_When_Status401', () => {
    expect(classifyError(401)).toBe('configuration');
  });

  it('Should_ReturnConfiguration_When_Status403', () => {
    expect(classifyError(403)).toBe('configuration');
  });

  it('Should_ReturnValidation_When_Status400', () => {
    expect(classifyError(400)).toBe('validation');
  });

  it('Should_ReturnNotFound_When_Status404', () => {
    expect(classifyError(404)).toBe('not_found');
  });

  it('Should_ReturnConflict_When_Status409', () => {
    expect(classifyError(409)).toBe('conflict');
  });

  it('Should_ReturnTransient_When_Status500', () => {
    expect(classifyError(500)).toBe('transient');
  });

  it('Should_ReturnTransient_When_Status503', () => {
    expect(classifyError(503)).toBe('transient');
  });
});

describe('toTranslocoKey', () => {
  it('Should_ConvertSimpleKey_When_TwoSegments', () => {
    expect(toTranslocoKey('error.access.denied')).toBe('errors.access.denied');
  });

  it('Should_ConvertWithCamelCase_When_UnderscoreInLastSegment', () => {
    expect(toTranslocoKey('error.scan.invalid_status_transition')).toBe('errors.scan.invalidStatusTransition');
  });

  it('Should_MergeLastTwoSegments_When_ThreeOrMoreSegments', () => {
    expect(toTranslocoKey('error.confluence.auth.failed')).toBe('errors.confluence.authFailed');
  });

  it('Should_HandleDeepKeys_When_FourSegments', () => {
    expect(toTranslocoKey('error.pii.detection.connection_failed')).toBe('errors.pii.detectionConnectionFailed');
  });

  it('Should_ReturnAsIs_When_NoErrorPrefix', () => {
    expect(toTranslocoKey('some.other.key')).toBe('some.other.key');
  });

  it('Should_HandleSingleSegment_When_ErrorDotInternal', () => {
    expect(toTranslocoKey('error.internal')).toBe('errors.internal');
  });

  it('Should_ConvertCryptoKey_When_Underscore', () => {
    expect(toTranslocoKey('error.crypto.operation_failed')).toBe('errors.crypto.operationFailed');
  });

  it('Should_ConvertExportKey_When_UnderscoreInSegment', () => {
    expect(toTranslocoKey('error.export.context_not_found')).toBe('errors.export.contextNotFound');
  });

  it('Should_ConvertEncryptionKey_When_Simple', () => {
    expect(toTranslocoKey('error.encryption.failed')).toBe('errors.encryption.failed');
  });

  it('Should_ConvertConfluenceSpace_When_ThreeSegments', () => {
    expect(toTranslocoKey('error.confluence.space.not_found')).toBe('errors.confluence.spaceNotFound');
  });

  it('Should_ConvertConfluenceSpace_When_CacheError', () => {
    expect(toTranslocoKey('error.confluence.space.cache_error')).toBe('errors.confluence.spaceCacheError');
  });

  it('Should_ConvertConfluenceDeserialization_When_ThreeSegments', () => {
    expect(toTranslocoKey('error.confluence.deserialization.failed')).toBe('errors.confluence.deserializationFailed');
  });

  it('Should_ConvertValidationConstraint_When_Underscore', () => {
    expect(toTranslocoKey('error.validation.constraint_violation')).toBe('errors.validation.constraintViolation');
  });

  it('Should_ConvertValidationMalformed_When_Underscore', () => {
    expect(toTranslocoKey('error.validation.malformed_request')).toBe('errors.validation.malformedRequest');
  });

  it('Should_ConvertStateInvalid_When_Simple', () => {
    expect(toTranslocoKey('error.state.invalid')).toBe('errors.state.invalid');
  });

  it('Should_ConvertPiiDetectorError_When_TwoSegments', () => {
    expect(toTranslocoKey('error.pii.detector.error')).toBe('errors.pii.detectorError');
  });

  it('Should_ConvertConfluenceResourceNotFound_When_ThreeSegments', () => {
    expect(toTranslocoKey('error.confluence.resource.not_found')).toBe('errors.confluence.resourceNotFound');
  });

  it('Should_ConvertConfluenceApiError_When_ThreeSegments', () => {
    expect(toTranslocoKey('error.confluence.api.error')).toBe('errors.confluence.apiError');
  });

  it('Should_ConvertConfluenceDateParseFailed_When_ThreeSegments', () => {
    expect(toTranslocoKey('error.confluence.date.parse_failed')).toBe('errors.confluence.dateParseFailed');
  });

  it('Should_ConvertPiiDetectionServiceError_When_ThreeSegments', () => {
    expect(toTranslocoKey('error.pii.detection.service_error')).toBe('errors.pii.detectionServiceError');
  });

  it('Should_ConvertPiiDetectionTimeout_When_ThreeSegments', () => {
    expect(toTranslocoKey('error.pii.detection.timeout')).toBe('errors.pii.detectionTimeout');
  });

  it('Should_ConvertExportFailed_When_Simple', () => {
    expect(toTranslocoKey('error.export.failed')).toBe('errors.export.failed');
  });

  it('Should_ConvertExportUnsupportedSourceType_When_Underscore', () => {
    expect(toTranslocoKey('error.export.unsupported_source_type')).toBe('errors.export.unsupportedSourceType');
  });
});
