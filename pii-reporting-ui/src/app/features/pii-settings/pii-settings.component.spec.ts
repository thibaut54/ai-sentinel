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
  openmedEnabled: false,
  gliner2Enabled: false,
  prefilterEnabled: false,
  llmJudgeEnabled: true,
  defaultThreshold: 0.75,
  nbOfLabelByPass: 35,
  updatedAt: '2026-03-16T10:00:00',
};

const MOCK_CONFLUENCE_CONFIG: ConfluenceConnectionConfig = {
  baseUrl: 'https://confluence.example.com',
  username: 'user',
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
      openmed: { label: 'OpenMed', description: 'desc' },
      prefilter: { label: 'Pré-filtre déterministe', description: 'desc' },
      llmJudge: { label: 'LLM as judge', description: 'desc' },
    },
    messages: { loadError: 'Erreur', saveAllSuccess: 'Sauvegardé', saveAllError: 'Erreur' },
    confluence: { messages: { loadError: 'Erreur', saveSuccess: 'OK', saveError: 'Erreur' } },
    actions: { resetAll: 'Reset', cancel: 'Annuler', saveAll: 'Sauvegarder' },
    validation: { atLeastOneDetector: 'Au moins un' },
  },
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

    // Simulate Confluence form dirty state via model signal update
    const confluenceChild = component.confluenceSettings();
    expect(confluenceChild).toBeTruthy();
    confluenceChild!.model.update(m => ({ ...m, baseUrl: 'https://new-url.example.com' }));

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

  it('Should_CreateOpenmedEnabledControl_When_FormInitialized', () => {
    // Then - openmedEnabled control exists and defaults to false
    const openmedControl = component.configForm.get('openmedEnabled');
    expect(openmedControl).toBeTruthy();
    expect(openmedControl?.value).toBe(false);
  });

  it('Should_PassAtLeastOneDetectorValidation_When_OnlyOpenmedEnabled', () => {
    // Given - All other detectors disabled, only openmed enabled
    component.configForm.patchValue({
      glinerEnabled: false,
      presidioEnabled: false,
      regexEnabled: false,
      openmedEnabled: true,
    });

    // Then - Form must not carry atLeastOneDetector error
    expect(component.configForm.hasError('atLeastOneDetector')).toBe(false);
  });

  it('Should_FailAtLeastOneDetectorValidation_When_AllDetectorsDisabled', () => {
    // Given - All detectors disabled (including openmed and gliner2)
    component.configForm.patchValue({
      glinerEnabled: false,
      presidioEnabled: false,
      regexEnabled: false,
      openmedEnabled: false,
      gliner2Enabled: false,
    });

    // Then - Form must carry atLeastOneDetector error
    expect(component.configForm.hasError('atLeastOneDetector')).toBe(true);
  });

  it('Should_CreateGliner2EnabledControl_When_FormInitialized', () => {
    const gliner2Control = component.configForm.get('gliner2Enabled');
    expect(gliner2Control).toBeTruthy();
    expect(gliner2Control?.value).toBe(false);
  });

  it('Should_CreatePrefilterEnabledControl_When_FormInitialized', () => {
    const prefilterControl = component.configForm.get('prefilterEnabled');
    expect(prefilterControl).toBeTruthy();
    expect(prefilterControl?.value).toBe(false);
  });

  it('Should_PassAtLeastOneDetectorValidation_When_OnlyGliner2Enabled', () => {
    component.configForm.patchValue({
      glinerEnabled: false,
      presidioEnabled: false,
      regexEnabled: false,
      openmedEnabled: false,
      gliner2Enabled: true,
    });

    expect(component.configForm.hasError('atLeastOneDetector')).toBe(false);
  });

  it('Should_TrackDescriptionChange_When_Gliner2RowEdited', () => {
    // Given a GLINER2 row registered as original
    const type = {
      id: 1,
      piiType: 'EMAIL',
      detector: 'GLINER2' as const,
      enabled: true,
      threshold: 0.5,
      llmJudgeEnabled: true,
      category: 'CONTACT',
      detectorDescription: 'adresse e-mail',
    };
    component.groupedPiiTypes.set([
      { detector: 'GLINER2', categories: [{ category: 'CONTACT', types: [type] }] },
    ]);
    // Seed originals via the private initializer through loadAllConfigs path:
    (component as unknown as { originalPiiTypes: { set: (m: Map<string, unknown>) => void } })
      .originalPiiTypes.set(new Map([['GLINER2:EMAIL', { ...type }]]));

    // When the description changes
    component.onPiiTypeDescriptionChange(type, 'nouvelle description');

    // Then the row is tracked as modified (drives the bulk save + unsaved indicator)
    expect(component.hasUnsavedTypeChanges()).toBe(true);
  });

  it('Should_CreateLlmJudgeEnabledControl_When_FormInitialized', () => {
    // Then - llmJudgeEnabled control exists and defaults to true
    const llmJudgeControl = component.configForm.get('llmJudgeEnabled');
    expect(llmJudgeControl).toBeTruthy();
    expect(llmJudgeControl?.value).toBe(true);
  });

  it('Should_TrackJudgeToggleChange_When_PiiTypeJudgeToggled', () => {
    // Given a row registered as original with the judge enabled
    const type = {
      id: 1,
      piiType: 'EMAIL',
      detector: 'GLINER' as const,
      enabled: true,
      threshold: 0.5,
      llmJudgeEnabled: true,
      category: 'CONTACT',
    };
    component.groupedPiiTypes.set([
      { detector: 'GLINER', categories: [{ category: 'CONTACT', types: [type] }] },
    ]);
    (component as unknown as { originalPiiTypes: { set: (m: Map<string, unknown>) => void } })
      .originalPiiTypes.set(new Map([['GLINER:EMAIL', { ...type }]]));

    // When the LLM-judge toggle is turned off
    component.onPiiTypeJudgeToggleChange(type, false);

    // Then the row is tracked as modified
    expect(component.hasUnsavedTypeChanges()).toBe(true);
  });

  it('Should_ReflectDetectorMasterState_When_DetectorEnabledQueried', () => {
    // Given - openmed master off, gliner master on (from MOCK config)
    expect(component.isDetectorEnabled('GLINER')).toBe(true);
    expect(component.isDetectorEnabled('OPENMED')).toBe(false);
    // Unknown detector defaults to enabled
    expect(component.isDetectorEnabled('UNKNOWN')).toBe(true);
  });

  it('Should_CollapseDetectorSubSection_When_MasterToggledOff', () => {
    // Given - GLINER master is on and its sub-section expanded
    expect(component.isDetectorCollapsed('GLINER')).toBe(false);

    // When - the master toggle is turned off then the handler runs
    component.configForm.patchValue({ glinerEnabled: false });
    component.onDetectorMasterToggle('GLINER');

    // Then - the GLINER sub-section is collapsed
    expect(component.isDetectorCollapsed('GLINER')).toBe(true);

    // When - the master toggle is turned back on
    component.configForm.patchValue({ glinerEnabled: true });
    component.onDetectorMasterToggle('GLINER');

    // Then - the GLINER sub-section is expanded again
    expect(component.isDetectorCollapsed('GLINER')).toBe(false);
  });

  it('Should_CollapseDisabledDetectorsOnLoad_When_ConfigLoaded', () => {
    // The MOCK config has openmed + gliner2 disabled -> their sub-sections
    // must start collapsed; the enabled ones must start expanded.
    expect(component.isDetectorCollapsed('OPENMED')).toBe(true);
    expect(component.isDetectorCollapsed('GLINER2')).toBe(true);
    expect(component.isDetectorCollapsed('GLINER')).toBe(false);
    expect(component.isDetectorCollapsed('PRESIDIO')).toBe(false);
  });

  it('Should_PreservePerTypeJudgeValue_When_MasterJudgeToggledOff', () => {
    // Given - the global judge master is on (MOCK config)
    expect(component.isLlmJudgeMasterEnabled()).toBe(true);

    // When - the global judge master is turned off
    component.configForm.patchValue({ llmJudgeEnabled: false });

    // Then - the master reads as off and the per-type values are NOT mutated
    // (the view derives the off state; no modification is tracked).
    expect(component.isLlmJudgeMasterEnabled()).toBe(false);
    expect(component.hasUnsavedTypeChanges()).toBe(false);
  });
});
