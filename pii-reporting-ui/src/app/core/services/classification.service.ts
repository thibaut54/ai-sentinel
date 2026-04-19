import { inject, Injectable, signal } from '@angular/core';
import {
  GdprDataClassification,
  NlpdDataClassification,
  PiiTypeConfig
} from '../models/pii-detection-config.model';
import { PiiDetectionConfigService } from './pii-detection-config.service';

/** PrimeNG badge severities mapped to legal classification values. */
export type LegalBadgeSeverity = 'danger' | 'warn' | 'info' | 'success';

/** Ordered list of the four GDPR classification values used for UI filters / dropdowns. */
export const GDPR_CLASSIFICATION_VALUES: readonly GdprDataClassification[] = [
  'SPECIAL_CATEGORY',
  'CRIMINAL_DATA',
  'PERSONAL_DATA_HIGH_RISK',
  'PERSONAL_DATA',
] as const;

/** Ordered list of the four nLPD classification values used for UI filters / dropdowns. */
export const NLPD_CLASSIFICATION_VALUES: readonly NlpdDataClassification[] = [
  'SENSITIVE_DATA',
  'HIGH_RISK_PROFILING_DATA',
  'PERSONAL_DATA_HIGH_RISK',
  'PERSONAL_DATA',
] as const;

/** Single source of truth mapping a classification label to a PrimeNG badge severity. */
const BADGE_SEVERITY_BY_CLASSIFICATION: Record<string, LegalBadgeSeverity> = {
  SPECIAL_CATEGORY: 'danger',
  CRIMINAL_DATA: 'warn',
  SENSITIVE_DATA: 'danger',
  HIGH_RISK_PROFILING_DATA: 'warn',
  PERSONAL_DATA_HIGH_RISK: 'info',
  PERSONAL_DATA: 'success',
};

/**
 * Legal classification service.
 *
 * Keeps two `piiType -> classification` maps in memory so that UI components
 * can query the active classification without re-hitting the backend.
 *
 * Fallback on missing data: `PERSONAL_DATA` (safe conservative value).
 */
/** Per-PII-type severity coming from backend config (HIGH/MEDIUM/LOW). */
export type PiiSeverity = 'HIGH' | 'MEDIUM' | 'LOW';

@Injectable({ providedIn: 'root' })
export class ClassificationService {
  private static readonly DEFAULT_GDPR: GdprDataClassification = 'PERSONAL_DATA';
  private static readonly DEFAULT_NLPD: NlpdDataClassification = 'PERSONAL_DATA';
  private static readonly DEFAULT_SEVERITY: PiiSeverity = 'LOW';

  private readonly configService = inject(PiiDetectionConfigService);

  private readonly gdprMap = signal<Record<string, GdprDataClassification>>({});
  private readonly nlpdMap = signal<Record<string, NlpdDataClassification>>({});
  private readonly severityMap = signal<Record<string, PiiSeverity>>({});
  private readonly loaded = signal<boolean>(false);

  constructor() {
    this.refresh();
  }

  /**
   * Reload both classification maps from the backend.
   * Gracefully handles a not-yet-deployed backend (undefined fields) or
   * missing HTTP provider in tests.
   */
  refresh(): void {
    try {
      this.configService.getAllPiiTypeConfigs().subscribe({
        next: configs => this.hydrateMaps(configs),
        error: err => {
          // Backend may not be ready. Keep previous state; fallbacks handle missing keys.
          // We warn explicitly so the legal lens doesn't silently display PERSONAL_DATA
          // for data that should be SPECIAL_CATEGORY/SENSITIVE_DATA.
          console.warn('[ClassificationService] Failed to load classifications, falling back to PERSONAL_DATA', err);
          this.loaded.set(true);
        }
      });
    } catch (err) {
      // Missing HttpClient provider in tests, or synchronous throw.
      console.warn('[ClassificationService] Could not reach the config service', err);
      this.loaded.set(true);
    }
  }

  /**
   * Get the GDPR classification for a given PII type.
   * Returns `PERSONAL_DATA` when unknown.
   */
  getGdprClassification(piiType: string): GdprDataClassification {
    return this.gdprMap()[piiType] ?? ClassificationService.DEFAULT_GDPR;
  }

  /**
   * Get the nLPD classification for a given PII type.
   * Returns `PERSONAL_DATA` when unknown.
   */
  getNlpdClassification(piiType: string): NlpdDataClassification {
    return this.nlpdMap()[piiType] ?? ClassificationService.DEFAULT_NLPD;
  }

  /**
   * Get the configured severity for a PII type (HIGH/MEDIUM/LOW).
   * Returns `LOW` when unknown.
   */
  getSeverity(piiType: string): PiiSeverity {
    return this.severityMap()[piiType] ?? ClassificationService.DEFAULT_SEVERITY;
  }

  /** Map a severity value to a PrimeNG badge severity. */
  severityBadgeSeverity(value: PiiSeverity | undefined): LegalBadgeSeverity {
    switch (value) {
      case 'HIGH':
        return 'danger';
      case 'MEDIUM':
        return 'warn';
      default:
        return 'success';
    }
  }

  /**
   * Cross-regime mapping GDPR -> nLPD for the creation dialog auto-fill.
   *
   * Conservative rules:
   * - SPECIAL_CATEGORY or CRIMINAL_DATA (GDPR) -> SENSITIVE_DATA (nLPD)
   * - PERSONAL_DATA_HIGH_RISK                  -> PERSONAL_DATA_HIGH_RISK
   * - PERSONAL_DATA                            -> PERSONAL_DATA
   */
  mapGdprToNlpd(gdpr: GdprDataClassification): NlpdDataClassification {
    switch (gdpr) {
      case 'SPECIAL_CATEGORY':
      case 'CRIMINAL_DATA':
        return 'SENSITIVE_DATA';
      case 'PERSONAL_DATA_HIGH_RISK':
        return 'PERSONAL_DATA_HIGH_RISK';
      default:
        return 'PERSONAL_DATA';
    }
  }

  /**
   * Cross-regime mapping nLPD -> GDPR for the creation dialog auto-fill.
   *
   * Conservative rules (user can override manually):
   * - SENSITIVE_DATA             -> SPECIAL_CATEGORY
   * - HIGH_RISK_PROFILING_DATA   -> PERSONAL_DATA_HIGH_RISK
   * - PERSONAL_DATA_HIGH_RISK    -> PERSONAL_DATA_HIGH_RISK
   * - PERSONAL_DATA              -> PERSONAL_DATA
   */
  mapNlpdToGdpr(nlpd: NlpdDataClassification): GdprDataClassification {
    switch (nlpd) {
      case 'SENSITIVE_DATA':
        return 'SPECIAL_CATEGORY';
      case 'HIGH_RISK_PROFILING_DATA':
        return 'PERSONAL_DATA_HIGH_RISK';
      case 'PERSONAL_DATA_HIGH_RISK':
        return 'PERSONAL_DATA_HIGH_RISK';
      default:
        return 'PERSONAL_DATA';
    }
  }

  /** True once the first load attempt has completed (success or failure). */
  isLoaded(): boolean {
    return this.loaded();
  }

  /**
   * Map any legal classification label (GDPR or nLPD) to its PrimeNG badge severity.
   * Falls back to `success` (green) for unknown values so the UI never crashes.
   */
  badgeSeverity(value: GdprDataClassification | NlpdDataClassification | undefined): LegalBadgeSeverity {
    return BADGE_SEVERITY_BY_CLASSIFICATION[value ?? 'PERSONAL_DATA'] ?? 'success';
  }

  private hydrateMaps(configs: PiiTypeConfig[]): void {
    const gdpr: Record<string, GdprDataClassification> = {};
    const nlpd: Record<string, NlpdDataClassification> = {};
    const severity: Record<string, PiiSeverity> = {};
    for (const config of configs) {
      if (config.gdprClassification) {
        gdpr[config.piiType] = config.gdprClassification;
      }
      if (config.nlpdClassification) {
        nlpd[config.piiType] = config.nlpdClassification;
      }
      if (config.severity === 'HIGH' || config.severity === 'MEDIUM' || config.severity === 'LOW') {
        severity[config.piiType] = config.severity;
      }
    }
    this.gdprMap.set(gdpr);
    this.nlpdMap.set(nlpd);
    this.severityMap.set(severity);
    this.loaded.set(true);
  }
}
