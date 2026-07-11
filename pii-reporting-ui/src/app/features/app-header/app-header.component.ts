import { ChangeDetectionStrategy, Component, inject, output, signal } from '@angular/core';
import { NgOptimizedImage } from '@angular/common';
import { RouterLink } from '@angular/router';
import { TranslocoModule } from '@jsverse/transloco';
import { ButtonModule } from 'primeng/button';
import { TabsModule } from 'primeng/tabs';
import { LanguageSelectorComponent } from '../../core/components/language-selector/language-selector.component';
import { ThemeService } from '../../core/services/theme.service';

/**
 * Shared application top bar: brand, theme toggle, settings and language.
 *
 * Rendered by every top-level view so the header stays consistent when
 * navigating between the dashboard and the obfuscation view. The brand links
 * back to the dashboard; the settings action is delegated to the host so each
 * view can open it the way it prefers (dialog on the dashboard, route elsewhere).
 */
@Component({
  selector: 'app-header',
  standalone: true,
  imports: [NgOptimizedImage, RouterLink, TranslocoModule, ButtonModule, TabsModule, LanguageSelectorComponent],
  templateUrl: './app-header.component.html',
  styleUrl: './app-header.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class AppHeaderComponent {
  readonly themeService = inject(ThemeService);
  readonly openSettings = output<void>();

  // Data source selector. Only Confluence exists today; kept as a tab bar so it
  // reads as a source picker and stays consistent across every top-level view.
  readonly activeSourceId = signal('confluence');
}
