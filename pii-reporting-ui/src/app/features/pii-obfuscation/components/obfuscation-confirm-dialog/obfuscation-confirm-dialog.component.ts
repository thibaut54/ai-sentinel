import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { TranslocoModule } from '@jsverse/transloco';
import { DialogModule } from 'primeng/dialog';
import { ObfuscationPlanDto } from '../../../../core/models/remediation.model';
import { TestIds } from '../../../test-ids.constants';

interface SeverityBreakdownRow {
  severity: string;
  count: number;
}

const SEVERITY_DISPLAY_ORDER = ['high', 'medium', 'low'];

function severityRank(severity: string): number {
  const index = SEVERITY_DISPLAY_ORDER.indexOf(severity.toLowerCase());
  return index === -1 ? SEVERITY_DISPLAY_ORDER.length : index;
}

/**
 * Destructive confirmation dialog. Displays the backend-computed
 * ObfuscationPlan verbatim (total, severity breakdown, reported false
 * positives) and emits confirm/cancel gestures only.
 */
@Component({
  selector: 'app-obfuscation-confirm-dialog',
  standalone: true,
  imports: [TranslocoModule, DialogModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './obfuscation-confirm-dialog.component.html',
  styleUrl: './obfuscation-confirm-dialog.component.css',
})
export class ObfuscationConfirmDialogComponent {
  readonly visible = input.required<boolean>();
  readonly plan = input.required<ObfuscationPlanDto | null>();
  readonly spaceKey = input('');

  readonly confirmed = output<void>();
  readonly cancelled = output<void>();

  readonly testIds = TestIds.obfuscation.confirmDialog;

  readonly totalFindings = computed(() => this.plan()?.totalFindings ?? 0);
  readonly falsePositivesReported = computed(() => this.plan()?.falsePositivesReported ?? 0);

  readonly severityRows = computed<SeverityBreakdownRow[]>(() => {
    const bySeverity = this.plan()?.bySeverity ?? {};
    return Object.entries(bySeverity)
      .map(([severity, count]) => ({ severity, count }))
      .sort((a, b) => severityRank(a.severity) - severityRank(b.severity));
  });
}
