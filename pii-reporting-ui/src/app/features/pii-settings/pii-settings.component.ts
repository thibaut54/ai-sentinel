import { Component, computed, input, OnInit, output, SecurityContext, signal, viewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { DomSanitizer } from '@angular/platform-browser';
import {
    AbstractControlOptions,
    FormBuilder,
    FormGroup,
    FormsModule,
    ReactiveFormsModule,
    Validators
} from '@angular/forms';
import { Router } from '@angular/router';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import { ButtonModule } from 'primeng/button';
import { ToggleSwitchModule } from 'primeng/toggleswitch';
import { InputNumberModule } from 'primeng/inputnumber';
import { MessageModule } from 'primeng/message';
import { ProgressSpinnerModule } from 'primeng/progressspinner';
import { ToastModule } from 'primeng/toast';
import { IconFieldModule } from 'primeng/iconfield';
import { InputIconModule } from 'primeng/inputicon';
import { InputTextModule } from 'primeng/inputtext';
import { SelectModule } from 'primeng/select';
import { ConfirmDialogModule } from 'primeng/confirmdialog';
import { DialogModule } from 'primeng/dialog';
import { ConfirmationService, MessageService } from 'primeng/api';
import { PiiDetectionConfigService } from '../../core/services/pii-detection-config.service';
import {
    CreatePiiTypeConfigRequest,
    GroupedPiiTypes,
    PiiDetectionConfig,
    PiiTypeConfig,
    UpdatePiiTypeConfigRequest
} from '../../core/models/pii-detection-config.model';
import { forkJoin, Observable } from 'rxjs';
import { ConfluenceSettingsComponent } from '../confluence-settings/confluence-settings.component';

type SettingsSection = 'detectors' | 'thresholds' | 'pii_types' | 'confluence';

/**
 * Settings page for PII detection configuration.
 * Manages detector-level settings and individual PII type configurations.
 */
@Component({
  selector: 'app-pii-settings',
  templateUrl: './pii-settings.component.html',
  styleUrl: './pii-settings.component.css',
  standalone: true,
    imports: [
        CommonModule,
        FormsModule,
        ReactiveFormsModule,
        TranslocoModule,
        ButtonModule,
        ToggleSwitchModule,
        InputNumberModule,
        MessageModule,
        ProgressSpinnerModule,
        ToastModule,
        IconFieldModule,
        InputIconModule,
        InputTextModule,
        SelectModule,
        ConfirmDialogModule,
        DialogModule,
        ConfluenceSettingsComponent
    ],
  providers: [MessageService, ConfirmationService]
})
export class PiiSettingsComponent implements OnInit {
  readonly dialogMode = input(false);
  readonly initialTab = input(0);
  readonly closeDialog = output();
  readonly settingsSaved = output();

  readonly confluenceSettings = viewChild(ConfluenceSettingsComponent);

  configForm!: FormGroup;
  loading = signal(false);
  saving = signal(false);
  currentConfig = signal<PiiDetectionConfig | null>(null);

  // PII type configurations
  groupedPiiTypes = signal<GroupedPiiTypes[]>([]);
  originalPiiTypes = signal<Map<string, PiiTypeConfig>>(new Map());
  modifiedPiiTypes = signal<Map<string, PiiTypeConfig>>(new Map());

  // Sidebar navigation
  activeSection = signal<SettingsSection>('detectors');

  // Collapsible detector groups in PII types section
  collapsedDetectors = signal<Set<string>>(new Set());

  // Search functionality
  searchTerm = signal<string>('');

  // Computed signal for filtered PII types based on search
  filteredPiiTypes = computed(() => {
    const term = this.searchTerm().toLowerCase().trim();
    if (!term) {
      return this.groupedPiiTypes();
    }

    return this.groupedPiiTypes()
      .map(detectorGroup => {
        const filteredCategories = detectorGroup.categories
          .map(categoryGroup => {
            const filteredTypes = categoryGroup.types.filter(type => {
              // Get translated name and description
              const typeName = this.translocoService.translate(`settings.piiTypes.typeNames.${type.piiType}`).toLowerCase();
              const typeDescription = this.translocoService.translate(`settings.piiTypes.typeDescriptions.${type.piiType}`).toLowerCase();
              const countryCode = (type.countryCode || '').toLowerCase();

              // Search in name, description, and country code
              return typeName.includes(term) ||
                typeDescription.includes(term) ||
                countryCode.includes(term);
            });

            return filteredTypes.length > 0 ? {
              ...categoryGroup,
              types: filteredTypes
            } : null;
          })
          .filter(cat => cat !== null) as typeof detectorGroup.categories;

        return filteredCategories.length > 0 ? {
          ...detectorGroup,
          categories: filteredCategories
        } : null;
      })
      .filter(det => det !== null) as GroupedPiiTypes[];
  });

  // Computed signal for checking if there are no results
  hasNoSearchResults = computed(() => {
    const term = this.searchTerm().trim();
    if (!term) return false;

    const filtered = this.filteredPiiTypes();
    return filtered.every(det => det.categories.every(cat => cat.types.length === 0));
  });

  // Computed signal for unsaved changes
  hasUnsavedTypeChanges = computed(() => this.modifiedPiiTypes().size > 0);

  // Custom label management
  showAddCustomLabelDialog = signal(false);
  customLabelForm!: FormGroup;
  creatingCustomType = signal(false);

  categoryOptions = [
    { label: 'CONTACT', value: 'CONTACT' },
    { label: 'IDENTITY', value: 'IDENTITY' },
    { label: 'FINANCIAL', value: 'FINANCIAL' },
    { label: 'GOVERNMENT_ID', value: 'GOVERNMENT_ID' },
    { label: 'LOCATION', value: 'LOCATION' },
    { label: 'SECURITY', value: 'SECURITY' },
    { label: 'NETWORK', value: 'NETWORK' },
    { label: 'MEDICAL', value: 'MEDICAL' },
    { label: 'BUSINESS', value: 'BUSINESS' },
    { label: 'DIGITAL', value: 'DIGITAL' },
    { label: 'CUSTOM', value: 'CUSTOM' }
  ];

  severityOptions = [
    { label: 'HIGH', value: 'HIGH' },
    { label: 'MEDIUM', value: 'MEDIUM' },
    { label: 'LOW', value: 'LOW' }
  ];

  constructor(
    private readonly fb: FormBuilder,
    private readonly configService: PiiDetectionConfigService,
    private readonly messageService: MessageService,
    private readonly confirmationService: ConfirmationService,
    private readonly translocoService: TranslocoService,
    private readonly router: Router,
    private readonly sanitizer: DomSanitizer
  ) {
    this.initForm();
    this.initCustomLabelForm();
  }

  /** Maps tab indices to sidebar section identifiers. */
  private static readonly TAB_TO_SECTION: ReadonlyArray<SettingsSection> = [
    'detectors', 'thresholds', 'pii_types', 'confluence'
  ];

  /**
   * Maps a "Types d'IPI" detector group to its master toggle in the
   * "Moteur de détection" section. The PII-type rows of a detector are
   * derived (disabled + collapsed) from the state of this control so the
   * two sections stay consistent without ever overwriting the per-type
   * stored values (customisations are preserved by construction).
   */
  private static readonly DETECTOR_MASTER_CONTROL: Readonly<Record<string, string>> = {
    GLINER: 'glinerEnabled',
    PRESIDIO: 'presidioEnabled',
    REGEX: 'regexEnabled',
    OPENMED: 'openmedEnabled',
    GLINER2: 'gliner2Enabled'
  };

  ngOnInit(): void {
    const tabIndex = this.initialTab();
    const section = PiiSettingsComponent.TAB_TO_SECTION[tabIndex];
    if (section) {
      this.activeSection.set(section);
    }

    this.loadAllConfigs();
  }

  private initForm(): void {
    this.configForm = this.fb.group({
      glinerEnabled: [true],
      presidioEnabled: [true],
      regexEnabled: [true],
      openmedEnabled: [false],
      gliner2Enabled: [false],
      prefilterEnabled: [false],
      llmJudgeEnabled: [true],
      defaultThreshold: [0.75, [Validators.required, Validators.min(0), Validators.max(1)]],
      nbOfLabelByPass: [35, [Validators.required, Validators.min(1), Validators.max(100)]]
    }, {
      validators: [this.atLeastOneDetectorValidator]
    } as AbstractControlOptions);
  }

  private initCustomLabelForm(): void {
    this.customLabelForm = this.fb.group({
      detectorLabel: ['', [Validators.required, Validators.minLength(2)]],
      piiType: ['', [Validators.required, Validators.pattern(/^[A-Z][A-Z0-9_]*$/)]],
      category: ['CUSTOM', [Validators.required]],
      severity: ['MEDIUM', [Validators.required]],
      threshold: [0.8, [Validators.required, Validators.min(0), Validators.max(1)]],
      countryCode: ['']
    });
  }

  /**
   * Auto-generate PII type code from detector label.
   * "employee badge number" -> "EMPLOYEE_BADGE_NUMBER"
   * "Numéro de badge" -> "NUMERO_DE_BADGE"
   */
  onDetectorLabelChange(label: string): void {
    const piiType = label
      .trim()
      .normalize('NFD')
      .replaceAll(/[\u0300-\u036f]/g, '')
      .toUpperCase()
      .replaceAll(/[^A-Z0-9\s]/g, '')
      .replaceAll(/\s+/g, '_');
    this.customLabelForm.patchValue({ piiType });
  }

  openAddCustomLabelDialog(): void {
    this.customLabelForm.reset({
      detectorLabel: '',
      piiType: '',
      category: 'CUSTOM',
      severity: 'MEDIUM',
      threshold: 0.8,
      countryCode: ''
    });
    this.showAddCustomLabelDialog.set(true);
  }

  closeAddCustomLabelDialog(): void {
    this.customLabelForm.reset({
      detectorLabel: '',
      piiType: '',
      category: 'CUSTOM',
      severity: 'MEDIUM',
      threshold: 0.8,
      countryCode: ''
    });
    this.showAddCustomLabelDialog.set(false);
  }

  createCustomType(): void {
    if (this.customLabelForm.invalid) {
      this.customLabelForm.markAllAsTouched();
      return;
    }

    this.creatingCustomType.set(true);
    const formValue = this.customLabelForm.value;

    const request: CreatePiiTypeConfigRequest = {
      piiType: formValue.piiType,
      detector: 'GLINER',
      enabled: true,
      threshold: formValue.threshold,
      category: formValue.category,
      detectorLabel: formValue.detectorLabel,
      severity: formValue.severity,
      countryCode: formValue.countryCode || undefined
    };

    this.configService.createPiiTypeConfig(request).subscribe({
      next: () => {
        this.messageService.add({
          severity: 'success',
          summary: this.translocoService.translate('common.success'),
          detail: this.translocoService.translate('settings.customLabels.messages.createSuccess', { label: formValue.detectorLabel }),
          life: 3000
        });
        this.creatingCustomType.set(false);
        this.showAddCustomLabelDialog.set(false);
        this.loadAllConfigs();
      },
      error: (err) => {
        console.error('Failed to create custom PII type:', err);
        this.messageService.add({
          severity: 'error',
          summary: this.translocoService.translate('common.error'),
          detail: this.translocoService.translate('settings.customLabels.messages.createError', { error: err.error?.message || err.message || 'Unknown error' }),
          life: 5000
        });
        this.creatingCustomType.set(false);
      }
    });
  }

  confirmDeleteCustomType(type: PiiTypeConfig): void {
    const typeName = type.detectorLabel || type.piiType;
    this.confirmationService.confirm({
      header: this.translocoService.translate('settings.customLabels.deleteConfirm.header'),
      message: this.translocoService.translate('settings.customLabels.deleteConfirm.message', { label: typeName }),
      acceptLabel: this.translocoService.translate('common.yes'),
      rejectLabel: this.translocoService.translate('common.no'),
      acceptButtonStyleClass: 'p-button-danger',
      accept: () => this.deleteCustomType(type)
    });
  }

  private deleteCustomType(type: PiiTypeConfig): void {
    this.configService.deletePiiTypeConfig(type.detector, type.piiType).subscribe({
      next: () => {
        this.messageService.add({
          severity: 'success',
          summary: this.translocoService.translate('common.success'),
          detail: this.translocoService.translate('settings.customLabels.messages.deleteSuccess', { label: type.detectorLabel || type.piiType }),
          life: 3000
        });
        this.loadAllConfigs();
      },
      error: (err) => {
        console.error('Failed to delete custom PII type:', err);
        this.messageService.add({
          severity: 'error',
          summary: this.translocoService.translate('common.error'),
          detail: this.translocoService.translate('settings.customLabels.messages.deleteError', { error: err.error?.message || err.message || 'Unknown error' }),
          life: 5000
        });
      }
    });
  }

  /**
   * Custom validator: at least one detector must be enabled.
   */
  private atLeastOneDetectorValidator(group: FormGroup): {[key: string]: boolean} | null {
    const gliner = group.get('glinerEnabled')?.value;
    const presidio = group.get('presidioEnabled')?.value;
    const regex = group.get('regexEnabled')?.value;
    const openmed = group.get('openmedEnabled')?.value;
    const gliner2 = group.get('gliner2Enabled')?.value;

    if (!gliner && !presidio && !regex && !openmed && !gliner2) {
      return {atLeastOneDetector: true};
    }
    return null;
  }

  /**
   * Load all configurations: detector-level and PII types.
   */
  private loadAllConfigs(): void {
    this.loading.set(true);

    forkJoin([
      this.configService.getConfig(),
      this.configService.getPiiTypesGroupedForUI()
    ]).subscribe({
      next: ([detectorConfig, piiTypes]) => {
        // Set detector config
        this.currentConfig.set(detectorConfig);
        this.configForm.patchValue({
          glinerEnabled: detectorConfig.glinerEnabled,
          presidioEnabled: detectorConfig.presidioEnabled,
          regexEnabled: detectorConfig.regexEnabled,
          openmedEnabled: detectorConfig.openmedEnabled,
          gliner2Enabled: detectorConfig.gliner2Enabled,
          prefilterEnabled: detectorConfig.prefilterEnabled,
          llmJudgeEnabled: detectorConfig.llmJudgeEnabled,
          defaultThreshold: detectorConfig.defaultThreshold,
          nbOfLabelByPass: detectorConfig.nbOfLabelByPass
        });

        // Set PII types
        this.groupedPiiTypes.set(piiTypes);
        this.initializeOriginalPiiTypes(piiTypes);

        // Collapse the sub-sections of detectors that are disabled, so the
        // "Types d'IPI" view reflects the "Moteur de détection" state on load.
        this.syncCollapsedWithDetectorState();

        this.loading.set(false);
      },
      error: (err) => {
        console.error('Failed to load configurations:', err);
        const errorMsg = this.translocoService.translate('settings.messages.loadError', {error: err.message || 'Unknown error'});
        this.messageService.add({
          severity: 'error',
          summary: this.translocoService.translate('common.error'),
          detail: errorMsg,
          life: 5000
        });
        this.loading.set(false);
      }
    });
  }

  /**
   * Store original PII types for comparison.
   */
  private initializeOriginalPiiTypes(grouped: GroupedPiiTypes[]): void {
    const originalMap = new Map<string, PiiTypeConfig>();

    grouped.forEach(detectorGroup => {
      detectorGroup.categories.forEach(categoryGroup => {
        categoryGroup.types.forEach(type => {
          const key = this.getPiiTypeKey(type.detector, type.piiType);
          originalMap.set(key, {...type});
        });
      });
    });

    this.originalPiiTypes.set(originalMap);
    this.modifiedPiiTypes.set(new Map()); // Clear modifications
  }

  /**
   * Generate unique key for PII type.
   */
  private getPiiTypeKey(detector: string, piiType: string): string {
    return `${detector}:${piiType}`;
  }

  /**
   * Handle toggle change for a PII type.
   */
  onPiiTypeToggleChange(type: PiiTypeConfig, enabled: boolean): void {
    const key = this.getPiiTypeKey(type.detector, type.piiType);
    const original = this.originalPiiTypes().get(key);

    if (!original) return;

    // Create modified config
    const modified: PiiTypeConfig = {
      ...type,
      enabled
    };

    this.trackPiiTypeModification(key, original, modified);

    // Update the grouped data to reflect changes
    this.updateGroupedPiiTypes(type.detector, type.piiType, {enabled});
  }

  /**
   * Handle threshold change for a PII type.
   */
  onPiiTypeThresholdChange(type: PiiTypeConfig, threshold: number): void {
    const key = this.getPiiTypeKey(type.detector, type.piiType);
    const original = this.originalPiiTypes().get(key);

    if (!original) return;

    // Create modified config
    const modified: PiiTypeConfig = {
      ...type,
      threshold
    };

    this.trackPiiTypeModification(key, original, modified);

    // Update the grouped data to reflect changes
    this.updateGroupedPiiTypes(type.detector, type.piiType, {threshold});
  }

  /**
   * Handle LLM-judge toggle change for a PII type.
   * Reuses the same modification key as enabled/threshold so all edits of a row
   * merge into a SINGLE bulk update entry.
   */
  onPiiTypeJudgeToggleChange(type: PiiTypeConfig, llmJudgeEnabled: boolean): void {
    const key = this.getPiiTypeKey(type.detector, type.piiType);
    const original = this.originalPiiTypes().get(key);

    if (!original) return;

    const modified: PiiTypeConfig = {
      ...type,
      llmJudgeEnabled
    };

    this.trackPiiTypeModification(key, original, modified);

    this.updateGroupedPiiTypes(type.detector, type.piiType, {llmJudgeEnabled});
  }

  /**
   * Handle inference-description change for a GLINER2 PII type.
   * Reuses the same modification key as toggle/threshold so all edits of a row
   * merge into a SINGLE bulk update entry.
   */
  onPiiTypeDescriptionChange(type: PiiTypeConfig, detectorDescription: string): void {
    const key = this.getPiiTypeKey(type.detector, type.piiType);
    const original = this.originalPiiTypes().get(key);

    if (!original) return;

    const modified: PiiTypeConfig = {
      ...type,
      detectorDescription
    };

    this.trackPiiTypeModification(key, original, modified);

    this.updateGroupedPiiTypes(type.detector, type.piiType, {detectorDescription});
  }

  /**
   * Track a PII-type row modification: record it when it differs from the
   * original (enabled, threshold, LLM-judge toggle or description), drop it when it matches again.
   * Always emits a new Map reference so the signal recomputes.
   */
  private trackPiiTypeModification(
    key: string,
    original: PiiTypeConfig,
    modified: PiiTypeConfig
  ): void {
    const changed =
      modified.enabled !== original.enabled ||
      modified.threshold !== original.threshold ||
      modified.llmJudgeEnabled !== original.llmJudgeEnabled ||
      (modified.detectorDescription ?? '') !== (original.detectorDescription ?? '');

    if (changed) {
      this.modifiedPiiTypes().set(key, modified);
    } else {
      this.modifiedPiiTypes().delete(key);
    }

    this.modifiedPiiTypes.set(new Map(this.modifiedPiiTypes()));
  }

  /**
   * Build a bulk-update payload entry. Always carries the LLM-judge toggle and
   * adds detectorDescription ONLY for GLINER2 rows (extension of the bulk
   * contract, sent conditionally).
   */
  private toUpdateRequest(type: PiiTypeConfig): UpdatePiiTypeConfigRequest {
    const update: UpdatePiiTypeConfigRequest = {
      piiType: type.piiType,
      detector: type.detector,
      enabled: type.enabled,
      threshold: type.threshold,
      llmJudgeEnabled: type.llmJudgeEnabled
    };
    if (type.detector === 'GLINER2') {
      update.detectorDescription = type.detectorDescription ?? '';
    }
    return update;
  }

  /**
   * Update the grouped PII types data structure.
   */
  private updateGroupedPiiTypes(detector: string, piiType: string, updates: Partial<PiiTypeConfig>): void {
    const currentGrouped = this.groupedPiiTypes();
    const updatedGrouped = currentGrouped.map(detectorGroup => {
      if (detectorGroup.detector !== detector) return detectorGroup;

      return {
        ...detectorGroup,
        categories: detectorGroup.categories.map(categoryGroup => ({
          ...categoryGroup,
          types: categoryGroup.types.map(type =>
            type.piiType === piiType ? {...type, ...updates} : type
          )
        }))
      };
    });

    this.groupedPiiTypes.set(updatedGrouped);
  }

  /**
   * Save detector-level configuration.
   */
  onSaveDetectorConfig(): void {
    if (this.configForm.invalid) {
      this.configForm.markAllAsTouched();
      return;
    }

    this.saving.set(true);
    const request = this.configForm.value;

    this.configService.updateConfig(request).subscribe({
      next: (config) => {
        this.currentConfig.set(config);
        this.messageService.add({
          severity: 'success',
          summary: this.translocoService.translate('common.success'),
          detail: this.translocoService.translate('settings.messages.saveSuccess'),
          life: 3000
        });
        this.saving.set(false);
        this.configForm.markAsPristine();
      },
      error: (err) => {
        console.error('Failed to update detector configuration:', err);
        const errorMsg = this.translocoService.translate('settings.messages.saveError', {error: err.message || 'Unknown error'});
        this.messageService.add({
          severity: 'error',
          summary: this.translocoService.translate('common.error'),
          detail: errorMsg,
          life: 5000
        });
        this.saving.set(false);
      }
    });
  }

  /**
   * Save all modified PII type configurations.
   */
  onSavePiiTypes(): void {
    const modifications = Array.from(this.modifiedPiiTypes().values());

    if (modifications.length === 0) {
      return;
    }

    this.saving.set(true);

    const updates = modifications.map(type => this.toUpdateRequest(type));

    this.configService.bulkUpdatePiiTypeConfigs(updates).subscribe({
      next: (updatedConfigs) => {
        // Update original types with new values
        updatedConfigs.forEach(config => {
          const key = this.getPiiTypeKey(config.detector, config.piiType);
          this.originalPiiTypes().set(key, config);
        });

        // Clear modifications
        this.modifiedPiiTypes.set(new Map());

        this.messageService.add({
          severity: 'success',
          summary: this.translocoService.translate('common.success'),
          detail: this.translocoService.translate('settings.piiTypes.messages.bulkSaveSuccess', {count: updatedConfigs.length}),
          life: 3000
        });

        this.saving.set(false);
      },
      error: (err) => {
        console.error('Failed to update PII type configurations:', err);
        const errorMsg = this.translocoService.translate('settings.piiTypes.messages.bulkSaveError', {error: err.message || 'Unknown error'});
        this.messageService.add({
          severity: 'error',
          summary: this.translocoService.translate('common.error'),
          detail: errorMsg,
          life: 5000
        });
        this.saving.set(false);
      }
    });
  }

  /**
   * Save all configurations (detector + PII types).
   */
  onSaveAll(): void {
    const hasDetectorChanges = this.configForm.dirty;
    const hasTypeChanges = this.hasUnsavedTypeChanges();
    const hasConfluenceChanges = this.confluenceSettings()?.hasUnsavedChanges ?? false;

    if (!hasDetectorChanges && !hasTypeChanges && !hasConfluenceChanges) {
      return;
    }

    if (hasConfluenceChanges) {
      this.confluenceSettings()!.onSave();
    }

    // If only Confluence changed, it handles its own saving signal — nothing else to do
    if (!hasDetectorChanges && !hasTypeChanges) {
      return;
    }

    this.saving.set(true);

    const requests: Observable<any>[] = [];
    let detectorRequestIndex = -1;
    let typesRequestIndex = -1;

    if (hasDetectorChanges && this.configForm.valid) {
      detectorRequestIndex = requests.length;
      requests.push(this.configService.updateConfig(this.configForm.value));
    }

    if (hasTypeChanges) {
      const modifications = Array.from(this.modifiedPiiTypes().values());
      const updates = modifications.map(type => this.toUpdateRequest(type));
      typesRequestIndex = requests.length;
      requests.push(this.configService.bulkUpdatePiiTypeConfigs(updates));
    }

    forkJoin(requests).subscribe({
      next: (results) => {
        // Update detector config if it was saved
        if (detectorRequestIndex >= 0 && results[detectorRequestIndex]) {
          this.currentConfig.set(results[detectorRequestIndex]);
          this.configForm.markAsPristine();
        }

        // Update PII types if they were saved
        if (typesRequestIndex >= 0 && results[typesRequestIndex]) {
          const piiTypesResult = results[typesRequestIndex];
          piiTypesResult.forEach((config: PiiTypeConfig) => {
            const key = this.getPiiTypeKey(config.detector, config.piiType);
            this.originalPiiTypes().set(key, config);
          });
          this.modifiedPiiTypes.set(new Map());
        }

        this.messageService.add({
          severity: 'success',
          summary: this.translocoService.translate('common.success'),
          detail: this.translocoService.translate('settings.messages.saveAllSuccess'),
          life: 3000
        });

        this.saving.set(false);
        this.settingsSaved.emit();
      },
      error: (err) => {
        console.error('Failed to save configurations:', err);
        const errorMsg = this.translocoService.translate('settings.messages.saveAllError', {error: err.message || 'Unknown error'});
        this.messageService.add({
          severity: 'error',
          summary: this.translocoService.translate('common.error'),
          detail: errorMsg,
          life: 5000
        });
        this.saving.set(false);
      }
    });
  }

  /**
   * Cancel and navigate back.
   * In dialog mode, emits close event. In standalone mode, navigates to home.
   */
  onCancel(): void {
    this.onResetAll();
    if (this.dialogMode()) {
      this.closeDialog.emit();
    } else {
      this.router.navigate(['/']);
    }
  }

  /**
   * Reset detector configuration.
   */
  onResetDetectorConfig(): void {
    if (this.currentConfig()) {
      this.configForm.patchValue({
        glinerEnabled: this.currentConfig()!.glinerEnabled,
        presidioEnabled: this.currentConfig()!.presidioEnabled,
        regexEnabled: this.currentConfig()!.regexEnabled,
        openmedEnabled: this.currentConfig()!.openmedEnabled,
        gliner2Enabled: this.currentConfig()!.gliner2Enabled,
        prefilterEnabled: this.currentConfig()!.prefilterEnabled,
        llmJudgeEnabled: this.currentConfig()!.llmJudgeEnabled,
        defaultThreshold: this.currentConfig()!.defaultThreshold,
        nbOfLabelByPass: this.currentConfig()!.nbOfLabelByPass
      });
      this.configForm.markAsPristine();
      // Restore the collapse state to match the reset detector toggles.
      this.syncCollapsedWithDetectorState();
    }
  }

  /**
   * Reset PII type configurations.
   */
  onResetPiiTypes(): void {
    // Clear all modifications
    this.modifiedPiiTypes.set(new Map());

    // Restore original values in grouped data
    const currentGrouped = this.groupedPiiTypes();
    const updatedGrouped = currentGrouped.map(detectorGroup => ({
      ...detectorGroup,
      categories: detectorGroup.categories.map(categoryGroup => ({
        ...categoryGroup,
        types: categoryGroup.types.map(type => {
          const key = this.getPiiTypeKey(type.detector, type.piiType);
          const original = this.originalPiiTypes().get(key);
          return original ? {...original} : type;
        })
      }))
    }));

    this.groupedPiiTypes.set(updatedGrouped);
  }

  /**
   * Reset all configurations.
   */
  onResetAll(): void {
    this.onResetDetectorConfig();
    this.onResetPiiTypes();
    this.confluenceSettings()?.onReset();
  }

  get hasUnsavedChanges(): boolean {
    return this.configForm.dirty || this.hasUnsavedTypeChanges() || (this.confluenceSettings()?.hasUnsavedChanges ?? false);
  }

  get isFormValid(): boolean {
    return this.configForm.valid && (this.confluenceSettings()?.isFormValid ?? true);
  }

  get hasDetectorChanges(): boolean {
    return this.configForm.dirty;
  }

  get atLeastOneDetectorError(): boolean {
    return this.configForm.hasError('atLeastOneDetector') && this.configForm.touched;
  }

  /**
   * Get count of modified PII types.
   */
  get modifiedTypesCount(): number {
    return this.modifiedPiiTypes().size;
  }

  /**
   * Handle search term change.
   */
  onSearchChange(term: string): void {
    this.searchTerm.set(term);
  }

  /**
   * Clear search term.
   */
  clearSearch(): void {
    this.searchTerm.set('');
  }

  /**
   * Highlight search term in text.
   */
  highlightText(text: string, originalKey: string): string {
    // Get translated text first
    const translatedText = this.translocoService.translate(originalKey);

    const term = this.searchTerm().trim();
    if (!term) {
      // No search term, return plain translated text
      return this.sanitizer.sanitize(SecurityContext.HTML, translatedText) || translatedText;
    }

    // Escape special regex characters
    const escapedTerm = term.replaceAll(/[.*+?^${}()|[\]\\]/g, String.raw`\$&`);
    const regex = new RegExp(`(${escapedTerm})`, 'gi');

    // Replace matches with highlighted version
    const highlighted = translatedText.replaceAll(regex, '<mark class="search-highlight">$1</mark>');

    return this.sanitizer.sanitize(SecurityContext.HTML, highlighted) || '';
  }

  /**
   * Set the active sidebar section.
   */
  setActiveSection(section: SettingsSection): void {
    this.activeSection.set(section);
  }

  /**
   * Toggle collapse state of a detector group.
   */
  toggleDetectorCollapse(detector: string): void {
    const current = new Set(this.collapsedDetectors());
    if (current.has(detector)) {
      current.delete(detector);
    } else {
      current.add(detector);
    }
    this.collapsedDetectors.set(current);
  }

  /**
   * Check if a detector group is collapsed.
   */
  isDetectorCollapsed(detector: string): boolean {
    return this.collapsedDetectors().has(detector);
  }

  /**
   * Whether the detector that owns a "Types d'IPI" sub-section is enabled
   * in the "Moteur de détection" section. Drives the disabled state of the
   * per-type toggles so a disabled detector cannot be edited there.
   * Unknown detectors (no master toggle) default to enabled.
   */
  isDetectorEnabled(detector: string): boolean {
    const control = PiiSettingsComponent.DETECTOR_MASTER_CONTROL[detector];
    if (!control) {
      return true;
    }
    return !!this.configForm.get(control)?.value;
  }

  /**
   * Whether the global LLM-as-Judge master toggle is enabled. When off, the
   * per-type judge toggles are shown off and disabled WITHOUT mutating their
   * stored values, so re-enabling restores each customisation (and an
   * untouched config shows them all on).
   */
  isLlmJudgeMasterEnabled(): boolean {
    return !!this.configForm.get('llmJudgeEnabled')?.value;
  }

  /**
   * React to a detector master toggle in "Moteur de détection": collapse its
   * "Types d'IPI" sub-section when disabled, expand it when re-enabled. The
   * per-type values are never touched — only the visual state is derived.
   */
  onDetectorMasterToggle(detector: string): void {
    const collapsed = new Set(this.collapsedDetectors());
    if (this.isDetectorEnabled(detector)) {
      collapsed.delete(detector);
    } else {
      collapsed.add(detector);
    }
    this.collapsedDetectors.set(collapsed);
  }

  /**
   * Collapse every detector sub-section whose master toggle is currently
   * disabled, leaving enabled ones in their current state. Called after a
   * config load so the two sections start consistent.
   */
  private syncCollapsedWithDetectorState(): void {
    const collapsed = new Set(this.collapsedDetectors());
    for (const detector of Object.keys(PiiSettingsComponent.DETECTOR_MASTER_CONTROL)) {
      if (this.isDetectorEnabled(detector)) {
        collapsed.delete(detector);
      } else {
        collapsed.add(detector);
      }
    }
    this.collapsedDetectors.set(collapsed);
  }
}
