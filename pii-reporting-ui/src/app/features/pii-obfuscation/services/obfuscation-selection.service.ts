import { Injectable, signal } from '@angular/core';
import { RemediationScope, RemediationSelectionDto } from '../../../core/models/remediation.model';

/**
 * Raw obfuscation selection state: checked criteria and per-finding overrides.
 * Holds gestures only — every aggregate (counts, master states, plan) is
 * computed by the backend and must never be derived here.
 */
@Injectable()
export class ObfuscationSelectionService {
  private readonly scopeState = signal<RemediationScope | null>(null);
  private readonly checkedTypesState = signal<ReadonlySet<string>>(new Set());
  private readonly checkedSeveritiesState = signal<ReadonlySet<string>>(new Set());
  private readonly excludedFindingIdsState = signal<ReadonlySet<string>>(new Set());
  private readonly includedFindingIdsState = signal<ReadonlySet<string>>(new Set());

  readonly scope = this.scopeState.asReadonly();
  readonly checkedTypes = this.checkedTypesState.asReadonly();
  readonly checkedSeverities = this.checkedSeveritiesState.asReadonly();
  readonly excludedFindingIds = this.excludedFindingIdsState.asReadonly();
  readonly includedFindingIds = this.includedFindingIdsState.asReadonly();

  setScope(scope: RemediationScope): void {
    this.scopeState.set(scope);
  }

  checkType(piiType: string): void {
    this.checkedTypesState.update((types) => withAdded(types, piiType));
  }

  uncheckType(piiType: string): void {
    this.checkedTypesState.update((types) => withRemoved(types, piiType));
  }

  checkSeverity(severity: string): void {
    this.checkedSeveritiesState.update((severities) => withAdded(severities, severity));
  }

  uncheckSeverity(severity: string): void {
    this.checkedSeveritiesState.update((severities) => withRemoved(severities, severity));
  }

  excludeFinding(findingId: string): void {
    this.includedFindingIdsState.update((ids) => withRemoved(ids, findingId));
    this.excludedFindingIdsState.update((ids) => withAdded(ids, findingId));
  }

  includeFinding(findingId: string): void {
    this.excludedFindingIdsState.update((ids) => withRemoved(ids, findingId));
    this.includedFindingIdsState.update((ids) => withAdded(ids, findingId));
  }

  clear(): void {
    this.checkedTypesState.set(new Set());
    this.checkedSeveritiesState.set(new Set());
    this.excludedFindingIdsState.set(new Set());
    this.includedFindingIdsState.set(new Set());
  }

  buildSelectionDto(): RemediationSelectionDto {
    return {
      scope: this.scopeState() ?? { spaceKey: '' },
      piiTypes: [...this.checkedTypesState()],
      severities: [...this.checkedSeveritiesState()],
      excludedFindingIds: [...this.excludedFindingIdsState()],
      includedFindingIds: [...this.includedFindingIdsState()]
    };
  }
}

function withAdded(set: ReadonlySet<string>, value: string): ReadonlySet<string> {
  const next = new Set(set);
  next.add(value);
  return next;
}

function withRemoved(set: ReadonlySet<string>, value: string): ReadonlySet<string> {
  const next = new Set(set);
  next.delete(value);
  return next;
}
