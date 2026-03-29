import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { TranslocoModule } from '@jsverse/transloco';

@Component({
  selector: 'app-scan-status-badge',
  standalone: true,
  imports: [TranslocoModule],
  template: `
    <output class="scan-status-badge"
            [class.scanning]="isStreaming()"
            [class.inactive]="!isStreaming()"
            [attr.data-testid]="testId()"
            [attr.aria-live]="isStreaming() ? 'polite' : 'off'"
            [attr.aria-label]="(isStreaming() ? 'dashboard.scanStatus.active' : 'dashboard.scanStatus.inactive') | transloco">
      {{ (isStreaming() ? 'dashboard.scanStatus.active' : 'dashboard.scanStatus.inactive') | transloco }}
    </output>
  `,
  styleUrl: './scan-status-badge.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class ScanStatusBadgeComponent {
  readonly isStreaming = input(false);
  readonly testId = input<string | null>(null);
}
