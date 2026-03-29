import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { ButtonModule } from 'primeng/button';
import { TooltipModule } from 'primeng/tooltip';
import { TranslocoModule } from '@jsverse/transloco';
import type { DashboardTestIds } from '../../../features/test-ids.constants';

@Component({
  selector: 'app-scan-actions-group',
  standalone: true,
  imports: [ButtonModule, TooltipModule, TranslocoModule],
  templateUrl: './scan-actions-group.component.html',
  styleUrl: './scan-actions-group.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class ScanActionsGroupComponent {
  readonly isStreaming = input(false);
  readonly canStartScan = input(false);
  readonly canResumeScan = input(false);
  readonly selectedCount = input(0);
  readonly showResume = input(false);
  readonly testIds = input<DashboardTestIds | null>(null);

  readonly startAll = output<void>();
  readonly startSelected = output<void>();
  readonly pauseScan = output<void>();
  readonly resumeScan = output<void>();

  onStart(): void {
    if (this.selectedCount() > 0) {
      this.startSelected.emit();
    } else {
      this.startAll.emit();
    }
  }
}
