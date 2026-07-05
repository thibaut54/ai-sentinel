import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslocoTestingModule } from '@jsverse/transloco';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ObfuscationFindingRowComponent } from './obfuscation-finding-row.component';
import { RemediationFindingDto } from '../../../../core/models/remediation.model';

const FR_TRANSLATIONS = {
  obfuscation: {
    select: 'Sélectionner',
    redactedValue: 'Caviardé',
    status: {
      pending: 'À traiter',
      redacted: 'Caviardé',
      manual: 'Traité (manuel)',
      fp: 'Faux positif',
    },
    action: {
      markManual: 'Marquer comme traité manuellement',
      undoManual: 'Rétablir « à traiter »',
      markFp: 'Signaler faux positif',
      restore: 'Rétablir',
    },
    ineligible: {
      attachment: 'Pièce jointe non caviardable ({{kind}})',
    },
  },
};

function finding(overrides: Partial<RemediationFindingDto> = {}): RemediationFindingDto {
  return {
    findingId: 'f1',
    piiType: 'EMAIL',
    severity: 'high',
    detector: 'PRESIDIO',
    confidenceScore: 0.87,
    maskedContext: 'contact: [EMAIL]',
    pageId: 'p1',
    pageTitle: 'Team page',
    status: 'PENDING',
    selected: false,
    eligibleForRedaction: true,
    ...overrides,
  };
}

describe('ObfuscationFindingRowComponent', () => {
  let fixture: ComponentFixture<ObfuscationFindingRowComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [
        ObfuscationFindingRowComponent,
        TranslocoTestingModule.forRoot({
          translocoConfig: { defaultLang: 'fr', availableLangs: ['fr'] },
          preloadLangs: true,
          langs: { fr: FR_TRANSLATIONS },
        }),
      ],
    }).compileComponents();
  });

  function createComponent(value: RemediationFindingDto, groupBy: 'type' | 'severity' = 'type'): void {
    fixture = TestBed.createComponent(ObfuscationFindingRowComponent);
    fixture.componentRef.setInput('finding', value);
    fixture.componentRef.setInput('groupBy', groupBy);
    fixture.detectChanges();
  }

  function query<T extends HTMLElement>(testId: string): T | null {
    return fixture.nativeElement.querySelector(`[data-testid="${testId}"]`);
  }

  it('Should_RenderMaskedContextOnly_When_FindingPending', () => {
    createComponent(finding());

    const value = query('obfuscation-row-value');
    expect(value?.textContent).toContain('contact: [EMAIL]');
    expect(fixture.nativeElement.textContent).not.toContain('john.doe@example.com');
  });

  it('Should_CheckCheckbox_When_ApiSaysSelected', () => {
    createComponent(finding({ selected: true }));

    const checkbox = query<HTMLInputElement>('obfuscation-row-checkbox');
    expect(checkbox?.checked).toBe(true);
    expect(checkbox?.disabled).toBe(false);
  });

  it('Should_DisableCheckbox_When_FindingRedacted', () => {
    createComponent(finding({ status: 'REDACTED' }));

    expect(query<HTMLInputElement>('obfuscation-row-checkbox')?.disabled).toBe(true);
  });

  it('Should_DisableCheckbox_When_FindingManuallyHandled', () => {
    createComponent(finding({ status: 'MANUALLY_HANDLED' }));

    expect(query<HTMLInputElement>('obfuscation-row-checkbox')?.disabled).toBe(true);
  });

  it('Should_KeepCheckboxEnabled_When_FindingFalsePositive', () => {
    createComponent(finding({ status: 'FALSE_POSITIVE' }));

    expect(query<HTMLInputElement>('obfuscation-row-checkbox')?.disabled).toBe(false);
  });

  it('Should_RenderRedactedBlockInsteadOfValue_When_FindingRedacted', () => {
    createComponent(finding({ status: 'REDACTED' }));

    const value = query('obfuscation-row-value');
    expect(value?.textContent).toContain('Caviardé');
    expect(value?.textContent).not.toContain('[EMAIL]');
  });

  it('Should_GreyOutAndExplain_When_ApiSaysIneligible', () => {
    createComponent(
      finding({
        eligibleForRedaction: false,
        ineligibilityReason: 'ATTACHMENT',
        attachmentName: 'report.pdf',
      })
    );

    const root = query('obfuscation-row');
    expect(root?.className).toContain('ob-occ--ineligible');
    expect(query<HTMLInputElement>('obfuscation-row-checkbox')?.disabled).toBe(true);
    expect(query('obfuscation-row-ineligible')?.textContent).toContain(
      'Pièce jointe non caviardable (pdf)'
    );
  });

  it('Should_ShowRawReason_When_IneligibleWithoutAttachment', () => {
    createComponent(finding({ eligibleForRedaction: false, ineligibilityReason: 'SOME_REASON' }));

    expect(query('obfuscation-row-ineligible')?.textContent).toContain('SOME_REASON');
  });

  it('Should_ShowStatusBadgeFromApi_When_Rendered', () => {
    createComponent(finding({ status: 'FALSE_POSITIVE' }));

    expect(query('obfuscation-row-status')?.textContent).toContain('Faux positif');
  });

  it('Should_ShowPendingActions_When_FindingPending', () => {
    createComponent(finding());

    expect(query('obfuscation-row-mark-manual')).toBeTruthy();
    expect(query('obfuscation-row-report-fp')).toBeTruthy();
    expect(query('obfuscation-row-restore')).toBeFalsy();
  });

  it('Should_ShowOnlyRestore_When_FindingManuallyHandled', () => {
    createComponent(finding({ status: 'MANUALLY_HANDLED' }));

    expect(query('obfuscation-row-restore')).toBeTruthy();
    expect(query('obfuscation-row-mark-manual')).toBeFalsy();
    expect(query('obfuscation-row-report-fp')).toBeFalsy();
  });

  it('Should_ShowOnlyRestore_When_FindingFalsePositive', () => {
    createComponent(finding({ status: 'FALSE_POSITIVE' }));

    expect(query('obfuscation-row-restore')).toBeTruthy();
    expect(query('obfuscation-row-mark-manual')).toBeFalsy();
  });

  it('Should_ShowNoAction_When_FindingRedacted', () => {
    createComponent(finding({ status: 'REDACTED' }));

    expect(query('obfuscation-row-mark-manual')).toBeFalsy();
    expect(query('obfuscation-row-report-fp')).toBeFalsy();
    expect(query('obfuscation-row-restore')).toBeFalsy();
  });

  it('Should_EmitToggled_When_CheckboxChanged', () => {
    createComponent(finding());
    const toggled = vi.fn();
    fixture.componentInstance.toggled.subscribe(toggled);

    query<HTMLInputElement>('obfuscation-row-checkbox')?.dispatchEvent(new Event('change'));

    expect(toggled).toHaveBeenCalledTimes(1);
  });

  it('Should_EmitQuickActions_When_ActionButtonsClicked', () => {
    createComponent(finding());
    const markManual = vi.fn();
    const reportFp = vi.fn();
    fixture.componentInstance.markManual.subscribe(markManual);
    fixture.componentInstance.reportFalsePositive.subscribe(reportFp);

    query('obfuscation-row-mark-manual')?.click();
    query('obfuscation-row-report-fp')?.click();

    expect(markManual).toHaveBeenCalledTimes(1);
    expect(reportFp).toHaveBeenCalledTimes(1);
  });

  it('Should_EmitRestore_When_RestoreClicked', () => {
    createComponent(finding({ status: 'FALSE_POSITIVE' }));
    const restore = vi.fn();
    fixture.componentInstance.restore.subscribe(restore);

    query('obfuscation-row-restore')?.click();

    expect(restore).toHaveBeenCalledTimes(1);
  });

  it('Should_ShowPageMetaAndReusedIndicators_When_Rendered', () => {
    createComponent(finding({ attachmentName: 'report.pdf' }));

    expect(fixture.nativeElement.textContent).toContain('Team page');
    expect(fixture.nativeElement.textContent).toContain('report.pdf');
    expect(fixture.nativeElement.querySelector('app-confidence-indicator')).toBeTruthy();
    expect(fixture.nativeElement.querySelector('app-detector-tag')).toBeTruthy();
  });

  it('Should_ShowPiiTypeTag_When_GroupedBySeverity', () => {
    createComponent(finding(), 'severity');

    expect(fixture.nativeElement.textContent).toContain('EMAIL');
  });
});
