import { Injectable, signal } from '@angular/core';
import type { SettingsSection } from '../../features/pii-settings/pii-settings.component';

@Injectable({ providedIn: 'root' })
export class SettingsDialogService {
  private readonly _visible = signal(false);
  private readonly _initialSection = signal<SettingsSection>('detectors');

  readonly visible = this._visible.asReadonly();
  readonly initialSection = this._initialSection.asReadonly();

  open(section: SettingsSection = 'detectors'): void {
    this._initialSection.set(section);
    this._visible.set(true);
  }

  close(): void {
    this._visible.set(false);
    this._initialSection.set('detectors');
  }
}
