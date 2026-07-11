export type DetectorType = 'PRESIDIO' | 'REGEX' | 'MINISTRAL';

/**
 * PII Detection Configuration model matching backend DTO.
 */
export interface PiiDetectionConfig {
  presidioEnabled: boolean;
  regexEnabled: boolean;
  postfilterEnabled: boolean;
  ministralEnabled: boolean;
  ministralChunkSize: number;
  ministralOverlap: number;
  defaultThreshold: number;
  updatedAt?: string;
  updatedBy?: string;
}

/**
 * Request DTO for updating PII detection configuration.
 */
export interface UpdatePiiDetectionConfigRequest {
  presidioEnabled: boolean;
  regexEnabled: boolean;
  postfilterEnabled: boolean;
  ministralEnabled: boolean;
  ministralChunkSize: number;
  ministralOverlap: number;
  defaultThreshold: number;
}

/**
 * PII Type Configuration model matching backend PiiTypeConfigResponseDto.
 */
export interface PiiTypeConfig {
  id: number;
  piiType: string;
  detector: DetectorType;
  enabled: boolean;
  threshold: number;
  category: string;
  countryCode?: string;
  isCustom?: boolean;
  detectorLabel?: string;
  severity?: string;
  updatedAt?: string;
  updatedBy?: string;
}

/**
 * Request DTO for creating a custom PII type configuration.
 */
export interface CreatePiiTypeConfigRequest {
  piiType: string;
  detector: DetectorType;
  enabled: boolean;
  threshold: number;
  category: string;
  detectorLabel: string;
  severity: string;
  countryCode?: string;
}

/**
 * Request DTO for updating a single PII type configuration.
 */
export interface UpdatePiiTypeConfigRequest {
  piiType: string;
  detector: DetectorType;
  enabled: boolean;
  threshold: number;
}

/**
 * Request DTO for bulk updating PII type configurations.
 */
export interface BulkUpdatePiiTypeConfigRequest {
  updates: UpdatePiiTypeConfigRequest[];
}

/**
 * Open-vocabulary label proposed by Ministral that has no pii_type_config row yet,
 * collected for operator review before being promoted or ignored.
 */
export interface DiscoveredLabel {
  label: string;
  occurrenceCount: number;
  firstSeen: string;
  lastSeen: string;
  status: string;
}

/**
 * Request DTO for promoting a discovered label into a custom PII type configuration.
 */
export interface PromoteDiscoveredLabelRequest {
  category: string;
  severity: string;
  threshold: number;
  detectorLabel: string;
  countryCode?: string;
}

/**
 * Grouped PII types by detector and category for UI display.
 */
export interface GroupedPiiTypes {
  detector: 'PRESIDIO' | 'MINISTRAL';
  categories: CategoryGroup[];
}

/**
 * Category group containing PII types.
 */
export interface CategoryGroup {
  category: string;
  types: PiiTypeConfig[];
}
