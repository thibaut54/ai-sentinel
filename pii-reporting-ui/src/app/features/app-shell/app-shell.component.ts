import { ChangeDetectionStrategy, Component, computed, inject, signal, viewChild } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NgOptimizedImage } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import { filter, map } from 'rxjs/operators';
import { ButtonModule } from 'primeng/button';
import { TooltipModule } from 'primeng/tooltip';
import { ToastModule } from 'primeng/toast';
import { ConfirmDialogModule } from 'primeng/confirmdialog';
import { DialogModule } from 'primeng/dialog';
import { TabsModule } from 'primeng/tabs';
import { SelectButtonModule } from 'primeng/selectbutton';
import { SelectModule } from 'primeng/select';
import { LanguageSelectorComponent } from '../../core/components/language-selector/language-selector.component';
import { ConfluenceDashboardComponent } from '../confluence-dashboard/confluence-dashboard.component';
import { PiiSettingsComponent } from '../pii-settings/pii-settings.component';
import { ThemeService } from '../../core/services/theme.service';
import { ViewMode, ViewModeService } from '../../core/services/view-mode.service';

/**
 * Application shell with top bar and Confluence source tab.
 *
 * Hosts the global toast, confirmation dialog, and settings dialog.
 */
@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [
    NgOptimizedImage,
    FormsModule,
    TranslocoModule,
    ButtonModule,
    TooltipModule,
    ToastModule,
    ConfirmDialogModule,
    DialogModule,
    TabsModule,
    SelectButtonModule,
    SelectModule,
    LanguageSelectorComponent,
    ConfluenceDashboardComponent,
    PiiSettingsComponent
  ],
  templateUrl: './app-shell.component.html',
  styleUrl: './app-shell.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class AppShellComponent {
  readonly themeService = inject(ThemeService);
  readonly viewModeService = inject(ViewModeService);
  private readonly translocoService = inject(TranslocoService);

  readonly activeSourceId = signal('confluence');

  // Settings dialog state
  readonly showSettingsDialog = signal(false);
  readonly settingsInitialTab = signal(0);

  /**
   * Tracks the active language so {@link viewModeOptions} recomputes when the user
   * switches language AND when transloco finishes loading the initial bundle.
   * Without this signal, the computed below would freeze on the first render
   * (where `translate()` returns the key because translations aren't loaded yet).
   */
  private readonly activeLang = toSignal(
    this.translocoService.langChanges$,
    { initialValue: this.translocoService.getActiveLang() }
  );
  private readonly translationsLoaded = toSignal(
    this.translocoService.events$.pipe(
      filter(e => e.type === 'translationLoadSuccess'),
      map(() => true)
    ),
    { initialValue: false }
  );

  /** Options for the tri-mode toggle (translated, reactive to language changes). */
  readonly viewModeOptions = computed<{ label: string; value: ViewMode }[]>(() => {
    // Reading both signals makes this computed react to loadSuccess + lang switch.
    this.activeLang();
    this.translationsLoaded();
    return [
      { label: this.translocoService.translate('viewMode.standard'), value: 'standard' },
      { label: this.translocoService.translate('viewMode.gdpr'), value: 'gdpr' },
      { label: this.translocoService.translate('viewMode.nlpd'), value: 'nlpd' }
    ];
  });

  // Child component references
  private readonly confluenceDashboard = viewChild(ConfluenceDashboardComponent);
  private readonly piiSettings = viewChild(PiiSettingsComponent);

  openSettingsDialog(tab: number = 0): void {
    this.settingsInitialTab.set(tab);
    this.showSettingsDialog.set(true);
  }

  closeSettingsDialog(): void {
    this.showSettingsDialog.set(false);
  }

  onSettingsDialogHide(): void {
    this.piiSettings()?.onResetAll();
  }

  /**
   * Handle settings saved event from PiiSettingsComponent.
   * Triggers dashboard refresh to reflect configuration changes.
   */
  onSettingsSaved(): void {
    this.confluenceDashboard()?.refreshAfterSettingsSave();
  }

  /** Update the global view mode. */
  onViewModeChange(mode: ViewMode): void {
    this.viewModeService.setMode(mode);
  }
}
