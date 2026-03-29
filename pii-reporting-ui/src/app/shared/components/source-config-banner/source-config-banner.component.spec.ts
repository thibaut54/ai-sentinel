import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslocoTestingModule } from '@jsverse/transloco';
import { SourceConfigBannerComponent, SourceType } from './source-config-banner.component';

const TRANSLATIONS = {
  dashboard: {
    notifications: {
      configMissing: {
        message: 'Les identifiants {{source}} ne sont pas configurés. Veuillez configurer vos paramètres de connexion avant de lancer un scan.',
        settingsButton: 'Configurer',
        closeAriaLabel: 'Fermer la notification'
      }
    }
  }
};

describe('SourceConfigBannerComponent', () => {
  let fixture: ComponentFixture<SourceConfigBannerComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [
        SourceConfigBannerComponent,
        TranslocoTestingModule.forRoot({
          langs: { fr: TRANSLATIONS },
          translocoConfig: { availableLangs: ['fr'], defaultLang: 'fr' },
        }),
      ],
    }).compileComponents();
  });

  function createComponent(sourceType: SourceType, showBanner = true): void {
    fixture = TestBed.createComponent(SourceConfigBannerComponent);
    fixture.componentRef.setInput('sourceType', sourceType);
    fixture.componentRef.setInput('showBanner', showBanner);
    fixture.detectChanges();
  }

  it('Should_HideBanner_When_ShowBannerIsFalse', () => {
    createComponent('confluence', false);
    const banner = fixture.nativeElement.querySelector('.source-config-banner');
    expect(banner).toBeNull();
  });

  it('Should_DisplayBanner_When_ShowBannerIsTrue', () => {
    createComponent('confluence');
    const banner = fixture.nativeElement.querySelector('.source-config-banner');
    expect(banner).toBeTruthy();
  });

  it('Should_DisplayConfluenceMessage_When_SourceIsConfluence', () => {
    createComponent('confluence');
    const message = fixture.nativeElement.querySelector('.banner-message');
    expect(message.textContent).toContain('Confluence');
    expect(message.textContent).toContain('ne sont pas configurés');
  });

  it('Should_DisplayJiraMessage_When_SourceIsJira', () => {
    createComponent('jira');
    const message = fixture.nativeElement.querySelector('.banner-message');
    expect(message.textContent).toContain('Jira');
  });

  it('Should_DisplaySharePointMessage_When_SourceIsSharePoint', () => {
    createComponent('sharepoint');
    const message = fixture.nativeElement.querySelector('.banner-message');
    expect(message.textContent).toContain('SharePoint');
  });

  it('Should_EmitOpenSettings_When_ConfigureButtonClicked', () => {
    createComponent('confluence');
    const spy = vi.spyOn(fixture.componentInstance.openSettings, 'emit');
    const configureBtn = fixture.nativeElement.querySelector('[data-testid="configure-button"]');
    configureBtn.click();
    expect(spy).toHaveBeenCalled();
  });

  it('Should_EmitDismiss_When_DismissButtonClicked', () => {
    createComponent('confluence');
    const spy = vi.spyOn(fixture.componentInstance.dismiss, 'emit');
    const dismissBtn = fixture.nativeElement.querySelector('[data-testid="dismiss-button"]');
    dismissBtn.click();
    expect(spy).toHaveBeenCalled();
  });

  it('Should_HaveWarningIcon_When_BannerIsVisible', () => {
    createComponent('jira');
    const icon = fixture.nativeElement.querySelector('.pi-exclamation-triangle');
    expect(icon).toBeTruthy();
  });

  it('Should_HaveAlertRole_When_BannerIsVisible', () => {
    createComponent('sharepoint');
    const banner = fixture.nativeElement.querySelector('[role="alert"]');
    expect(banner).toBeTruthy();
  });

  it('Should_HaveAriaLabelOnDismissButton_When_BannerIsVisible', () => {
    createComponent('confluence');
    const dismissBtn = fixture.nativeElement.querySelector('[data-testid="dismiss-button"]');
    expect(dismissBtn.getAttribute('aria-label')).toBe('Fermer la notification');
  });
});
