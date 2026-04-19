import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { TranslocoTestingModule } from '@jsverse/transloco';
import { PiiSettingsComponent } from './pii-settings.component';
import { PiiDetectionConfig } from '../../core/models/pii-detection-config.model';
import { ConfluenceConnectionConfig } from '../../core/models/confluence-connection-config.model';

const MOCK_DETECTOR_CONFIG: PiiDetectionConfig = {
  glinerEnabled: true,
  presidioEnabled: true,
  regexEnabled: true,
  defaultThreshold: 0.75,
  nbOfLabelByPass: 35,
  updatedAt: '2026-03-16T10:00:00',
};

const MOCK_CONFLUENCE_CONFIG: ConfluenceConnectionConfig = {
  baseUrl: 'https://confluence.example.com',
  username: 'user',
  apiTokenMasked: '****',
  connectTimeout: 30000,
  readTimeout: 60000,
  maxRetries: 3,
  pagesLimit: 25,
  maxPages: 1000,
  deploymentType: 'CLOUD',
  configured: true,
};

const FR_TRANSLATIONS = {
  common: { success: 'Succès', error: 'Erreur', warning: 'Attention', close: 'Fermer' },
  settings: {
    nav: { detectors: 'Détecteurs', thresholds: 'Seuils', piiTypes: 'Types PII', confluence: 'Confluence' },
    sections: { detectors: 'Détecteurs', threshold: 'Seuils', performance: 'Perf' },
    detectors: {
      gliner: { label: 'GLiNER', description: 'desc' },
      presidio: { label: 'Presidio', description: 'desc' },
      regex: { label: 'Regex', description: 'desc' },
    },
    messages: { loadError: 'Erreur', saveAllSuccess: 'Sauvegardé', saveAllError: 'Erreur' },
    confluence: { messages: { loadError: 'Erreur', saveSuccess: 'OK', saveError: 'Erreur' } },
    actions: { resetAll: 'Reset', cancel: 'Annuler', saveAll: 'Sauvegarder' },
    validation: { atLeastOneDetector: 'Au moins un' },
  },
  viewMode: { label: 'Mode', standard: 'Standard', gdpr: 'RGPD', nlpd: 'nLPD' },
  gdpr: { classification: {
    SPECIAL_CATEGORY: { label: 'Cat speciale', short: 'Art. 9' },
    CRIMINAL_DATA: { label: 'Donnees penales', short: 'Art. 10' },
    PERSONAL_DATA_HIGH_RISK: { label: 'Haut risque', short: 'Haut' },
    PERSONAL_DATA: { label: 'Donnees perso', short: 'Art. 6' },
  }},
  nlpd: { classification: {
    SENSITIVE_DATA: { label: 'Sensibles', short: 'Art. 5c' },
    HIGH_RISK_PROFILING_DATA: { label: 'Profilage', short: 'Art. 5g' },
    PERSONAL_DATA_HIGH_RISK: { label: 'Haut risque', short: 'Haut' },
    PERSONAL_DATA: { label: 'Donnees perso', short: 'Art. 5a' },
  }},
};

describe('PiiSettingsComponent', () => {
  let component: PiiSettingsComponent;
  let fixture: ComponentFixture<PiiSettingsComponent>;
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [
        PiiSettingsComponent,
        TranslocoTestingModule.forRoot({
          langs: { fr: FR_TRANSLATIONS },
          translocoConfig: { availableLangs: ['fr'], defaultLang: 'fr' },
        }),
      ],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
      ],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(PiiSettingsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();

    // ClassificationService triggers a GET on /api/v1/pii-detection/pii-types at construction.
    httpMock.match('/api/v1/pii-detection/pii-types').forEach(
      (req: TestRequest) => req.flush([])
    );
    // Respond to initial config loads
    httpMock.expectOne('/api/v1/pii-detection/config').flush(MOCK_DETECTOR_CONFIG);
    httpMock.expectOne('/api/v1/pii-detection/pii-types/grouped').flush([]);
    // Confluence child may or may not load depending on section visibility
    httpMock.match('/api/v1/confluence/connection-config').forEach(
      (req: TestRequest) => req.flush(MOCK_CONFLUENCE_CONFIG)
    );

    fixture.detectChanges();
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('Should_NotStickOnSpinner_When_OnlyConfluenceSettingsChanged', () => {
    // Given - Navigate to Confluence section to render child component
    component.setActiveSection('confluence');
    fixture.detectChanges();

    // Handle Confluence config load triggered by section change
    httpMock.match('/api/v1/confluence/connection-config').forEach(
      (req: TestRequest) => req.flush(MOCK_CONFLUENCE_CONFIG)
    );
    fixture.detectChanges();

    // Simulate Confluence form dirty state
    const confluenceChild = component.confluenceSettings();
    expect(confluenceChild).toBeTruthy();
    confluenceChild!.configForm.markAsDirty();
    confluenceChild!.configForm.patchValue({ baseUrl: 'https://new-url.example.com' });

    // Ensure parent form is NOT dirty (only Confluence changed)
    expect(component.configForm.dirty).toBe(false);
    expect(component.hasUnsavedTypeChanges()).toBe(false);

    // When - User clicks "Save All"
    component.onSaveAll();

    // Then - Parent saving signal should remain false (nothing to save on parent side)
    // onSaveAll() returns immediately when neither configForm is dirty nor types have changed
    expect(component.saving()).toBe(false);

    // Confluence child handles its own save via its own HTTP call
    const confluencePutReqs = httpMock.match('/api/v1/confluence/connection-config');
    confluencePutReqs.forEach(
      (req: TestRequest) =>
        req.flush({ ...MOCK_CONFLUENCE_CONFIG, baseUrl: 'https://new-url.example.com' })
    );

    fixture.detectChanges();

    // Verify Confluence child saving is also resolved
    expect(confluenceChild!.saving()).toBe(false);
  });

  it('Should_SaveDetectorChanges_When_DetectorConfigModified', () => {
    // Given - Detector config changed
    component.configForm.patchValue({ defaultThreshold: 0.9 });
    component.configForm.markAsDirty();

    // When
    component.onSaveAll();

    // Then - saving should be true while request in flight
    expect(component.saving()).toBe(true);

    const req = httpMock.expectOne('/api/v1/pii-detection/config');
    expect(req.request.method).toBe('PUT');
    req.flush({ ...MOCK_DETECTOR_CONFIG, defaultThreshold: 0.9 });

    // saving should be false after response (flush is synchronous)
    expect(component.saving()).toBe(false);
  });

  it('Should_IncludeClassifications_When_CreatingCustomType', () => {
    // Given - open the dialog (initialises defaults to PERSONAL_DATA / PERSONAL_DATA)
    component.openAddCustomLabelDialog();
    component.customLabelForm.patchValue({
      detectorLabel: 'employee badge',
      piiType: 'EMPLOYEE_BADGE',
      category: 'CUSTOM',
      severity: 'MEDIUM',
      threshold: 0.8,
    });

    // When
    component.createCustomType();

    // Then - request should contain gdpr + nlpd classifications
    const req = httpMock.expectOne('/api/v1/pii-detection/pii-types');
    expect(req.request.method).toBe('POST');
    expect(req.request.body.gdprClassification).toBe('PERSONAL_DATA');
    expect(req.request.body.nlpdClassification).toBe('PERSONAL_DATA');
    req.flush({
      id: 1,
      piiType: 'EMPLOYEE_BADGE',
      detector: 'GLINER',
      enabled: true,
      threshold: 0.8,
      category: 'CUSTOM',
      gdprClassification: 'PERSONAL_DATA',
      nlpdClassification: 'PERSONAL_DATA',
    });
    // Second reload triggered after success
    httpMock.match(() => true).forEach((pending: TestRequest) => {
      if (!pending.cancelled) pending.flush([]);
    });
  });

  it('Should_AutoMapNlpd_When_GdprChanges', () => {
    // Given - dialog open
    component.openAddCustomLabelDialog();

    // When - choose a GDPR SPECIAL_CATEGORY
    component.customLabelForm.get('gdprClassification')!.setValue('SPECIAL_CATEGORY');

    // Then - nLPD must be auto-mapped to SENSITIVE_DATA
    expect(component.customLabelForm.get('nlpdClassification')!.value).toBe('SENSITIVE_DATA');
  });

  it('Should_NotAutoMapGdpr_When_NlpdChangesAndModeIsStandard', () => {
    // Given - default mode is 'standard' and dialog open
    component.viewModeService.setMode('standard');
    component.openAddCustomLabelDialog();
    component.customLabelForm.patchValue(
      { gdprClassification: 'PERSONAL_DATA' },
      { emitEvent: false }
    );

    // When - user changes nLPD directly
    component.customLabelForm.get('nlpdClassification')!.setValue('SENSITIVE_DATA');

    // Then - GDPR should NOT auto-change in standard mode
    expect(component.customLabelForm.get('gdprClassification')!.value).toBe('PERSONAL_DATA');
  });

  it('Should_AutoMapGdpr_When_NlpdChangesAndModeIsNlpd', () => {
    // Given - user switches to nLPD mode
    component.viewModeService.setMode('nlpd');
    component.openAddCustomLabelDialog();

    // When - user picks SENSITIVE_DATA
    component.customLabelForm.get('nlpdClassification')!.setValue('SENSITIVE_DATA');

    // Then - GDPR must be auto-mapped to SPECIAL_CATEGORY (conservative default)
    expect(component.customLabelForm.get('gdprClassification')!.value).toBe('SPECIAL_CATEGORY');
  });
});
