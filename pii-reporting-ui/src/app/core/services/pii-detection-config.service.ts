import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import {
  CreatePiiTypeConfigRequest,
  DiscoveredLabel,
  GroupedPiiTypes,
  PiiDetectionConfig,
  PiiTypeConfig,
  PromoteDiscoveredLabelRequest,
  UpdatePiiDetectionConfigRequest,
  UpdatePiiTypeConfigRequest
} from '../models/pii-detection-config.model';

/**
 * Service for managing PII detection configuration.
 */
@Injectable({providedIn: 'root'})
export class PiiDetectionConfigService {
  private readonly apiUrl = '/api/v1/pii-detection/config';
  private readonly typesApiUrl = '/api/v1/pii-detection/pii-types';
  private readonly discoveredLabelsApiUrl = '/api/v1/pii-detection/discovered-labels';

  constructor(private readonly http: HttpClient) {
  }

  /**
   * Get current PII detection configuration.
   */
  getConfig(): Observable<PiiDetectionConfig> {
    return this.http.get<PiiDetectionConfig>(this.apiUrl);
  }

  /**
   * Update PII detection configuration.
   */
  updateConfig(request: UpdatePiiDetectionConfigRequest): Observable<PiiDetectionConfig> {
    return this.http.put<PiiDetectionConfig>(this.apiUrl, request);
  }

  /**
   * Get all PII type configurations.
   */
  getAllPiiTypeConfigs(): Observable<PiiTypeConfig[]> {
    return this.http.get<PiiTypeConfig[]>(this.typesApiUrl);
  }

  /**
   * Get PII type configurations for a specific detector.
   */
  getPiiTypeConfigsByDetector(detector: 'PRESIDIO' | 'REGEX'): Observable<PiiTypeConfig[]> {
    return this.http.get<PiiTypeConfig[]>(`${this.typesApiUrl}/${detector}`);
  }

  /**
   * Get PII type configurations grouped by category.
   */
  getPiiTypeConfigsGrouped(): Observable<Record<string, Record<string, PiiTypeConfig[]>>> {
    return this.http.get<Record<string, Record<string, PiiTypeConfig[]>>>(`${this.typesApiUrl}/grouped/by-category`);
  }

  /**
   * Update a single PII type configuration.
   */
  updatePiiTypeConfig(detector: string, piiType: string, request: UpdatePiiTypeConfigRequest): Observable<PiiTypeConfig> {
    return this.http.put<PiiTypeConfig>(`${this.typesApiUrl}/${detector}/${piiType}`, request);
  }

  /**
   * Bulk update multiple PII type configurations.
   */
  bulkUpdatePiiTypeConfigs(updates: UpdatePiiTypeConfigRequest[]): Observable<PiiTypeConfig[]> {
    return this.http.put<PiiTypeConfig[]>(`${this.typesApiUrl}/bulk`, updates);
  }

  /**
   * Get PII types grouped by detector and category for UI display.
   * Backend already returns the UI-friendly structure.
   */
  getPiiTypesGroupedForUI(): Observable<GroupedPiiTypes[]> {
    return this.http.get<GroupedPiiTypes[]>(`${this.typesApiUrl}/grouped`);
  }

  /**
   * Create a custom PII type configuration.
   */
  createPiiTypeConfig(request: CreatePiiTypeConfigRequest): Observable<PiiTypeConfig> {
    return this.http.post<PiiTypeConfig>(this.typesApiUrl, request);
  }

  /**
   * Delete a custom PII type configuration.
   */
  deletePiiTypeConfig(detector: string, piiType: string): Observable<void> {
    return this.http.delete<void>(`${this.typesApiUrl}/${detector}/${piiType}`);
  }

  /**
   * Get the PENDING open-vocabulary labels discovered by Ministral awaiting review.
   */
  getDiscoveredLabels(): Observable<DiscoveredLabel[]> {
    return this.http.get<DiscoveredLabel[]>(this.discoveredLabelsApiUrl);
  }

  /**
   * Promote a discovered label into a custom PII type configuration.
   */
  promoteLabel(label: string, request: PromoteDiscoveredLabelRequest): Observable<PiiTypeConfig> {
    return this.http.post<PiiTypeConfig>(`${this.discoveredLabelsApiUrl}/${encodeURIComponent(label)}/promote`, request);
  }

  /**
   * Ignore a discovered label so it stops appearing in the review inbox.
   */
  ignoreLabel(label: string): Observable<void> {
    return this.http.post<void>(`${this.discoveredLabelsApiUrl}/${encodeURIComponent(label)}/ignore`, {});
  }
}
