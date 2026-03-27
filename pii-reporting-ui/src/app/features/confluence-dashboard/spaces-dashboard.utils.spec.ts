import { TestBed } from '@angular/core/testing';
import { SpacesDashboardUtils, UISpace } from './spaces-dashboard.utils';

describe('SpacesDashboardUtils', () => {
  let utils: SpacesDashboardUtils;

  const mockSpaces = [
    { key: 'SPACE1', name: 'Alpha Space', status: 'NOT_STARTED' },
    { key: 'SPACE2', name: 'Beta Space', status: 'NOT_STARTED' },
    { key: 'SPACE3', name: 'Gamma Space', status: 'NOT_STARTED' }
  ];

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [SpacesDashboardUtils] });
    utils = TestBed.inject(SpacesDashboardUtils);
  });

  // ========== setSpaces ==========

  it('Should_InitializeUiSpaces_When_SetSpaces', () => {
    utils.setSpaces(mockSpaces as any);

    const filtered = utils.filteredSpaces();
    expect(filtered.length).toBe(3);
    expect(filtered[0].status).toBe('NOT_STARTED');
    expect(filtered[0].counts).toEqual({ total: 0, high: 0, medium: 0, low: 0 });
    expect(filtered[0].originalIndex).toBe(0);
  });

  it('Should_HandleNull_When_SetSpacesNull', () => {
    utils.setSpaces(null);
    expect(utils.filteredSpaces()).toEqual([]);
  });

  it('Should_HandleUndefined_When_SetSpacesUndefined', () => {
    utils.setSpaces(undefined);
    expect(utils.filteredSpaces()).toEqual([]);
  });

  // ========== updateSpace ==========

  it('Should_UpdateSingleSpace_When_UpdateSpace', () => {
    utils.setSpaces(mockSpaces as any);

    utils.updateSpace('SPACE1', { status: 'RUNNING', counts: { total: 5, high: 2, medium: 2, low: 1 } });

    const space = utils.filteredSpaces().find(s => s.key === 'SPACE1');
    expect(space?.status).toBe('RUNNING');
    expect(space?.counts?.total).toBe(5);
  });

  it('Should_BeCaseInsensitive_When_UpdateSpace', () => {
    utils.setSpaces(mockSpaces as any);

    utils.updateSpace('  space1 ', { status: 'OK' });

    const space = utils.filteredSpaces().find(s => s.key === 'SPACE1');
    expect(space?.status).toBe('OK');
  });

  // ========== Filtering ==========

  it('Should_FilterByGlobal_When_GlobalFilterSet', () => {
    utils.setSpaces(mockSpaces as any);

    utils.globalFilter.set('alpha');

    expect(utils.filteredSpaces().length).toBe(1);
    expect(utils.filteredSpaces()[0].key).toBe('SPACE1');
  });

  it('Should_FilterByKey_When_GlobalFilterMatchesKey', () => {
    utils.setSpaces(mockSpaces as any);

    utils.globalFilter.set('SPACE2');

    expect(utils.filteredSpaces().length).toBe(1);
    expect(utils.filteredSpaces()[0].key).toBe('SPACE2');
  });

  it('Should_FilterByName_When_OnFilterName', () => {
    utils.setSpaces(mockSpaces as any);

    utils.onFilter('name', 'beta');

    expect(utils.filteredSpaces().length).toBe(1);
    expect(utils.filteredSpaces()[0].key).toBe('SPACE2');
  });

  it('Should_FilterByStatus_When_OnFilterStatus', () => {
    utils.setSpaces(mockSpaces as any);
    utils.updateSpace('SPACE1', { status: 'RUNNING' });

    utils.onFilter('status', 'RUNNING');

    expect(utils.filteredSpaces().length).toBe(1);
    expect(utils.filteredSpaces()[0].key).toBe('SPACE1');
  });

  it('Should_ShowAll_When_FilterCleared', () => {
    utils.setSpaces(mockSpaces as any);
    utils.onFilter('status', 'RUNNING');
    expect(utils.filteredSpaces().length).toBe(0);

    utils.onFilter('status', null);
    expect(utils.filteredSpaces().length).toBe(3);
  });

  // ========== statusLabel ==========

  it('Should_ReturnCorrectLabel_When_StatusLabel', () => {
    expect(utils.statusLabel('FAILED')).toBe('En échec');
    expect(utils.statusLabel('RUNNING')).toBe('En cours');
    expect(utils.statusLabel('PAUSED')).toBe('En pause');
    expect(utils.statusLabel('PENDING')).toBe('En attente');
    expect(utils.statusLabel('NOT_STARTED')).toBe('Non démarré');
    expect(utils.statusLabel(undefined)).toBe('Non démarré');
    expect(utils.statusLabel('OK')).toBe('Terminé');
  });

  // ========== statusStyle ==========

  it('Should_ReturnCorrectSeverity_When_StatusStyle', () => {
    expect(utils.statusStyle('FAILED')).toBe('danger');
    expect(utils.statusStyle('RUNNING')).toBe('warning');
    expect(utils.statusStyle('PAUSED')).toBe('info');
    expect(utils.statusStyle('PENDING')).toBe('info');
    expect(utils.statusStyle('NOT_STARTED')).toBe('secondary');
    expect(utils.statusStyle(undefined)).toBe('secondary');
    expect(utils.statusStyle('OK')).toBe('success');
  });

  // ========== statusStyleClass ==========

  it('Should_ReturnStyleClass_When_StatusStyleClass', () => {
    expect(utils.statusStyleClass('RUNNING')).toBe('status-running');
    expect(utils.statusStyleClass('PAUSED')).toBe('status-paused');
    expect(utils.statusStyleClass('OK')).toBeUndefined();
    expect(utils.statusStyleClass(undefined)).toBeUndefined();
  });

  // ========== canViewResult ==========

  it('Should_AllowView_When_Running', () => {
    expect(utils.canViewResult({ status: 'RUNNING' } as UISpace)).toBe(true);
  });

  it('Should_AllowView_When_HasLastScanTs', () => {
    expect(utils.canViewResult({ status: 'OK', lastScanTs: '2026-01-01' } as UISpace)).toBe(true);
  });

  it('Should_DenyView_When_NeverScanned', () => {
    expect(utils.canViewResult({ status: 'NOT_STARTED' } as UISpace)).toBe(false);
  });

  // ========== hasConfluenceUrl ==========

  it('Should_ReturnTrue_When_UrlPresent', () => {
    expect(utils.hasConfluenceUrl({ url: 'https://confluence.example.com/SPACE1' } as UISpace)).toBe(true);
  });

  it('Should_ReturnFalse_When_NoUrl', () => {
    expect(utils.hasConfluenceUrl({ url: '' } as UISpace)).toBe(false);
    expect(utils.hasConfluenceUrl(null)).toBe(false);
    expect(utils.hasConfluenceUrl(undefined)).toBe(false);
  });

  // ========== getSpaceCounts ==========

  it('Should_ReturnCounts_When_SpaceExists', () => {
    utils.setSpaces(mockSpaces as any);
    utils.updateSpace('SPACE1', { counts: { total: 10, high: 3, medium: 5, low: 2 } });

    expect(utils.getSpaceCounts('SPACE1')).toEqual({ total: 10, high: 3, medium: 5, low: 2 });
  });

  it('Should_ReturnZeroCounts_When_SpaceNotFound', () => {
    expect(utils.getSpaceCounts('NONEXISTENT')).toEqual({ total: 0, high: 0, medium: 0, low: 0 });
  });

  // ========== severityCounts ==========

  it('Should_CountSeverities_When_SeverityCounts', () => {
    const items = [
      { severity: 'high' },
      { severity: 'high' },
      { severity: 'medium' },
      { severity: 'low' },
      { severity: 'low' },
      { severity: 'low' }
    ] as any;

    expect(utils.severityCounts(items)).toEqual({ total: 6, high: 2, medium: 1, low: 3 });
  });

  it('Should_ReturnZero_When_EmptyArray', () => {
    expect(utils.severityCounts([])).toEqual({ total: 0, high: 0, medium: 0, low: 0 });
  });
});
