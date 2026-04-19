import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { ClassificationService } from './classification.service';
import { PiiDetectionConfigService } from './pii-detection-config.service';
import {
  GdprDataClassification,
  NlpdDataClassification,
  PiiTypeConfig
} from '../models/pii-detection-config.model';

function buildConfig(
  piiType: string,
  gdpr?: GdprDataClassification,
  nlpd?: NlpdDataClassification
): PiiTypeConfig {
  return {
    id: 1,
    piiType,
    detector: 'GLINER',
    enabled: true,
    threshold: 0.8,
    category: 'IDENTITY',
    gdprClassification: gdpr,
    nlpdClassification: nlpd,
  };
}

describe('ClassificationService', () => {
  function setup(configs: PiiTypeConfig[] | 'error'): ClassificationService {
    const stub = {
      getAllPiiTypeConfigs: () =>
        configs === 'error'
          ? throwError(() => new Error('backend down'))
          : of(configs),
    };
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        ClassificationService,
        { provide: PiiDetectionConfigService, useValue: stub },
      ],
    });
    return TestBed.inject(ClassificationService);
  }

  describe('loading', () => {
    it('Should_PopulateMaps_When_BackendReturnsConfigs', () => {
      const service = setup([
        buildConfig('EMAIL', 'PERSONAL_DATA', 'PERSONAL_DATA'),
        buildConfig('DIAGNOSIS', 'SPECIAL_CATEGORY', 'SENSITIVE_DATA'),
      ]);

      expect(service.getGdprClassification('DIAGNOSIS')).toBe('SPECIAL_CATEGORY');
      expect(service.getNlpdClassification('DIAGNOSIS')).toBe('SENSITIVE_DATA');
      expect(service.getGdprClassification('EMAIL')).toBe('PERSONAL_DATA');
      expect(service.getNlpdClassification('EMAIL')).toBe('PERSONAL_DATA');
      expect(service.isLoaded()).toBe(true);
    });

    it('Should_FallbackToPersonalData_When_PiiTypeUnknown', () => {
      const service = setup([]);
      expect(service.getGdprClassification('UNKNOWN')).toBe('PERSONAL_DATA');
      expect(service.getNlpdClassification('UNKNOWN')).toBe('PERSONAL_DATA');
    });

    it('Should_FallbackToPersonalData_When_FieldsMissingOnResponse', () => {
      const service = setup([buildConfig('IBAN', undefined, undefined)]);
      expect(service.getGdprClassification('IBAN')).toBe('PERSONAL_DATA');
      expect(service.getNlpdClassification('IBAN')).toBe('PERSONAL_DATA');
    });

    it('Should_StillReportLoaded_When_BackendFails', () => {
      const service = setup('error');
      expect(service.isLoaded()).toBe(true);
      expect(service.getGdprClassification('EMAIL')).toBe('PERSONAL_DATA');
    });
  });

  describe('mapGdprToNlpd', () => {
    it('Should_MapSpecialCategoryToSensitive', () => {
      const service = setup([]);
      expect(service.mapGdprToNlpd('SPECIAL_CATEGORY')).toBe('SENSITIVE_DATA');
    });

    it('Should_MapCriminalDataToSensitive', () => {
      const service = setup([]);
      expect(service.mapGdprToNlpd('CRIMINAL_DATA')).toBe('SENSITIVE_DATA');
    });

    it('Should_MapPersonalDataHighRiskSymmetrically', () => {
      const service = setup([]);
      expect(service.mapGdprToNlpd('PERSONAL_DATA_HIGH_RISK')).toBe(
        'PERSONAL_DATA_HIGH_RISK'
      );
    });

    it('Should_MapPersonalDataSymmetrically', () => {
      const service = setup([]);
      expect(service.mapGdprToNlpd('PERSONAL_DATA')).toBe('PERSONAL_DATA');
    });
  });

  describe('mapNlpdToGdpr', () => {
    it('Should_MapSensitiveToSpecialCategory', () => {
      const service = setup([]);
      expect(service.mapNlpdToGdpr('SENSITIVE_DATA')).toBe('SPECIAL_CATEGORY');
    });

    it('Should_MapHighRiskProfilingToPersonalDataHighRisk', () => {
      const service = setup([]);
      expect(service.mapNlpdToGdpr('HIGH_RISK_PROFILING_DATA')).toBe(
        'PERSONAL_DATA_HIGH_RISK'
      );
    });

    it('Should_MapPersonalDataHighRiskSymmetrically', () => {
      const service = setup([]);
      expect(service.mapNlpdToGdpr('PERSONAL_DATA_HIGH_RISK')).toBe(
        'PERSONAL_DATA_HIGH_RISK'
      );
    });

    it('Should_MapPersonalDataSymmetrically', () => {
      const service = setup([]);
      expect(service.mapNlpdToGdpr('PERSONAL_DATA')).toBe('PERSONAL_DATA');
    });
  });

  describe('refresh', () => {
    it('Should_UpdateMaps_When_RefreshCalled', () => {
      const service = setup([buildConfig('EMAIL', 'PERSONAL_DATA', 'PERSONAL_DATA')]);
      expect(service.getGdprClassification('EMAIL')).toBe('PERSONAL_DATA');

      // Replace stub response for next call.
      const injected = TestBed.inject(PiiDetectionConfigService);
      (injected as unknown as { getAllPiiTypeConfigs: () => unknown }).getAllPiiTypeConfigs =
        () => of([buildConfig('EMAIL', 'PERSONAL_DATA_HIGH_RISK', 'PERSONAL_DATA_HIGH_RISK')]);

      service.refresh();
      expect(service.getGdprClassification('EMAIL')).toBe('PERSONAL_DATA_HIGH_RISK');
      expect(service.getNlpdClassification('EMAIL')).toBe('PERSONAL_DATA_HIGH_RISK');
    });
  });
});
