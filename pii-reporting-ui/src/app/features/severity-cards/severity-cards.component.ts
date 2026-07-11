import { ChangeDetectionStrategy, Component, computed, Input, signal } from '@angular/core';
import { TranslocoModule } from '@jsverse/transloco';
import { SeverityCounts } from '../../core/models/severity-counts';

interface SeverityCard {
  labelKey: string;
  count: number;
  color: string;
  border: string;
}

@Component({
  selector: 'app-severity-cards',
  standalone: true,
  imports: [TranslocoModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './severity-cards.component.html',
  styleUrl: './severity-cards.component.css'
})
export class SeverityCardsComponent {
  private readonly _counts = signal<SeverityCounts>({ total: 0, high: 0, medium: 0, low: 0 });

  @Input({ required: true })
  set counts(value: SeverityCounts) {
    this._counts.set(value);
  }

  readonly total = computed(() => this._counts().total);

  readonly cards = computed<SeverityCard[]>(() => {
    const c = this._counts();
    return [
      { labelKey: 'severity.badgeHigh',   count: c.high,   color: '#b91c1c', border: '#eec2c2' },
      { labelKey: 'severity.badgeMedium', count: c.medium, color: '#c2740a', border: '#ecd4a5' },
      { labelKey: 'severity.badgeLow',    count: c.low,    color: '#94a3b8', border: '#cbd5e1' },
    ];
  });
}
