import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslocoTestingModule } from '@jsverse/transloco';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ObfuscationGroupListComponent } from './obfuscation-group-list.component';
import { RemediationFindingDto, RemediationGroupDto } from '../../../../core/models/remediation.model';

const FR_TRANSLATIONS = {
  obfuscation: {
    select: 'Sélectionner',
    selectAllGroup: 'Sélectionner tout le groupe (toutes pages)',
    nSelectedShort: '{{count}} sélectionnés',
    nOccurrences: '{{count}} occurrences',
    groupPageHint:
      '{{visible}} occurrences affichées sur {{total}} — cocher l’en-tête sélectionne les {{total}}, y compris sur les autres pages.',
    redactedValue: 'Caviardé',
    status: { pending: 'À traiter', redacted: 'Caviardé', manual: 'Traité (manuel)', fp: 'Faux positif' },
    action: { markManual: 'Manuel', undoManual: 'Rétablir', markFp: 'FP', restore: 'Rétablir' },
    ineligible: { attachment: 'Pièce jointe non caviardable ({{kind}})' },
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
    occurrenceCount: 1,
    pageId: 'p1',
    pageTitle: 'Team page',
    status: 'PENDING',
    selected: false,
    eligibleForRedaction: true,
    ...overrides,
  };
}

function group(overrides: Partial<RemediationGroupDto> = {}): RemediationGroupDto {
  return {
    key: 'EMAIL',
    label: 'Email',
    severity: 'high',
    total: 12,
    occurrenceCount: 12,
    selectedCount: 0,
    masterState: 'none',
    findings: [finding()],
    ...overrides,
  };
}

describe('ObfuscationGroupListComponent', () => {
  let fixture: ComponentFixture<ObfuscationGroupListComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [
        ObfuscationGroupListComponent,
        TranslocoTestingModule.forRoot({
          translocoConfig: { defaultLang: 'fr', availableLangs: ['fr'] },
          preloadLangs: true,
          langs: { fr: FR_TRANSLATIONS },
        }),
      ],
    }).compileComponents();
  });

  function createComponent(
    groups: RemediationGroupDto[],
    openKeys: ReadonlySet<string> = new Set(),
    groupBy: 'type' | 'severity' = 'type'
  ): void {
    fixture = TestBed.createComponent(ObfuscationGroupListComponent);
    fixture.componentRef.setInput('groups', groups);
    fixture.componentRef.setInput('openKeys', openKeys);
    fixture.componentRef.setInput('groupBy', groupBy);
    fixture.detectChanges();
  }

  function query<T extends HTMLElement>(testId: string): T | null {
    return fixture.nativeElement.querySelector(`[data-testid="${testId}"]`);
  }

  it('Should_RenderApiMasterStateVerbatim_When_RowDataWouldImplyOtherwise', () => {
    createComponent([
      group({
        masterState: 'partial',
        findings: [finding({ selected: true }), finding({ findingId: 'f2', selected: true })],
      }),
    ]);

    expect(query('obfuscation-group-master')?.getAttribute('aria-checked')).toBe('mixed');
  });

  it('Should_MapMasterStateToAriaChecked_When_AllOrNone', () => {
    createComponent([group({ key: 'A', masterState: 'all' }), group({ key: 'B', masterState: 'none' })]);

    const masters = fixture.nativeElement.querySelectorAll('[data-testid="obfuscation-group-master"]');
    expect(masters[0].getAttribute('aria-checked')).toBe('true');
    expect(masters[1].getAttribute('aria-checked')).toBe('false');
  });

  it('Should_RenderApiTotalVerbatim_When_VisibleRowCountDiffers', () => {
    createComponent([group({ total: 344, findings: [finding()] })]);

    expect(query('obfuscation-group-count')?.textContent).toContain('344');
  });

  it('Should_RenderApiSelectedCountVerbatim_When_NoRowIsSelected', () => {
    createComponent([group({ selectedCount: 99, findings: [finding({ selected: false })] })]);

    expect(query('obfuscation-group-selected-count')?.textContent).toContain('99 sélectionnés');
  });

  it('Should_HideSelectedCount_When_ApiSaysZero', () => {
    createComponent([group({ selectedCount: 0 })]);

    expect(query('obfuscation-group-selected-count')).toBeFalsy();
  });

  it('Should_ShowOccurrenceCount_When_MoreOccurrencesThanDistinctValues', () => {
    createComponent([group({ total: 4, occurrenceCount: 9, findings: [finding()] })]);

    expect(query('obfuscation-group-occurrences')?.textContent).toContain('9 occurrences');
  });

  it('Should_HideOccurrenceCount_When_OneOccurrencePerValue', () => {
    createComponent([group({ total: 4, occurrenceCount: 4, findings: [finding()] })]);

    expect(query('obfuscation-group-occurrences')).toBeFalsy();
  });

  it('Should_ShowCrossPageHint_When_FewerRowsThanGroupTotal', () => {
    createComponent([group({ total: 12, findings: [finding()] })], new Set(['EMAIL']));

    const hint = query('obfuscation-group-hint');
    expect(hint?.textContent).toContain('1 occurrences affichées sur 12');
  });

  it('Should_NotShowHint_When_AllGroupRowsVisible', () => {
    createComponent([group({ total: 1, findings: [finding()] })], new Set(['EMAIL']));

    expect(query('obfuscation-group-hint')).toBeFalsy();
  });

  it('Should_RenderRowsOnlyForOpenGroups_When_AccordionsPartiallyOpen', () => {
    createComponent(
      [group({ key: 'A', label: 'A' }), group({ key: 'B', label: 'B', findings: [finding({ findingId: 'f2' })] })],
      new Set(['A'])
    );

    const toggles = fixture.nativeElement.querySelectorAll('[data-testid="obfuscation-group-toggle"]');
    expect(toggles[0].getAttribute('aria-expanded')).toBe('true');
    expect(toggles[1].getAttribute('aria-expanded')).toBe('false');
    expect(fixture.nativeElement.querySelectorAll('app-obfuscation-finding-row').length).toBe(1);
  });

  it('Should_EmitGroupToggled_When_HeaderToggleClicked', () => {
    createComponent([group()]);
    const toggledKeys: string[] = [];
    fixture.componentInstance.groupToggled.subscribe((key) => toggledKeys.push(key));

    query('obfuscation-group-toggle')?.click();

    expect(toggledKeys).toEqual(['EMAIL']);
  });

  it('Should_EmitMasterToggledWithGroup_When_MasterCheckboxClicked', () => {
    const target = group({ masterState: 'partial' });
    createComponent([target]);
    const emitted: RemediationGroupDto[] = [];
    fixture.componentInstance.masterToggled.subscribe((value) => emitted.push(value));

    query('obfuscation-group-master')?.click();

    expect(emitted).toEqual([target]);
  });

  it('Should_BubbleRowGestures_When_RowEventsEmitted', () => {
    const rowFinding = finding({ findingId: 'f9' });
    createComponent([group({ findings: [rowFinding] })], new Set(['EMAIL']));
    const toggled = vi.fn();
    fixture.componentInstance.rowToggled.subscribe(toggled);

    fixture.nativeElement
      .querySelector('[data-testid="obfuscation-row-checkbox"]')
      ?.dispatchEvent(new Event('change'));

    expect(toggled).toHaveBeenCalledWith(rowFinding);
  });

  it('Should_UseSeverityGroupKeyForDot_When_GroupedBySeverity', () => {
    createComponent([group({ key: 'high', label: 'Critique', severity: undefined })], new Set(), 'severity');

    expect(fixture.nativeElement.querySelector('.ob-dot--high')).toBeTruthy();
  });
});
