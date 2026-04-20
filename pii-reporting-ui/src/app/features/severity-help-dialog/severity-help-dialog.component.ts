import { ChangeDetectionStrategy, Component, effect, inject, model, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { DialogModule } from 'primeng/dialog';
import { TabsModule } from 'primeng/tabs';
import { BadgeModule } from 'primeng/badge';
import { ButtonModule } from 'primeng/button';
import { TranslocoModule } from '@jsverse/transloco';
import { ViewModeService } from '../../core/services/view-mode.service';
import { GdprDataClassification, NlpdDataClassification } from '../../core/models/pii-detection-config.model';

type HelpTab = 'standard' | 'gdpr' | 'nlpd';
type StandardLevel = 'high' | 'medium' | 'low' | 'score';
type LegalSeverity = 'danger' | 'warn' | 'info';

interface StandardEntry {
  readonly level: StandardLevel;
  readonly badge: 'danger' | 'warn' | 'info' | 'success';
  readonly i18nKey: string;
}

interface LegalEntry<TClass extends string> {
  readonly classification: TClass;
  readonly badge: LegalSeverity;
  readonly i18nKey: string;
}

@Component({
  selector: 'app-severity-help-dialog',
  standalone: true,
  imports: [CommonModule, DialogModule, TabsModule, BadgeModule, ButtonModule, TranslocoModule],
  templateUrl: './severity-help-dialog.component.html',
  styleUrl: './severity-help-dialog.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class SeverityHelpDialogComponent {
  private readonly viewModeService = inject(ViewModeService);

  readonly visible = model<boolean>(false);
  readonly activeTab = signal<HelpTab>('standard');

  readonly standardEntries: readonly StandardEntry[] = [
    { level: 'high', badge: 'danger', i18nKey: 'piiHelp.standard.high' },
    { level: 'medium', badge: 'warn', i18nKey: 'piiHelp.standard.medium' },
    { level: 'low', badge: 'info', i18nKey: 'piiHelp.standard.low' },
    { level: 'score', badge: 'success', i18nKey: 'piiHelp.standard.score' }
  ];

  readonly gdprEntries: readonly LegalEntry<GdprDataClassification>[] = [
    { classification: 'SPECIAL_CATEGORY', badge: 'danger', i18nKey: 'piiHelp.gdpr.SPECIAL_CATEGORY' },
    { classification: 'CRIMINAL_DATA', badge: 'warn', i18nKey: 'piiHelp.gdpr.CRIMINAL_DATA' },
    { classification: 'PERSONAL_DATA_HIGH_RISK', badge: 'info', i18nKey: 'piiHelp.gdpr.PERSONAL_DATA_HIGH_RISK' },
    { classification: 'PERSONAL_DATA', badge: 'info', i18nKey: 'piiHelp.gdpr.PERSONAL_DATA' }
  ];

  readonly nlpdEntries: readonly LegalEntry<NlpdDataClassification>[] = [
    { classification: 'SENSITIVE_DATA', badge: 'danger', i18nKey: 'piiHelp.nlpd.SENSITIVE_DATA' },
    { classification: 'HIGH_RISK_PROFILING_DATA', badge: 'warn', i18nKey: 'piiHelp.nlpd.HIGH_RISK_PROFILING_DATA' },
    { classification: 'PERSONAL_DATA_HIGH_RISK', badge: 'info', i18nKey: 'piiHelp.nlpd.PERSONAL_DATA_HIGH_RISK' },
    { classification: 'PERSONAL_DATA', badge: 'info', i18nKey: 'piiHelp.nlpd.PERSONAL_DATA' }
  ];

  constructor() {
    effect(() => {
      if (this.visible()) {
        const mode = this.viewModeService.viewMode();
        this.activeTab.set(mode === 'gdpr' ? 'gdpr' : mode === 'nlpd' ? 'nlpd' : 'standard');
      }
    });
  }

  onTabChange(value: string | number): void {
    this.activeTab.set(value as HelpTab);
  }

  badgeClassFor(badge: 'danger' | 'warn' | 'info' | 'success' | LegalSeverity): string {
    return `severity-badge-icon severity-badge-${badge}`;
  }
}
