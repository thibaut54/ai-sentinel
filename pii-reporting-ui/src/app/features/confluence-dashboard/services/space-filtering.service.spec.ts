import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { of } from 'rxjs';
import { SpaceFilteringService } from './space-filtering.service';
import { SpacesDashboardUtils, UISpace } from '../spaces-dashboard.utils';
import { SpaceDataManagementService } from './space-data-management.service';
import { PiiDetectionConfigService } from '../../../core/services/pii-detection-config.service';
import { PiiTypeConfig } from '../../../core/models/pii-detection-config.model';

/**
 * Builds a UISpace with sensible defaults for tests.
 */
function makeSpace(partial: Partial<UISpace> & { key: string }): UISpace {
  return {
    name: partial.key,
    status: 'OK',
    originalIndex: 0,
    counts: { total: 0, high: 0, medium: 0, low: 0 },
    piiTypeCounts: {},
    ...partial
  } as UISpace;
}

const piiConfigs: PiiTypeConfig[] = [
  { id: 1, piiType: 'EMAIL', detector: 'PRESIDIO', enabled: true, threshold: 0.5, llmJudgeEnabled: false, category: 'CONTACT', detectorLabel: 'EMAIL' },
  { id: 2, piiType: 'PHONE_NUMBER', detector: 'PRESIDIO', enabled: true, threshold: 0.5, llmJudgeEnabled: false, category: 'CONTACT', detectorLabel: 'PHONE' },
  { id: 3, piiType: 'IBAN_CODE', detector: 'REGEX', enabled: true, threshold: 0.5, llmJudgeEnabled: false, category: 'FINANCIAL', detectorLabel: 'IBAN' }
];

describe('SpaceFilteringService', () => {
  let service: SpaceFilteringService;
  let utils: SpacesDashboardUtils;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        SpaceFilteringService,
        SpacesDashboardUtils,
        { provide: SpaceDataManagementService, useValue: { spacesUpdateInfo: signal([]) } },
        { provide: PiiDetectionConfigService, useValue: { getAllPiiTypeConfigs: () => of(piiConfigs) } }
      ]
    });
    service = TestBed.inject(SpaceFilteringService);
    utils = TestBed.inject(SpacesDashboardUtils);
  });

  /** Seeds the utils with three spaces covering distinct types/severities/statuses. */
  function seedSpaces(): void {
    utils.setSpaces([{ key: 'A', name: 'Alpha' }, { key: 'B', name: 'Bravo' }, { key: 'C', name: 'Charlie' }]);
    utils.updateSpace('A', {
      status: 'OK',
      counts: { total: 3, high: 2, medium: 1, low: 0 },
      piiTypeCounts: { EMAIL: 2, IBAN_CODE: 1 },
      lastScanTs: '2026-01-03'
    });
    utils.updateSpace('B', {
      status: 'FAILED',
      counts: { total: 2, high: 0, medium: 0, low: 2 },
      piiTypeCounts: { PHONE_NUMBER: 2 },
      lastScanTs: '2026-01-01'
    });
    utils.updateSpace('C', {
      status: 'RUNNING',
      counts: { total: 1, high: 0, medium: 1, low: 0 },
      piiTypeCounts: { EMAIL: 1 },
      lastScanTs: '2026-01-02'
    });
  }

  it('Should_ReturnAllSpaces_When_NoFilterApplied', () => {
    seedSpaces();
    expect(service.filteredSpaces().map(s => s.key).sort()).toEqual(['A', 'B', 'C']);
  });

  it('Should_OrWithinPiiTypeAxis_When_MultipleTypesSelected', () => {
    seedSpaces();
    // RG-01 OR within category: EMAIL (A, C) OR IBAN_CODE (A) -> A, C
    service.piiTypeFilter.set(['EMAIL', 'IBAN_CODE']);
    expect(service.filteredSpaces().map(s => s.key).sort()).toEqual(['A', 'C']);
  });

  it('Should_AndAcrossAxes_When_TypeAndStatusSelected', () => {
    seedSpaces();
    // RG-01 AND across axes: EMAIL (A, C) AND status RUNNING (C) -> C
    service.piiTypeFilter.set(['EMAIL']);
    service.statusFilter.set(['RUNNING']);
    expect(service.filteredSpaces().map(s => s.key)).toEqual(['C']);
  });

  it('Should_ApplyInclusionThreshold_When_TypeCountIsZero', () => {
    seedSpaces();
    // RG-02 threshold: only spaces with PHONE_NUMBER count > 0 match -> B
    service.piiTypeFilter.set(['PHONE_NUMBER']);
    expect(service.filteredSpaces().map(s => s.key)).toEqual(['B']);
  });

  it('Should_MatchSeverityByBucket_When_SeveritySelected', () => {
    seedSpaces();
    // RG-02: HIGH bucket > 0 -> only A
    service.severityFilter.set(['HIGH']);
    expect(service.filteredSpaces().map(s => s.key)).toEqual(['A']);
  });

  it('Should_ComputeBiLevelPiiTypeFacets_When_OtherAxisActive', () => {
    seedSpaces();
    // RG-04: facets reflect the OTHER axes -> filter status RUNNING (only C)
    service.statusFilter.set(['RUNNING']);
    const facets = service.piiTypeFacetCounts();
    expect(facets['EMAIL']).toEqual({ nbSpaces: 1, totalOccurrences: 1 });
    expect(facets['PHONE_NUMBER']).toEqual({ nbSpaces: 0, totalOccurrences: 0 });
  });

  it('Should_SortByPiiTypeDescAndHideZeros_When_PiiTypeSortSelected', () => {
    seedSpaces();
    // RG-05: piiType:EMAIL sorts by count desc and hides B (no EMAIL)
    service.setSortCriterion('piiType:EMAIL');
    expect(service.sortedSpaces().map(s => s.key)).toEqual(['A', 'C']);
  });

  it('Should_SortBySeverityScore_When_SeverityScoreSelected', () => {
    seedSpaces();
    // A (2 high) > C (1 medium) > B (2 low)
    service.setSortCriterion('severityScore');
    expect(service.sortedSpaces().map(s => s.key)).toEqual(['A', 'C', 'B']);
  });

  it('Should_SearchOnFilteredSet_When_SearchAndFilterCombined', () => {
    seedSpaces();
    // RG-06: filter EMAIL (A, C) then search "alp" -> A only
    service.piiTypeFilter.set(['EMAIL']);
    service.onGlobalChange('alp');
    expect(service.searchedSpaces().map(s => s.key)).toEqual(['A']);
  });

  it('Should_ReportResettable_When_AnyAxisActive', () => {
    seedSpaces();
    expect(service.isResettable()).toBe(false);
    service.severityFilter.set(['HIGH']);
    expect(service.isResettable()).toBe(true);
  });

  it('Should_ClearAllState_When_ResetCalled', () => {
    seedSpaces();
    service.piiTypeFilter.set(['EMAIL']);
    service.severityFilter.set(['HIGH']);
    service.statusFilter.set(['OK']);
    service.onGlobalChange('alp');
    service.setSortCriterion('name');

    service.reset();

    expect(service.piiTypeFilter()).toEqual([]);
    expect(service.severityFilter()).toEqual([]);
    expect(service.statusFilter()).toEqual([]);
    expect(service.globalFilter()).toBe('');
    expect(service.sortCriterion()).toBeNull();
    expect(service.isResettable()).toBe(false);
  });

  it('Should_GroupPiiTypesByCategory_When_ConfigsLoaded', () => {
    const groups = service.piiTypeGroups();
    const contact = groups.find(g => g.category === 'CONTACT');
    expect(contact?.items.map(i => i.code).sort()).toEqual(['EMAIL', 'PHONE_NUMBER']);
  });
});
