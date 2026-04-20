import { Severity } from '../../core/models/severity';

export interface SeverityStyle {
  label: string;
  labelKey: string;
  color: string;
  bg: string;
  border: string;
}

export const SEVERITY_STYLES: Record<Severity, SeverityStyle> = {
  high: {
    label: 'Critique',
    labelKey: 'severity.high',
    color: '#dc2626',
    bg: '#fef2f2',
    border: '#fecaca',
  },
  medium: {
    label: 'Moyenne',
    labelKey: 'severity.medium',
    color: '#ea580c',
    bg: '#fff7ed',
    border: '#fed7aa',
  },
  low: {
    label: 'Faible',
    labelKey: 'severity.low',
    // Blue tones instead of green: any PII detection is still a problem, so the
    // card must not signal "safe/ok". Matches PrimeNG `info` severity palette.
    color: '#2563eb',
    bg: '#eff6ff',
    border: '#bfdbfe',
  },
};
