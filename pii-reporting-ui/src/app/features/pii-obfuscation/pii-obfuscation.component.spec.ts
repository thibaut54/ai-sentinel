import { ComponentFixture, TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { ActivatedRoute, convertToParamMap, ParamMap } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { TranslocoTestingModule } from '@jsverse/transloco';
import { beforeEach, describe, expect, it } from 'vitest';
import { PiiObfuscationComponent } from './pii-obfuscation.component';
import { ObfuscationSelectionService } from './services/obfuscation-selection.service';
import { RemediationConfigService } from '../../core/services/remediation-config.service';

const FR_TRANSLATIONS = {
  obfuscation: {
    featureDisabled: 'Le caviardage automatique est désactivé pour cette instance.',
  },
};

describe('PiiObfuscationComponent', () => {
  let fixture: ComponentFixture<PiiObfuscationComponent>;
  let remediationConfigMock: { enabled: ReturnType<typeof signal<boolean>> };
  let queryParams$: BehaviorSubject<ParamMap>;

  beforeEach(async () => {
    remediationConfigMock = { enabled: signal(false) };
    queryParams$ = new BehaviorSubject(convertToParamMap({}));

    await TestBed.configureTestingModule({
      imports: [
        PiiObfuscationComponent,
        TranslocoTestingModule.forRoot({
          translocoConfig: { defaultLang: 'fr', availableLangs: ['fr'] },
          preloadLangs: true,
          langs: { fr: FR_TRANSLATIONS },
        }),
      ],
      providers: [
        { provide: RemediationConfigService, useValue: remediationConfigMock },
        { provide: ActivatedRoute, useValue: { queryParamMap: queryParams$ } },
      ],
    }).compileComponents();
  });

  function createComponent(): void {
    fixture = TestBed.createComponent(PiiObfuscationComponent);
    fixture.detectChanges();
  }

  function selectionService(): ObfuscationSelectionService {
    return fixture.debugElement.injector.get(ObfuscationSelectionService);
  }

  it('Should_ShowFeatureDisabledState_When_FlagOff', () => {
    createComponent();

    const disabled = fixture.nativeElement.querySelector(
      '[data-testid="obfuscation-feature-disabled"]'
    );
    expect(disabled).toBeTruthy();
    expect(disabled.textContent).toContain('désactivé');
    expect(
      fixture.nativeElement.querySelector('[data-testid="obfuscation-content"]')
    ).toBeFalsy();
  });

  it('Should_ShowPageContent_When_FlagOn', () => {
    remediationConfigMock.enabled.set(true);

    createComponent();

    expect(
      fixture.nativeElement.querySelector('[data-testid="obfuscation-content"]')
    ).toBeTruthy();
    expect(
      fixture.nativeElement.querySelector('[data-testid="obfuscation-feature-disabled"]')
    ).toBeFalsy();
  });

  it('Should_SetScopeFromQueryParams_When_Loaded', () => {
    queryParams$.next(
      convertToParamMap({ spaceKey: 'SPACE', pageId: 'p1', attachmentName: 'doc.pdf' })
    );

    createComponent();

    expect(selectionService().scope()).toEqual({
      spaceKey: 'SPACE',
      pageId: 'p1',
      attachmentName: 'doc.pdf',
    });
  });

  it('Should_OmitOptionalScopeParts_When_ParamsAbsent', () => {
    queryParams$.next(convertToParamMap({ spaceKey: 'SPACE' }));

    createComponent();

    expect(selectionService().scope()).toEqual({ spaceKey: 'SPACE' });
  });

  it('Should_FlagPreselectRequest_When_PreselectParamTrue', () => {
    queryParams$.next(convertToParamMap({ spaceKey: 'SPACE', preselect: 'true' }));

    createComponent();

    expect(fixture.componentInstance.preselectRequested()).toBe(true);
  });

  it('Should_NotFlagPreselectRequest_When_PreselectParamAbsent', () => {
    queryParams$.next(convertToParamMap({ spaceKey: 'SPACE' }));

    createComponent();

    expect(fixture.componentInstance.preselectRequested()).toBe(false);
  });

  it('Should_DisplaySpaceKeyHeading_When_FlagOn', () => {
    remediationConfigMock.enabled.set(true);
    queryParams$.next(convertToParamMap({ spaceKey: 'SPACE' }));

    createComponent();

    const heading = fixture.nativeElement.querySelector('h2');
    expect(heading.textContent).toContain('SPACE');
  });
});
