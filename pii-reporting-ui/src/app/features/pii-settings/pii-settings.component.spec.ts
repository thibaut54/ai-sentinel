import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { TranslocoTestingModule } from '@jsverse/transloco';
import { PiiSettingsComponent } from './pii-settings.component';
import { PiiDetectionConfig, PiiTypeConfig } from '../../core/models/pii-detection-config.model';
import { ConfluenceConnectionConfig } from '../../core/models/confluence-connection-config.model';

const MOCK_DETECTOR_CONFIG: PiiDetectionConfig = {
  glinerEnabled: true,
  presidioEnabled: true,
  regexEnabled: true,
  openmedEnabled: false,
  gliner2Enabled: false,
  prefilterEnabled: false,
  llmJudgeEnabled: true,
  glinerJudgeEnabled: true,
  presidioJudgeEnabled: false,
  regexJudgeEnabled: false,
  openmedJudgeEnabled: false,
  gliner2JudgeEnabled: false,
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
      detectorLabel: 'Détecteur',
      judgeLabel: 'Juge LLM',
      judgeAriaLabel: 'Activer le juge LLM pour {{detector}}',
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

  it('Should_CreatePerDetectorJudgeControls_When_FormInitialized', () => {
    // Then - each per-detector judge control exists on the form
    for (const control of [
      'glinerJudgeEnabled',
      'presidioJudgeEnabled',
      'regexJudgeEnabled',
      'openmedJudgeEnabled',
      'gliner2JudgeEnabled',
    ]) {
      expect(component.configForm.get(control)).toBeTruthy();
    }
  });

  it('Should_NotCreateGlobalLlmJudgeControl_When_FormInitialized', () => {
    // Then - the removed global judge control no longer exists on the form
    expect(component.configForm.get('llmJudgeEnabled')).toBeNull();
  });

  it('Should_PatchPerDetectorJudgeControls_When_ConfigLoaded', () => {
    // The MOCK config has the GLINER judge on and the others off.
    expect(component.configForm.get('glinerJudgeEnabled')?.value).toBe(true);
    expect(component.configForm.get('presidioJudgeEnabled')?.value).toBe(false);
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

  it('Should_PreservePerTypeJudgeValue_When_DetectorJudgeToggledOff', () => {
    // Given - the GLINER judge is on (MOCK config)
    expect(component.isDetectorJudgeEnabled('GLINER')).toBe(true);

    // When - the GLINER judge is turned off
    component.configForm.patchValue({ glinerJudgeEnabled: false });

    // Then - the detector judge reads as off and the per-type values are NOT
    // mutated (the view derives the off state; no modification is tracked).
    expect(component.isDetectorJudgeEnabled('GLINER')).toBe(false);
    expect(component.hasUnsavedTypeChanges()).toBe(false);
  });

  it('Should_DefaultToDisabled_When_DetectorJudgeQueriedForUnknownDetector', () => {
    expect(component.isDetectorJudgeEnabled('UNKNOWN')).toBe(false);
  });

  // ========== Helpers ==========

  function seedGlinerType(overrides: Partial<PiiTypeConfig> = {}): PiiTypeConfig {
    const type: PiiTypeConfig = {
      id: 1,
      piiType: 'EMAIL',
      detector: 'GLINER',
      enabled: true,
      threshold: 0.5,
      llmJudgeEnabled: true,
      category: 'CONTACT',
      ...overrides,
    };
    component.groupedPiiTypes.set([
      { detector: 'GLINER', categories: [{ category: 'CONTACT', types: [{ ...type }] }] },
    ]);
    (component as unknown as { originalPiiTypes: { set: (m: Map<string, PiiTypeConfig>) => void } })
      .originalPiiTypes.set(new Map([['GLINER:EMAIL', { ...type }]]));
    return type;
  }

  // ========== PII type modification handlers ==========

  it('Should_TrackModification_When_PiiTypeToggleChanged', () => {
    const type = seedGlinerType({ enabled: true });

    component.onPiiTypeToggleChange(type, false);

    expect(component.hasUnsavedTypeChanges()).toBe(true);
    expect(component.modifiedTypesCount).toBe(1);
  });

  it('Should_TrackModification_When_PiiTypeThresholdChanged', () => {
    const type = seedGlinerType({ threshold: 0.5 });

    component.onPiiTypeThresholdChange(type, 0.9);

    expect(component.hasUnsavedTypeChanges()).toBe(true);
  });

  it('Should_DropModification_When_ThresholdRevertedToOriginal', () => {
    const type = seedGlinerType({ threshold: 0.5 });

    component.onPiiTypeThresholdChange(type, 0.9);
    component.onPiiTypeThresholdChange({ ...type, threshold: 0.9 }, 0.5);

    expect(component.hasUnsavedTypeChanges()).toBe(false);
  });

  it('Should_IgnoreModification_When_OriginalNotFound', () => {
    const orphan: PiiTypeConfig = {
      id: 99,
      piiType: 'UNKNOWN',
      detector: 'REGEX',
      enabled: true,
      threshold: 0.5,
      llmJudgeEnabled: false,
      category: 'CONTACT',
    };

    component.onPiiTypeToggleChange(orphan, false);

    expect(component.hasUnsavedTypeChanges()).toBe(false);
  });

  // ========== onSavePiiTypes ==========

  it('Should_NotCallBackend_When_SavePiiTypesWithoutModifications', () => {
    component.onSavePiiTypes();

    expect(component.saving()).toBe(false);
    httpMock.expectNone('/api/v1/pii-detection/pii-types/bulk');
  });

  it('Should_PersistTypes_When_SavePiiTypesWithModifications', () => {
    const type = seedGlinerType({ threshold: 0.5 });
    component.onPiiTypeThresholdChange(type, 0.9);

    component.onSavePiiTypes();
    expect(component.saving()).toBe(true);

    const req = httpMock.expectOne('/api/v1/pii-detection/pii-types/bulk');
    expect(req.request.method).toBe('PUT');
    req.flush([{ ...type, threshold: 0.9 }]);

    expect(component.saving()).toBe(false);
    expect(component.hasUnsavedTypeChanges()).toBe(false);
  });

  it('Should_StopSaving_When_SavePiiTypesFails', () => {
    const type = seedGlinerType({ threshold: 0.5 });
    component.onPiiTypeThresholdChange(type, 0.9);

    component.onSavePiiTypes();
    httpMock.expectOne('/api/v1/pii-detection/pii-types/bulk').flush('boom', { status: 500, statusText: 'Server Error' });

    expect(component.saving()).toBe(false);
  });

  // ========== onSaveDetectorConfig ==========

  it('Should_MarkPristine_When_SaveDetectorConfigSucceeds', () => {
    component.configForm.patchValue({ defaultThreshold: 0.8 });
    component.configForm.markAsDirty();

    component.onSaveDetectorConfig();
    expect(component.saving()).toBe(true);

    httpMock.expectOne('/api/v1/pii-detection/config').flush({ ...MOCK_DETECTOR_CONFIG, defaultThreshold: 0.8 });

    expect(component.saving()).toBe(false);
    expect(component.configForm.pristine).toBe(true);
  });

  it('Should_AbortSave_When_DetectorConfigInvalid', () => {
    component.configForm.patchValue({ defaultThreshold: 5 });

    component.onSaveDetectorConfig();

    expect(component.saving()).toBe(false);
    httpMock.expectNone('/api/v1/pii-detection/config');
  });

  it('Should_StopSaving_When_SaveDetectorConfigFails', () => {
    component.configForm.patchValue({ defaultThreshold: 0.8 });
    component.configForm.markAsDirty();

    component.onSaveDetectorConfig();
    httpMock.expectOne('/api/v1/pii-detection/config').flush('boom', { status: 500, statusText: 'Server Error' });

    expect(component.saving()).toBe(false);
  });

  // ========== onSaveAll ==========

  it('Should_DoNothing_When_SaveAllWithoutAnyChange', () => {
    component.onSaveAll();

    expect(component.saving()).toBe(false);
    httpMock.expectNone('/api/v1/pii-detection/config');
    httpMock.expectNone('/api/v1/pii-detection/pii-types/bulk');
  });

  it('Should_SaveBothRequests_When_DetectorAndTypesChanged', () => {
    component.configForm.patchValue({ defaultThreshold: 0.8 });
    component.configForm.markAsDirty();
    const type = seedGlinerType({ threshold: 0.5 });
    component.onPiiTypeThresholdChange(type, 0.9);

    let saved = false;
    component.settingsSaved.subscribe(() => (saved = true));

    component.onSaveAll();
    expect(component.saving()).toBe(true);

    httpMock.expectOne('/api/v1/pii-detection/config').flush({ ...MOCK_DETECTOR_CONFIG, defaultThreshold: 0.8 });
    httpMock.expectOne('/api/v1/pii-detection/pii-types/bulk').flush([{ ...type, threshold: 0.9 }]);

    expect(component.saving()).toBe(false);
    expect(component.hasUnsavedTypeChanges()).toBe(false);
    expect(component.configForm.pristine).toBe(true);
    expect(saved).toBe(true);
  });

  it('Should_StopSaving_When_SaveAllFails', () => {
    component.configForm.patchValue({ defaultThreshold: 0.8 });
    component.configForm.markAsDirty();

    component.onSaveAll();
    httpMock.expectOne('/api/v1/pii-detection/config').flush('boom', { status: 500, statusText: 'Server Error' });

    expect(component.saving()).toBe(false);
  });

  // ========== Reset ==========

  it('Should_RestoreFormFromCurrentConfig_When_ResetDetectorConfig', () => {
    component.configForm.patchValue({ defaultThreshold: 0.1, glinerEnabled: false });
    component.configForm.markAsDirty();

    component.onResetDetectorConfig();

    expect(component.configForm.get('defaultThreshold')?.value).toBe(MOCK_DETECTOR_CONFIG.defaultThreshold);
    expect(component.configForm.get('glinerEnabled')?.value).toBe(MOCK_DETECTOR_CONFIG.glinerEnabled);
    expect(component.configForm.pristine).toBe(true);
  });

  it('Should_ClearModifications_When_ResetPiiTypes', () => {
    const type = seedGlinerType({ threshold: 0.5 });
    component.onPiiTypeThresholdChange(type, 0.9);
    expect(component.hasUnsavedTypeChanges()).toBe(true);

    component.onResetPiiTypes();

    expect(component.hasUnsavedTypeChanges()).toBe(false);
    const restored = component.groupedPiiTypes()[0].categories[0].types[0];
    expect(restored.threshold).toBe(0.5);
  });

  it('Should_ResetEverything_When_ResetAll', () => {
    component.configForm.patchValue({ defaultThreshold: 0.1 });
    component.configForm.markAsDirty();
    const type = seedGlinerType({ threshold: 0.5 });
    component.onPiiTypeThresholdChange(type, 0.9);

    component.onResetAll();

    expect(component.configForm.pristine).toBe(true);
    expect(component.hasUnsavedTypeChanges()).toBe(false);
  });

  // ========== Navigation / cancel ==========

  it('Should_EmitCloseDialog_When_CancelInDialogMode', () => {
    fixture.componentRef.setInput('dialogMode', true);
    let closed = false;
    component.closeDialog.subscribe(() => (closed = true));

    component.onCancel();

    expect(closed).toBe(true);
  });

  // ========== Getters ==========

  it('Should_ReflectUnsavedChanges_When_FormDirtyOrTypesModified', () => {
    expect(component.hasUnsavedChanges).toBe(false);

    component.configForm.markAsDirty();

    expect(component.hasUnsavedChanges).toBe(true);
    expect(component.hasDetectorChanges).toBe(true);
  });

  it('Should_BeFormValid_When_FormControlsValid', () => {
    expect(component.isFormValid).toBe(true);
  });

  it('Should_ExposeAtLeastOneDetectorError_When_AllDisabledAndTouched', () => {
    component.configForm.patchValue({
      glinerEnabled: false,
      presidioEnabled: false,
      regexEnabled: false,
      openmedEnabled: false,
      gliner2Enabled: false,
    });
    component.configForm.markAllAsTouched();

    expect(component.atLeastOneDetectorError).toBe(true);
  });

  // ========== Search ==========

  it('Should_FilterTypes_When_SearchTermMatches', () => {
    seedGlinerType({ piiType: 'EMAIL' });

    component.onSearchChange('EMAIL');

    expect(component.hasNoSearchResults()).toBe(false);
    expect(component.filteredPiiTypes().length).toBeGreaterThan(0);
  });

  it('Should_ReportNoResults_When_SearchTermDoesNotMatch', () => {
    seedGlinerType({ piiType: 'EMAIL', countryCode: 'CH' });

    component.onSearchChange('zzz-no-match-zzz');

    expect(component.hasNoSearchResults()).toBe(true);
  });

  it('Should_ResetSearch_When_ClearSearch', () => {
    component.onSearchChange('term');
    expect(component.searchTerm()).toBe('term');

    component.clearSearch();

    expect(component.searchTerm()).toBe('');
    expect(component.hasNoSearchResults()).toBe(false);
  });

  it('Should_ReturnPlainText_When_HighlightWithoutSearchTerm', () => {
    const result = component.highlightText('ignored', 'common.close');

    expect(result).toBe('Fermer');
  });

  it('Should_WrapMatchInMark_When_HighlightWithSearchTerm', () => {
    component.onSearchChange('Fer');

    const result = component.highlightText('ignored', 'common.close');

    expect(result).toContain('<mark class="search-highlight">Fer</mark>');
  });

  // ========== Collapse ==========

  it('Should_ToggleCollapseState_When_ToggleDetectorCollapse', () => {
    expect(component.isDetectorCollapsed('REGEX')).toBe(false);

    component.toggleDetectorCollapse('REGEX');
    expect(component.isDetectorCollapsed('REGEX')).toBe(true);

    component.toggleDetectorCollapse('REGEX');
    expect(component.isDetectorCollapsed('REGEX')).toBe(false);
  });

  // ========== Custom label dialog ==========

  it('Should_OpenAndCloseCustomLabelDialog_When_Toggled', () => {
    component.openAddCustomLabelDialog();
    expect(component.showAddCustomLabelDialog()).toBe(true);

    component.closeAddCustomLabelDialog();
    expect(component.showAddCustomLabelDialog()).toBe(false);
  });

  it('Should_GeneratePiiTypeCode_When_DetectorLabelChanged', () => {
    component.onDetectorLabelChange('Numéro de badge');

    expect(component.customLabelForm.get('piiType')?.value).toBe('NUMERO_DE_BADGE');
  });

  it('Should_AbortCreate_When_CustomLabelFormInvalid', () => {
    component.openAddCustomLabelDialog();

    component.createCustomType();

    expect(component.creatingCustomType()).toBe(false);
    httpMock.expectNone('/api/v1/pii-detection/pii-types');
  });

  it('Should_CreateCustomType_When_CustomLabelFormValid', () => {
    component.customLabelForm.patchValue({
      detectorLabel: 'Badge number',
      piiType: 'BADGE_NUMBER',
      category: 'CUSTOM',
      severity: 'MEDIUM',
      threshold: 0.8,
      countryCode: '',
    });

    component.createCustomType();
    expect(component.creatingCustomType()).toBe(true);

    const req = httpMock.expectOne('/api/v1/pii-detection/pii-types');
    expect(req.request.method).toBe('POST');
    req.flush({ id: 5, piiType: 'BADGE_NUMBER', detector: 'GLINER', enabled: true, threshold: 0.8, llmJudgeEnabled: false, category: 'CUSTOM' });

    // loadAllConfigs is re-triggered on success
    httpMock.expectOne('/api/v1/pii-detection/config').flush(MOCK_DETECTOR_CONFIG);
    httpMock.expectOne('/api/v1/pii-detection/pii-types/grouped').flush([]);

    expect(component.creatingCustomType()).toBe(false);
    expect(component.showAddCustomLabelDialog()).toBe(false);
  });

  it('Should_StopCreating_When_CreateCustomTypeFails', () => {
    component.customLabelForm.patchValue({
      detectorLabel: 'Badge number',
      piiType: 'BADGE_NUMBER',
      category: 'CUSTOM',
      severity: 'MEDIUM',
      threshold: 0.8,
      countryCode: '',
    });

    component.createCustomType();
    httpMock.expectOne('/api/v1/pii-detection/pii-types').flush('boom', { status: 500, statusText: 'Server Error' });

    expect(component.creatingCustomType()).toBe(false);
  });
});
