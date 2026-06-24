import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { ActivatedRoute, convertToParamMap, ParamMap, Router } from '@angular/router';
import { vi } from 'vitest';
import { of } from 'rxjs';
import { FilterUrlStateService } from './filter-url-state.service';
import { SpaceFilteringService } from './space-filtering.service';
import { SpacesDashboardUtils } from '../spaces-dashboard.utils';
import { SpaceDataManagementService } from './space-data-management.service';
import { PiiDetectionConfigService } from '../../../core/services/pii-detection-config.service';

/**
 * Creates the service under test with a controllable query-param snapshot.
 */
function setup(queryParams: Record<string, string>): {
  filtering: SpaceFilteringService;
  navigate: ReturnType<typeof vi.fn>;
} {
  const paramMap: ParamMap = convertToParamMap(queryParams);
  const navigate = vi.fn().mockResolvedValue(true);

  TestBed.configureTestingModule({
    providers: [
      FilterUrlStateService,
      SpaceFilteringService,
      SpacesDashboardUtils,
      { provide: SpaceDataManagementService, useValue: { spacesUpdateInfo: signal([]) } },
      { provide: PiiDetectionConfigService, useValue: { getAllPiiTypeConfigs: () => of([]) } },
      { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: paramMap } } },
      { provide: Router, useValue: { navigate } }
    ]
  });

  const filtering = TestBed.inject(SpaceFilteringService);
  TestBed.inject(FilterUrlStateService);
  return { filtering, navigate };
}

describe('FilterUrlStateService', () => {
  it('Should_HydrateStateFromUrl_When_QueryParamsPresent', () => {
    const { filtering } = setup({
      piiTypes: 'EMAIL,PHONE_NUMBER',
      sev: 'HIGH',
      status: 'OK',
      sort: 'lastScan:desc',
      q: 'alpha'
    });

    expect(filtering.piiTypeFilter()).toEqual(['EMAIL', 'PHONE_NUMBER']);
    expect(filtering.severityFilter()).toEqual(['HIGH']);
    expect(filtering.statusFilter()).toEqual(['OK']);
    expect(filtering.sortCriterion()).toBe('lastScan');
    expect(filtering.sortOrder()).toBe(-1);
    expect(filtering.globalFilter()).toBe('alpha');
  });

  it('Should_WriteStateToUrl_When_FilterChanges', async () => {
    const { filtering, navigate } = setup({});
    navigate.mockClear();

    filtering.piiTypeFilter.set(['EMAIL']);
    filtering.setSortCriterion('severityScore', 1);
    TestBed.tick();

    expect(navigate).toHaveBeenCalled();
    const lastCall = navigate.mock.calls.at(-1)!;
    const params = lastCall[1].queryParams;
    expect(params.piiTypes).toBe('EMAIL');
    expect(params.sort).toBe('severityScore:asc');
    // RG-08: empty axes are omitted (nulled)
    expect(params.sev).toBeNull();
  });
});
