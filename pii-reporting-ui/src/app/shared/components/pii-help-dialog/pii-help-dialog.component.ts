import { ChangeDetectionStrategy, Component, model } from '@angular/core';
import { TranslocoModule } from '@jsverse/transloco';
import { DialogModule } from 'primeng/dialog';
import { BadgeModule } from 'primeng/badge';

@Component({
  selector: 'app-pii-help-dialog',
  standalone: true,
  imports: [TranslocoModule, DialogModule, BadgeModule],
  templateUrl: './pii-help-dialog.component.html',
  styleUrl: './pii-help-dialog.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class PiiHelpDialogComponent {
  readonly visible = model(false);
}
