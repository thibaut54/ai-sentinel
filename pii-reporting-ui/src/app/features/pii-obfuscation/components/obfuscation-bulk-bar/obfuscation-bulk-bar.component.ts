import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { TranslocoModule } from '@jsverse/transloco';
import { ObfuscationPlanDto } from '../../../../core/models/remediation.model';
import { TestIds } from '../../../test-ids.constants';

export interface BulkChip {
  key: string;
  label: string;
  severity: string;
  selectedCount: number;
}

const MAX_VISIBLE_CHIPS = 3;

/**
 * Sticky bulk action bar. Every displayed number comes verbatim from the
 * backend plan (selection total, reported false positives) or from the
 * per-group counts of the last search response passed as chips.
 */
@Component({
  selector: 'app-obfuscation-bulk-bar',
  standalone: true,
  imports: [TranslocoModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './obfuscation-bulk-bar.component.html',
  styleUrl: './obfuscation-bulk-bar.component.css',
})
export class ObfuscationBulkBarComponent {
  readonly plan = input.required<ObfuscationPlanDto | null>();
  readonly chips = input<BulkChip[]>([]);

  readonly cleared = output<void>();
  readonly markTreated = output<void>();
  readonly obfuscate = output<void>();

  readonly testIds = TestIds.obfuscation.bulkBar;

  readonly visibleChips = computed(() => this.chips().slice(0, MAX_VISIBLE_CHIPS));
  readonly overflowChipCount = computed(() =>
    Math.max(0, this.chips().length - MAX_VISIBLE_CHIPS)
  );
}
