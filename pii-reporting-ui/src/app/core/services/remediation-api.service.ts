import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import {
  ChangeFindingsStatusRequest,
  ChangeFindingsStatusResponse,
  CreateObfuscationJobRequest,
  CreateObfuscationJobResponse,
  ObfuscationJobDto,
  ObfuscationPlanDto,
  RemediationConfigDto,
  RemediationFindingsSearchRequest,
  RemediationFindingsSearchResponse,
  RemediationSelectionDto,
  SelectionStatusChangeRequest
} from '../models/remediation.model';

/**
 * Pure HTTP client for the PII remediation (obfuscation) API.
 * One method per endpoint, no business logic: all aggregation and
 * validation rules live in the backend.
 */
@Injectable({ providedIn: 'root' })
export class RemediationApiService {
  private readonly apiUrl = '/api/v1/pii/remediation';

  constructor(private readonly http: HttpClient) {
  }

  /**
   * Get the remediation feature configuration (drives UI entry visibility).
   */
  getConfig(): Observable<RemediationConfigDto> {
    return this.http.get<RemediationConfigDto>(`${this.apiUrl}/config`);
  }

  /**
   * Search findings grouped and paginated by the backend.
   * POST because the current selection (many exclusions) travels in the body.
   */
  searchFindings(request: RemediationFindingsSearchRequest): Observable<RemediationFindingsSearchResponse> {
    return this.http.post<RemediationFindingsSearchResponse>(`${this.apiUrl}/findings/search`, request);
  }

  /**
   * Compute the obfuscation plan (totals, severity breakdown, checksum) for a selection.
   */
  planObfuscation(selection: RemediationSelectionDto): Observable<ObfuscationPlanDto> {
    return this.http.post<ObfuscationPlanDto>(`${this.apiUrl}/plan`, selection);
  }

  /**
   * Start an asynchronous obfuscation job. The backend answers 409 when the
   * selection checksum is outdated, in which case the UI must re-plan.
   */
  createJob(request: CreateObfuscationJobRequest): Observable<CreateObfuscationJobResponse> {
    return this.http.post<CreateObfuscationJobResponse>(`${this.apiUrl}/jobs`, request);
  }

  /**
   * Poll an obfuscation job status, progression and per-finding outcomes.
   */
  getJob(jobId: string): Observable<ObfuscationJobDto> {
    const id = encodeURIComponent(jobId);
    return this.http.get<ObfuscationJobDto>(`${this.apiUrl}/jobs/${id}`);
  }

  /**
   * Apply finding status transitions (false positive, manually handled, restore).
   */
  changeFindingsStatus(request: ChangeFindingsStatusRequest): Observable<ChangeFindingsStatusResponse> {
    return this.http.post<ChangeFindingsStatusResponse>(`${this.apiUrl}/findings/status`, request);
  }

  /**
   * Transition every PENDING finding of a selection to a target status server-side,
   * so bulk actions cover the whole selection instead of the current page slice.
   */
  changeFindingsStatusBySelection(
    request: SelectionStatusChangeRequest
  ): Observable<ChangeFindingsStatusResponse> {
    return this.http.post<ChangeFindingsStatusResponse>(
      `${this.apiUrl}/findings/status/by-selection`,
      request
    );
  }
}
