import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { ButtonModule } from 'primeng/button';
import { TranslocoModule } from '@jsverse/transloco';

export type SourceType = 'confluence' | 'jira' | 'sharepoint';

const SOURCE_LABELS: Record<SourceType, string> = {
  confluence: 'Confluence',
  jira: 'Jira',
  sharepoint: 'SharePoint'
};

@Component({
  selector: 'app-source-config-banner',
  standalone: true,
  imports: [ButtonModule, TranslocoModule],
  templateUrl: './source-config-banner.component.html',
  styleUrl: './source-config-banner.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class SourceConfigBannerComponent {
  readonly showBanner = input(false);
  readonly sourceType = input.required<SourceType>();
  readonly openSettings = output<void>();
  readonly dismiss = output<void>();

  readonly sourceLabel = computed(() => SOURCE_LABELS[this.sourceType()]);

  onOpenSettings(): void {
    this.openSettings.emit();
  }

  onDismiss(): void {
    this.dismiss.emit();
  }
}
