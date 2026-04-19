import { DetectorSource } from '../../core/models/detected-personally-identifiable-information';
import { GdprDataClassification, NlpdDataClassification } from '../../core/models/pii-detection-config.model';
import { PiiSeverity } from '../../core/services/classification.service';

export interface ValuePart {
  text: string;
  isBadge: boolean;
  isHighlighted?: boolean;
}

export interface PiiEntityRow {
  typeLabel: string;
  /** Raw piiType code (e.g. EMAIL) used for classification lookup. */
  piiTypeCode: string;
  value: string;
  valueParts: ValuePart[];
  isRevealed: boolean;
  confidence: number;
  detector: DetectorSource;
  severity: PiiSeverity;
  gdprClassification: GdprDataClassification;
  nlpdClassification: NlpdDataClassification;
}
