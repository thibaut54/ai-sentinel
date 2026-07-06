import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { TranslocoModule } from '@jsverse/transloco';
import { DetectorSource } from '../../../../core/models/detected-personally-identifiable-information';
import {
  FindingStatus,
  RemediationFindingDto,
  RemediationGroupBy
} from '../../../../core/models/remediation.model';
import { ConfidenceIndicatorComponent } from '../../../../shared/confidence-indicator/confidence-indicator.component';
import { DetectorTagComponent } from '../../../../shared/detector-tag/detector-tag.component';
import { PiiItemCardUtils } from '../../../pii-item-card/pii-item-card.utils';
import { TestIds } from '../../../test-ids.constants';

interface StatusMeta {
  labelKey: string;
  icon: string;
  variant: string;
}

const STATUS_META: Record<FindingStatus, StatusMeta> = {
  PENDING: { labelKey: 'obfuscation.status.pending', icon: 'pi-clock', variant: 'pending' },
  REDACTED: { labelKey: 'obfuscation.status.redacted', icon: 'pi-check-circle', variant: 'redacted' },
  MANUALLY_HANDLED: { labelKey: 'obfuscation.status.manual', icon: 'pi-check', variant: 'manual' },
  FALSE_POSITIVE: { labelKey: 'obfuscation.status.fp', icon: 'pi-flag', variant: 'fp' }
};

/**
 * Single finding occurrence row. Renders exactly what the backend provides
 * (selected flag, status, eligibility) and emits pure gestures — it never
 * decides selection or transition semantics itself.
 */
@Component({
  selector: 'app-obfuscation-finding-row',
  standalone: true,
  imports: [TranslocoModule, ConfidenceIndicatorComponent, DetectorTagComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './obfuscation-finding-row.component.html',
  styleUrl: './obfuscation-finding-row.component.css',
})
export class ObfuscationFindingRowComponent {
  readonly finding = input.required<RemediationFindingDto>();
  readonly groupBy = input<RemediationGroupBy>('type');

  readonly toggled = output<void>();
  readonly markManual = output<void>();
  readonly reportFalsePositive = output<void>();
  readonly restore = output<void>();

  private readonly itemCardUtils = inject(PiiItemCardUtils);

  readonly testIds = TestIds.obfuscation.row;

  readonly status = computed(() => this.finding().status);
  readonly statusMeta = computed(() => STATUS_META[this.status()]);
  readonly detectorSource = computed(() => this.finding().detector as DetectorSource);
  readonly attachmentKind = computed(() =>
    this.itemCardUtils.attachmentKind(this.finding().attachmentName)
  );

  readonly checkboxDisabled = computed(() => {
    const current = this.finding();
    return (
      current.status === 'REDACTED' ||
      current.status === 'MANUALLY_HANDLED' ||
      !current.eligibleForRedaction
    );
  });

  readonly treated = computed(
    () => this.status() === 'REDACTED' || this.status() === 'MANUALLY_HANDLED'
  );
}
