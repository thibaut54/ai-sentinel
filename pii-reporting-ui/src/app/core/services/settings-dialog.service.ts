import { Injectable, signal } from '@angular/core';

@Injectable({ providedIn: 'root' })
export class SettingsDialogService {
  private readonly _visible = signal(false);
  private readonly _initialTab = signal(0);

  readonly visible = this._visible.asReadonly();
  readonly initialTab = this._initialTab.asReadonly();

  open(tab = 0): void {
    this._initialTab.set(tab);
    this._visible.set(true);
  }

  close(): void {
    this._visible.set(false);
  }
}
