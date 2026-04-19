import { TestBed } from '@angular/core/testing';
import { ViewMode, ViewModeService } from './view-mode.service';

describe('ViewModeService', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    localStorage.clear();
  });

  function createService(): ViewModeService {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [ViewModeService] });
    return TestBed.inject(ViewModeService);
  }

  it('Should_DefaultToStandard_When_NoStoredMode', () => {
    const service = createService();
    expect(service.viewMode()).toBe('standard');
    expect(service.isLegalMode()).toBe(false);
    expect(service.isGdprMode()).toBe(false);
    expect(service.isNlpdMode()).toBe(false);
  });

  it('Should_LoadGdprMode_When_StoredGdpr', () => {
    localStorage.setItem(ViewModeService.STORAGE_KEY, 'gdpr');
    const service = createService();
    expect(service.viewMode()).toBe('gdpr');
    expect(service.isGdprMode()).toBe(true);
    expect(service.isLegalMode()).toBe(true);
    expect(service.isNlpdMode()).toBe(false);
  });

  it('Should_LoadNlpdMode_When_StoredNlpd', () => {
    localStorage.setItem(ViewModeService.STORAGE_KEY, 'nlpd');
    const service = createService();
    expect(service.viewMode()).toBe('nlpd');
    expect(service.isNlpdMode()).toBe(true);
    expect(service.isLegalMode()).toBe(true);
    expect(service.isGdprMode()).toBe(false);
  });

  it('Should_FallbackToStandard_When_StoredValueIsInvalid', () => {
    localStorage.setItem(ViewModeService.STORAGE_KEY, 'bogus-value');
    const service = createService();
    expect(service.viewMode()).toBe('standard');
  });

  it('Should_PersistMode_When_SetModeCalled', () => {
    const service = createService();
    service.setMode('gdpr');
    expect(service.viewMode()).toBe('gdpr');
    expect(localStorage.getItem(ViewModeService.STORAGE_KEY)).toBe('gdpr');
  });

  it('Should_UpdateComputedFlags_When_ModeChanges', () => {
    const service = createService();

    service.setMode('gdpr');
    expect(service.isGdprMode()).toBe(true);
    expect(service.isNlpdMode()).toBe(false);
    expect(service.isLegalMode()).toBe(true);

    service.setMode('nlpd');
    expect(service.isGdprMode()).toBe(false);
    expect(service.isNlpdMode()).toBe(true);
    expect(service.isLegalMode()).toBe(true);

    service.setMode('standard');
    expect(service.isLegalMode()).toBe(false);
  });

  it('Should_AcceptAllValidModes', () => {
    const service = createService();
    const modes: ViewMode[] = ['standard', 'gdpr', 'nlpd'];
    for (const mode of modes) {
      service.setMode(mode);
      expect(service.viewMode()).toBe(mode);
      expect(localStorage.getItem(ViewModeService.STORAGE_KEY)).toBe(mode);
    }
  });

  it('Should_KeepInMemoryMode_When_LocalStorageThrows', () => {
    const service = createService();
    const originalSetItem = Storage.prototype.setItem;
    Storage.prototype.setItem = () => {
      throw new Error('quota exceeded');
    };
    try {
      service.setMode('gdpr');
      expect(service.viewMode()).toBe('gdpr');
    } finally {
      Storage.prototype.setItem = originalSetItem;
    }
  });
});
