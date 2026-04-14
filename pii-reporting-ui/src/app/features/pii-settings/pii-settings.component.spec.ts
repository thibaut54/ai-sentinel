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
  llmValidationEnabled: false,
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
      llmValidation: { label: 'Double vérification IA (Gemma 4)', description: 'desc' },
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
});
