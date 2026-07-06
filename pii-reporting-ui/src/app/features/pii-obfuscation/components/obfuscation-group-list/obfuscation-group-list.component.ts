import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { TranslocoModule } from '@jsverse/transloco';
import {
  GroupMasterState,
  RemediationFindingDto,
  RemediationGroupBy,
  RemediationGroupDto
} from '../../../../core/models/remediation.model';
import { TestIds } from '../../../test-ids.constants';
import { ObfuscationFindingRowComponent } from '../obfuscation-finding-row/obfuscation-finding-row.component';

const ARIA_CHECKED_BY_STATE: Record<GroupMasterState, 'true' | 'false' | 'mixed'> = {
  all: 'true',
  none: 'false',
  partial: 'mixed'
};

/**
 * Collapsible group accordions (one per PII type or severity). Master
 * checkbox state, counts and totals are rendered verbatim from the backend
 * response; the component only emits gestures.
 */
@Component({
  selector: 'app-obfuscation-group-list',
  standalone: true,
  imports: [TranslocoModule, ObfuscationFindingRowComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './obfuscation-group-list.component.html',
  styleUrl: './obfuscation-group-list.component.css',
})
export class ObfuscationGroupListComponent {
  readonly groups = input.required<RemediationGroupDto[]>();
  readonly openKeys = input.required<ReadonlySet<string>>();
  readonly groupBy = input<RemediationGroupBy>('type');

  readonly groupToggled = output<string>();
  readonly masterToggled = output<RemediationGroupDto>();
  readonly rowToggled = output<RemediationFindingDto>();
  readonly rowMarkManual = output<RemediationFindingDto>();
  readonly rowReportFalsePositive = output<RemediationFindingDto>();
  readonly rowRestore = output<RemediationFindingDto>();

  readonly testIds = TestIds.obfuscation.group;

  isOpen(key: string): boolean {
    return this.openKeys().has(key);
  }

  ariaChecked(state: GroupMasterState): string {
    return ARIA_CHECKED_BY_STATE[state];
  }

  severityOf(group: RemediationGroupDto): string {
    const severity = this.groupBy() === 'severity' ? group.key : group.severity;
    return (severity ?? '').toLowerCase();
  }
}
