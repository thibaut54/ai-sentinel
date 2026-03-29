import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { NgOptimizedImage } from '@angular/common';
import { NavigationEnd, Router, RouterOutlet } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { filter, map, startWith } from 'rxjs';
import { TranslocoModule } from '@jsverse/transloco';
import { ButtonModule } from 'primeng/button';
import { TooltipModule } from 'primeng/tooltip';
import { ToastModule } from 'primeng/toast';
import { ConfirmDialogModule } from 'primeng/confirmdialog';
import { DialogModule } from 'primeng/dialog';
import { TabsModule } from 'primeng/tabs';
import { LanguageSelectorComponent } from '../../core/components/language-selector/language-selector.component';
import { PiiSettingsComponent } from '../pii-settings/pii-settings.component';
import { ThemeService } from '../../core/services/theme.service';
import { SettingsDialogService } from '../../core/services/settings-dialog.service';
const SOURCE_ROUTES = ['confluence', 'jira', 'sharepoint'] as const;
type SourceId = (typeof SOURCE_ROUTES)[number];

function extractSourceFromUrl(url: string): SourceId {
  const match = SOURCE_ROUTES.find((s) => url.includes(`/${s}`));
  return match ?? 'confluence';
}

/**
 * Application shell with top bar, source tabs synced to the router, and settings dialog.
 *
 * Each dashboard is lazy-loaded as a child route.
 */
@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [
    NgOptimizedImage,
    RouterOutlet,
    TranslocoModule,
    ButtonModule,
    TooltipModule,
    ToastModule,
    ConfirmDialogModule,
    DialogModule,
    TabsModule,
    LanguageSelectorComponent,
    PiiSettingsComponent,
  ],
  templateUrl: './app-shell.component.html',
  styleUrl: './app-shell.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AppShellComponent {
  private readonly router = inject(Router);
  readonly themeService = inject(ThemeService);
  readonly settingsDialog = inject(SettingsDialogService);

  readonly activeSourceId = toSignal(
    this.router.events.pipe(
      filter((e): e is NavigationEnd => e instanceof NavigationEnd),
      map((e) => extractSourceFromUrl(e.urlAfterRedirects)),
      startWith(extractSourceFromUrl(this.router.url))
    ),
    { initialValue: extractSourceFromUrl(this.router.url) }
  );

  openSettingsDialog(): void {
    this.settingsDialog.open();
  }

  closeSettingsDialog(): void {
    this.settingsDialog.close();
  }

    onTabChange(tabId: string | number): void {
        if (SOURCE_ROUTES.includes(tabId as SourceId)) {
            this.router.navigate(['/', tabId]);
        }
    }

  onSettingsSaved(): void {
    // Dashboards handle their own refresh via configSaved$ subscriptions
  }
}
