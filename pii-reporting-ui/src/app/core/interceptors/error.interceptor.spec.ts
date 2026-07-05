import { TestBed } from '@angular/core/testing';
import { HttpClient, HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { MessageService } from 'primeng/api';
import { TranslocoService } from '@jsverse/transloco';
import { vi, type MockInstance } from 'vitest';
import { errorInterceptor } from './error.interceptor';
import { ErrorNotificationService, type ErrorDetails } from '../services/error-notification.service';

describe('errorInterceptor', () => {
  let httpClient: HttpClient;
  let httpTesting: HttpTestingController;
  let notifySpy: MockInstance<(error: ErrorDetails) => void>;

  beforeEach(() => {
    const messageServiceMock = { add: vi.fn() };
    const translocoServiceMock = { translate: vi.fn((key: string) => `translated:${key}`) };

    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        ErrorNotificationService,
        { provide: MessageService, useValue: messageServiceMock },
        { provide: TranslocoService, useValue: translocoServiceMock }
      ]
    });

    httpClient = TestBed.inject(HttpClient);
    httpTesting = TestBed.inject(HttpTestingController);
    const errorService = TestBed.inject(ErrorNotificationService);
    notifySpy = vi.spyOn(errorService, 'notify');
  });

  afterEach(() => {
    httpTesting.verify();
  });

  it('Should_EmitNotification_When_ProblemDetailReceived', () => {
    httpClient.get('/api/test').subscribe({ error: () => {} });

    const req = httpTesting.expectOne('/api/test');
    req.flush(
      { errorKey: 'error.confluence.auth.failed', title: 'Unauthorized', status: 401 },
      { status: 401, statusText: 'Unauthorized' }
    );

    expect(notifySpy).toHaveBeenCalledWith({
      errorKey: 'error.confluence.auth.failed',
      status: 401,
      category: 'configuration'
    });
  });

  it('Should_UseDefaultErrorKey_When_ResponseHasNoErrorKey', () => {
    httpClient.get('/api/test').subscribe({ error: () => {} });

    const req = httpTesting.expectOne('/api/test');
    req.flush(null, { status: 500, statusText: 'Internal Server Error' });

    expect(notifySpy).toHaveBeenCalledWith({
      errorKey: 'error.internal',
      status: 500,
      category: 'transient'
    });
  });

  it('Should_ReThrowError_When_InterceptorNotifies', () => {
    let caughtError: HttpErrorResponse | undefined;

    httpClient.get('/api/test').subscribe({
      error: (err: HttpErrorResponse) => { caughtError = err; }
    });

    const req = httpTesting.expectOne('/api/test');
    req.flush(
      { errorKey: 'error.scan.not_found' },
      { status: 404, statusText: 'Not Found' }
    );

    expect(caughtError).toBeDefined();
    expect(caughtError!.status).toBe(404);
  });
});
