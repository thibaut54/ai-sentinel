import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { BadgeModule } from 'primeng/badge';

@Component({
  selector: 'app-pii-severity-badges',
  standalone: true,
  imports: [BadgeModule],
  template: `
    <div class="flex align-items-center justify-content-center gap-1">
      <p-badge [value]="high()" severity="danger" size="large"></p-badge>
      <p-badge [value]="medium()" severity="warn" size="large"></p-badge>
      <p-badge [value]="low()" severity="info" size="large"></p-badge>
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class PiiSeverityBadgesComponent {
  readonly high = input(0);
  readonly medium = input(0);
  readonly low = input(0);
}
