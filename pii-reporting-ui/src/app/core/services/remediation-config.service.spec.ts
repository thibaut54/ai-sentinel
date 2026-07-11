import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { RemediationConfigService } from './remediation-config.service';

describe('RemediationConfigService', () => {
  let service: RemediationConfigService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    });
    service = TestBed.inject(RemediationConfigService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpTesting.verify();
  });

  it('Should_DefaultToDisabled_When_ConfigNotLoadedYet', () => {
    expect(service.enabled()).toBe(false);
  });

  it('Should_EnableFeature_When_BackendReportsEnabled', () => {
    let emitted: boolean | undefined;
    service.loadRemediationConfig().subscribe((value) => (emitted = value));

    httpTesting.expectOne('/api/v1/pii/remediation/config').flush({ enabled: true });

    expect(service.enabled()).toBe(true);
    expect(emitted).toBe(true);
  });

  it('Should_KeepFeatureDisabled_When_BackendReportsDisabled', () => {
    let emitted: boolean | undefined;
    service.loadRemediationConfig().subscribe((value) => (emitted = value));

    httpTesting.expectOne('/api/v1/pii/remediation/config').flush({ enabled: false });

    expect(service.enabled()).toBe(false);
    expect(emitted).toBe(false);
  });

  it('Should_FallBackToDisabled_When_ConfigLoadFails', () => {
    let emitted: boolean | undefined;
    service.loadRemediationConfig().subscribe((value) => (emitted = value));

    httpTesting
      .expectOne('/api/v1/pii/remediation/config')
      .flush('boom', { status: 500, statusText: 'Server Error' });

    expect(service.enabled()).toBe(false);
    expect(emitted).toBe(false);
  });
});
