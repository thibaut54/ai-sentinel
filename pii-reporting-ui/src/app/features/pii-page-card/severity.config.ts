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
    color: '#b91c1c',
    bg: '#fef2f2',
    border: '#eec2c2',
  },
  medium: {
    label: 'Moyenne',
    labelKey: 'severity.medium',
    color: '#c2740a',
    bg: '#fdf6ec',
    border: '#ecd4a5',
  },
  low: {
    label: 'Faible',
    labelKey: 'severity.low',
    color: '#64748b',
    bg: '#f8fafc',
    border: '#cbd5e1',
  },
};
