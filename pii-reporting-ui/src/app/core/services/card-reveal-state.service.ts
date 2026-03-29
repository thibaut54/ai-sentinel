import { Injectable } from '@angular/core';
import { RevealedSecret } from './sentinelle-api.service';

/**
 * Tracks which PII cards have their secrets revealed across component re-creations.
 *
 * When p-table re-renders expanded rows (e.g. on polling updates),
 * child components are destroyed and recreated. This service
 * preserves revealed/masked state and cached secrets externally
 * so cards can restore their state on init.
 */
@Injectable({ providedIn: 'root' })
export class CardRevealStateService {
  private readonly revealedKeys = new Set<string>();
  private readonly secretsByKey = new Map<string, RevealedSecret[]>();

  isRevealed(key: string): boolean {
    return this.revealedKeys.has(key);
  }

  getSecrets(key: string): RevealedSecret[] {
    return this.secretsByKey.get(key) ?? [];
  }

  reveal(key: string, secrets: RevealedSecret[]): void {
    this.revealedKeys.add(key);
    this.secretsByKey.set(key, secrets);
  }

  mask(key: string): void {
    this.revealedKeys.delete(key);
  }

  clear(): void {
    this.revealedKeys.clear();
    this.secretsByKey.clear();
  }
}
