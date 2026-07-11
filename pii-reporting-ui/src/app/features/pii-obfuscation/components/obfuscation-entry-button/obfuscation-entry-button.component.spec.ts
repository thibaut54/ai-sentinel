import { ComponentFixture, TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { Router } from '@angular/router';
import { TranslocoTestingModule } from '@jsverse/transloco';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ObfuscationEntryButtonComponent } from './obfuscation-entry-button.component';
import { RemediationConfigService } from '../../../../core/services/remediation-config.service';

const FR_TRANSLATIONS = {
  obfuscation: {
    entry: {
      space: "Caviarder l'espace",
      page: 'Caviarder la page',
      attachment: 'Caviarder la pièce jointe',
    },
  },
};

describe('ObfuscationEntryButtonComponent', () => {
  let fixture: ComponentFixture<ObfuscationEntryButtonComponent>;
  let remediationConfigMock: { enabled: ReturnType<typeof signal<boolean>> };
  let routerMock: { navigate: ReturnType<typeof vi.fn> };

  beforeEach(async () => {
    remediationConfigMock = { enabled: signal(false) };
    routerMock = { navigate: vi.fn() };

    await TestBed.configureTestingModule({
      imports: [
        ObfuscationEntryButtonComponent,
        TranslocoTestingModule.forRoot({
          translocoConfig: { defaultLang: 'fr', availableLangs: ['fr'] },
          preloadLangs: true,
          langs: { fr: FR_TRANSLATIONS },
        }),
      ],
      providers: [
        { provide: RemediationConfigService, useValue: remediationConfigMock },
        { provide: Router, useValue: routerMock },
      ],
    }).compileComponents();
  });

  function createComponent(inputs: { spaceKey: string; pageId?: string; attachmentName?: string }): void {
    fixture = TestBed.createComponent(ObfuscationEntryButtonComponent);
    fixture.componentRef.setInput('spaceKey', inputs.spaceKey);
    if (inputs.pageId) {
      fixture.componentRef.setInput('pageId', inputs.pageId);
    }
    if (inputs.attachmentName) {
      fixture.componentRef.setInput('attachmentName', inputs.attachmentName);
    }
    fixture.detectChanges();
  }

  it('Should_HideButton_When_RemediationDisabled', () => {
    createComponent({ spaceKey: 'SPACE' });

    expect(fixture.nativeElement.querySelector('button')).toBeFalsy();
  });

  it('Should_ShowButton_When_RemediationEnabled', () => {
    remediationConfigMock.enabled.set(true);

    createComponent({ spaceKey: 'SPACE' });

    const button = fixture.nativeElement.querySelector('[data-testid="btn-obfuscate-space"]');
    expect(button).toBeTruthy();
  });

  it('Should_NavigateWithSpaceScopeAndPreselect_When_SpaceEntryClicked', () => {
    remediationConfigMock.enabled.set(true);
    createComponent({ spaceKey: 'SPACE' });

    fixture.nativeElement.querySelector('button').click();

    expect(routerMock.navigate).toHaveBeenCalledWith(['/obfuscation'], {
      queryParams: { spaceKey: 'SPACE', preselect: 'true' },
    });
  });

  it('Should_NavigateWithPageScope_When_PageEntryClicked', () => {
    remediationConfigMock.enabled.set(true);
    createComponent({ spaceKey: 'SPACE', pageId: 'p1' });

    const button = fixture.nativeElement.querySelector('[data-testid="btn-obfuscate-page"]');
    button.click();

    expect(routerMock.navigate).toHaveBeenCalledWith(['/obfuscation'], {
      queryParams: { spaceKey: 'SPACE', pageId: 'p1', preselect: 'true' },
    });
  });

  it('Should_NavigateWithAttachmentScope_When_AttachmentEntryClicked', () => {
    remediationConfigMock.enabled.set(true);
    createComponent({ spaceKey: 'SPACE', pageId: 'p1', attachmentName: 'doc.pdf' });

    const button = fixture.nativeElement.querySelector('[data-testid="btn-obfuscate-attachment"]');
    button.click();

    expect(routerMock.navigate).toHaveBeenCalledWith(['/obfuscation'], {
      queryParams: { spaceKey: 'SPACE', pageId: 'p1', attachmentName: 'doc.pdf', preselect: 'true' },
    });
  });

  it('Should_StopEventPropagation_When_Clicked', () => {
    remediationConfigMock.enabled.set(true);
    createComponent({ spaceKey: 'SPACE' });

    const rowClickSpy = vi.fn();
    fixture.nativeElement.addEventListener('click', rowClickSpy);
    fixture.nativeElement.querySelector('button').click();

    expect(rowClickSpy).not.toHaveBeenCalled();
  });

  it('Should_ExposeTranslatedAriaLabel_When_SpaceEntryRendered', () => {
    remediationConfigMock.enabled.set(true);
    createComponent({ spaceKey: 'SPACE' });

    const button = fixture.nativeElement.querySelector('button');
    expect(button.getAttribute('aria-label')).toBe("Caviarder l'espace");
  });
});
