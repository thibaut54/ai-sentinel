import { ChangeDetectionStrategy, Component, input } from '@angular/core';

@Component({
  selector: 'app-risk-score-badge',
  standalone: true,
  template: `<span class="risk-score-badge">{{ count() }}</span>`,
  styles: [`
    .risk-score-badge {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      min-width: 2.25rem;
      height: 1.625rem;
      padding: 0 0.625rem;
      border-radius: 6px;
      font-size: 0.8rem;
      font-weight: 700;
      color: var(--sentinel-bg-card);
      background-color: var(--sentinel-severity-total);
      letter-spacing: 0.25px;
    }
  `],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class RiskScoreBadgeComponent {
  readonly count = input(0);
}
