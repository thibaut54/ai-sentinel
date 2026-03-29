import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { TagModule } from 'primeng/tag';

@Component({
  selector: 'app-status-tag',
  standalone: true,
  imports: [TagModule],
  template: `
    <p-tag [value]="label()"
           [severity]="severity()"
           [styleClass]="'status-tag ' + extraStyleClass()"></p-tag>
  `,
  styleUrl: './status-tag.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class StatusTagComponent {
  readonly status = input<string>();

  readonly label = computed(() => {
    const s = this.status();
    if (s === 'FAILED') return 'En échec';
    if (s === 'RUNNING') return 'En cours';
    if (s === 'PAUSED') return 'En pause';
    if (s === 'PENDING' || !s) return 'Non démarré';
    return 'Terminé';
  });

  readonly severity = computed<'danger' | 'warning' | 'success' | 'info' | 'secondary'>(() => {
    const s = this.status();
    if (s === 'FAILED') return 'danger';
    if (s === 'RUNNING') return 'warning';
    if (s === 'PAUSED') return 'info';
    if (s === 'PENDING' || !s) return 'secondary';
    return 'success';
  });

  protected readonly extraStyleClass = computed(() => {
    const s = this.status();
    if (s === 'RUNNING') return 'status-running';
    if (s === 'PAUSED') return 'status-paused';
    return '';
  });
}
