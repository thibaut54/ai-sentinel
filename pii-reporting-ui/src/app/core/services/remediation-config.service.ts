import { Injectable, signal } from '@angular/core';
import { Observable, of } from 'rxjs';
import { catchError, map, tap } from 'rxjs/operators';
import { RemediationApiService } from './remediation-api.service';

/**
 * Facade holding the pii.remediation.enabled feature flag state.
 * Entry points stay hidden until the backend confirms the feature is enabled.
 */
@Injectable({ providedIn: 'root' })
export class RemediationConfigService {
  /** Signal holding the remediation enabled configuration state. */
  readonly enabled = signal<boolean>(false);

  constructor(private readonly remediationApi: RemediationApiService) {
  }

  /**
   * Loads the remediation configuration from backend and updates the signal.
   * Intended to be called during app initialization.
   */
  loadRemediationConfig(): Observable<boolean> {
    return this.remediationApi.getConfig().pipe(
      map((config) => config.enabled),
      tap((enabled) => this.enabled.set(enabled)),
      catchError(() => {
        this.enabled.set(false);
        return of(false);
      })
    );
  }
}
