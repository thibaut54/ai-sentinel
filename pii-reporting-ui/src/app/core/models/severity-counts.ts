/**
 * PII severity counts - matches backend SeverityCountsDto
 */
export interface SeverityCounts {
  high: number;
  medium: number;
  low: number;
  total: number;
}

/**
 * PII counts aggregated by legal classification - matches backend ClassificationCountsDto.
 * Exposed alongside {@link SeverityCounts} to power the tri-mode dashboard column.
 */
export interface ClassificationCounts {
  gdprSpecialCategory: number;
  gdprCriminalData: number;
  gdprPersonalDataHighRisk: number;
  gdprPersonalData: number;
  nlpdSensitiveData: number;
  nlpdHighRiskProfilingData: number;
  nlpdPersonalDataHighRisk: number;
  nlpdPersonalData: number;
}
