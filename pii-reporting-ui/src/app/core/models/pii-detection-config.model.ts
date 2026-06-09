export type DetectorType = 'GLINER' | 'PRESIDIO' | 'REGEX' | 'OPENMED' | 'GLINER2';

/**
 * PII Detection Configuration model matching backend DTO.
 */
export interface PiiDetectionConfig {
  glinerEnabled: boolean;
  presidioEnabled: boolean;
  regexEnabled: boolean;
  openmedEnabled: boolean;
  gliner2Enabled: boolean;
  prefilterEnabled: boolean;
  /**
   * Read-only derived flag: true when at least one per-detector judge toggle is on.
   * Computed by the backend (OR of the five `*JudgeEnabled` flags); not sent on update.
   */
  llmJudgeEnabled: boolean;
  glinerJudgeEnabled: boolean;
  presidioJudgeEnabled: boolean;
  regexJudgeEnabled: boolean;
  openmedJudgeEnabled: boolean;
  gliner2JudgeEnabled: boolean;
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
  openmedEnabled: boolean;
  gliner2Enabled: boolean;
  prefilterEnabled: boolean;
  glinerJudgeEnabled: boolean;
  presidioJudgeEnabled: boolean;
  regexJudgeEnabled: boolean;
  openmedJudgeEnabled: boolean;
  gliner2JudgeEnabled: boolean;
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
  /**
   * Whether the LLM judge post-filtering applies to detections of this PII type.
   */
  llmJudgeEnabled: boolean;
  category: string;
  countryCode?: string;
  isCustom?: boolean;
  detectorLabel?: string;
  /**
   * Raw natural-language inference description for this GLiNER2 type (editable in the UI).
   * The backend pairs it with `detectorLabel` to build the GLiNER2 schema entry
   * `{detectorLabel: detectorDescription}`; this field carries only the description string.
   */
  detectorDescription?: string;
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
  /**
   * GLiNER2 inference description. Sent ONLY for GLINER2 rows; when omitted the
   * backend leaves the stored description unchanged ("absent = unchanged").
   */
  detectorDescription?: string;
  /**
   * Per-type LLM judge toggle. When omitted the backend leaves the stored
   * value unchanged ("absent = unchanged").
   */
  llmJudgeEnabled?: boolean;
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
  detector: 'GLINER' | 'PRESIDIO' | 'OPENMED' | 'GLINER2';
  categories: CategoryGroup[];
}

/**
 * Category group containing PII types.
 */
export interface CategoryGroup {
  category: string;
  types: PiiTypeConfig[];
}
