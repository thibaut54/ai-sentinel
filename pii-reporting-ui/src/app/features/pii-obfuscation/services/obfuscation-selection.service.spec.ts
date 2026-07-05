import { describe, expect, it } from 'vitest';
import { ObfuscationSelectionService } from './obfuscation-selection.service';

describe('ObfuscationSelectionService', () => {
  function createService(): ObfuscationSelectionService {
    return new ObfuscationSelectionService();
  }

  it('Should_StartEmpty_When_Created', () => {
    const service = createService();

    expect(service.scope()).toBeNull();
    expect(service.checkedTypes().size).toBe(0);
    expect(service.checkedSeverities().size).toBe(0);
    expect(service.excludedFindingIds().size).toBe(0);
    expect(service.includedFindingIds().size).toBe(0);
  });

  it('Should_HoldScope_When_ScopeSet', () => {
    const service = createService();

    service.setScope({ spaceKey: 'SPACE', pageId: 'p1', attachmentName: 'doc.pdf' });

    expect(service.scope()).toEqual({ spaceKey: 'SPACE', pageId: 'p1', attachmentName: 'doc.pdf' });
  });

  it('Should_TrackCheckedType_When_TypeCheckedAndUnchecked', () => {
    const service = createService();

    service.checkType('EMAIL');
    service.checkType('IBAN');
    expect([...service.checkedTypes()].sort((a, b) => a.localeCompare(b))).toEqual(['EMAIL', 'IBAN']);

    service.uncheckType('EMAIL');
    expect([...service.checkedTypes()]).toEqual(['IBAN']);
  });

  it('Should_TrackCheckedSeverity_When_SeverityCheckedAndUnchecked', () => {
    const service = createService();

    service.checkSeverity('high');
    expect([...service.checkedSeverities()]).toEqual(['high']);

    service.uncheckSeverity('high');
    expect(service.checkedSeverities().size).toBe(0);
  });

  it('Should_MoveFindingToExcluded_When_FindingExcluded', () => {
    const service = createService();
    service.includeFinding('f1');

    service.excludeFinding('f1');

    expect([...service.excludedFindingIds()]).toEqual(['f1']);
    expect(service.includedFindingIds().size).toBe(0);
  });

  it('Should_MoveFindingToIncluded_When_FindingIncluded', () => {
    const service = createService();
    service.excludeFinding('f1');

    service.includeFinding('f1');

    expect([...service.includedFindingIds()]).toEqual(['f1']);
    expect(service.excludedFindingIds().size).toBe(0);
  });

  it('Should_ResetSelectionButKeepScope_When_Cleared', () => {
    const service = createService();
    service.setScope({ spaceKey: 'SPACE' });
    service.checkType('EMAIL');
    service.checkSeverity('high');
    service.excludeFinding('f1');
    service.includeFinding('f2');

    service.clear();

    expect(service.scope()).toEqual({ spaceKey: 'SPACE' });
    expect(service.checkedTypes().size).toBe(0);
    expect(service.checkedSeverities().size).toBe(0);
    expect(service.excludedFindingIds().size).toBe(0);
    expect(service.includedFindingIds().size).toBe(0);
  });

  it('Should_MirrorRawStateVerbatim_When_SelectionDtoBuilt', () => {
    const service = createService();
    service.setScope({ spaceKey: 'SPACE', pageId: 'p1' });
    service.checkType('EMAIL');
    service.checkSeverity('high');
    service.excludeFinding('f1');
    service.includeFinding('f2');

    expect(service.buildSelectionDto()).toEqual({
      scope: { spaceKey: 'SPACE', pageId: 'p1' },
      piiTypes: ['EMAIL'],
      severities: ['high'],
      excludedFindingIds: ['f1'],
      includedFindingIds: ['f2']
    });
  });

  it('Should_BuildEmptyScopedDto_When_NoScopeSet', () => {
    const service = createService();

    expect(service.buildSelectionDto()).toEqual({
      scope: { spaceKey: '' },
      piiTypes: [],
      severities: [],
      excludedFindingIds: [],
      includedFindingIds: []
    });
  });

  it('Should_ForgetFindingEverywhere_When_FindingForgotten', () => {
    const service = createService();
    service.excludeFinding('f1');
    service.includeFinding('f2');

    service.forgetFinding('f1');
    service.forgetFinding('f2');

    expect(service.excludedFindingIds().size).toBe(0);
    expect(service.includedFindingIds().size).toBe(0);
  });

  it('Should_ExposeNoAggregateComputation_When_ApiSurfaceInspected', () => {
    const service = createService();

    const memberNames = [
      ...Object.keys(service),
      ...Object.getOwnPropertyNames(Object.getPrototypeOf(service))
    ];

    const aggregatePattern = /count|total|aggregate|summary|master|plan|breakdown/i;
    expect(memberNames.filter((name) => aggregatePattern.test(name))).toEqual([]);
  });
});
