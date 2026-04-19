export type DetectorType = 'GLINER' | 'PRESIDIO' | 'REGEX';

/**
 * GDPR legal classification (EU regulation).
 * Matches backend enum `GdprDataClassification`.
 */
export type GdprDataClassification =
  | 'SPECIAL_CATEGORY'
  | 'CRIMINAL_DATA'
  | 'PERSONAL_DATA_HIGH_RISK'
  | 'PERSONAL_DATA';

/**
 * Swiss nLPD legal classification (Federal Data Protection Act).
 * Matches backend enum `NlpdDataClassification`.
 */
export type NlpdDataClassification =
  | 'SENSITIVE_DATA'
  | 'HIGH_RISK_PROFILING_DATA'
  | 'PERSONAL_DATA_HIGH_RISK'
  | 'PERSONAL_DATA';

/**
 * PII Detection Configuration model matching backend DTO.
 */
export interface PiiDetectionConfig {
  glinerEnabled: boolean;
  presidioEnabled: boolean;
  regexEnabled: boolean;
  defaultThreshold: number;
  nbOfLabelByPass: number;
  updatedAt?: string;
  updatedBy?: string;
}

/**
 * Request DTO for updating PII detection configuration.
 */
export interface UpdatePiiDetectionConfigRequest {
  glinerEnabled: boolean;
  presidioEnabled: boolean;
  regexEnabled: boolean;
  defaultThreshold: number;
  nbOfLabelByPass: number;
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
  /**
   * GDPR classification. Optional on the response for backward compatibility
   * while the backend rollout is in progress; consumers must fall back to
   * `PERSONAL_DATA` when missing.
   */
  gdprClassification?: GdprDataClassification;
  /**
   * Swiss nLPD classification. Optional for the same reason as above.
   */
  nlpdClassification?: NlpdDataClassification;
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
  /** GDPR classification, mandatory at creation (backend `@NotNull`). */
  gdprClassification: GdprDataClassification;
  /** nLPD classification, mandatory at creation (backend `@NotNull`). */
  nlpdClassification: NlpdDataClassification;
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
  /** GDPR classification, optional at update (null = no change). */
  gdprClassification?: GdprDataClassification;
  /** nLPD classification, optional at update (null = no change). */
  nlpdClassification?: NlpdDataClassification;
}

/**
 * Request DTO for bulk updating PII type configurations.
 */
export interface BulkUpdatePiiTypeConfigRequest {
  updates: UpdatePiiTypeConfigRequest[];
}

/**
 * Grouped PII types by detector and category for UI display.
 */
export interface GroupedPiiTypes {
  detector: 'GLINER' | 'PRESIDIO';
  categories: CategoryGroup[];
}

/**
 * Category group containing PII types.
 */
export interface CategoryGroup {
  category: string;
  types: PiiTypeConfig[];
}