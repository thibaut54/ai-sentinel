import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import { PersonallyIdentifiableInformationScanResult } from '../../core/models/personally-identifiable-information-scan-result';
import { SEVERITY_STYLES } from './severity.config';
import { PiiItemCardUtils } from '../pii-item-card/pii-item-card.utils';
import { ClassificationService } from '../../core/services/classification.service';
import { ViewModeService } from '../../core/services/view-mode.service';

@Component({
  selector: 'app-pii-card-collapsed',
  standalone: true,
  imports: [TranslocoModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './pii-card-collapsed.component.html',
  styleUrl: './pii-card-collapsed.component.css',
})
export class PiiCardCollapsedComponent {
  readonly item = input.required<PersonallyIdentifiableInformationScanResult>();
  readonly expand = output<void>();

  private readonly piiItemCardUtils = inject(PiiItemCardUtils);
  private readonly translocoService = inject(TranslocoService);
  readonly viewModeService = inject(ViewModeService);
  private readonly classificationService = inject(ClassificationService);

  readonly severityStyle = computed(() => SEVERITY_STYLES[this.item().severity] ?? SEVERITY_STYLES.low);

  readonly totalDetections = computed(() =>
    this.item().detectedPersonallyIdentifiableInformationList?.length ?? 0
  );

  readonly piiTypeBadges = computed(() => {
    const mode = this.viewModeService.viewMode();
    const entities = this.item().detectedPersonallyIdentifiableInformationList ?? [];
    const counts = new Map<string, number>();

    for (const entity of entities) {
      const piiTypeCode = (entity.piiType || entity.piiTypeLabel || 'UNKNOWN').toUpperCase();
      const label = entity.piiTypeLabel || entity.piiType || 'UNKNOWN';
      let key: string;
      if (mode === 'gdpr') {
        const gdpr = this.classificationService.getGdprClassification(piiTypeCode);
        key = this.translocoService.translate(`gdpr.classification.${gdpr}.short`);
      } else if (mode === 'nlpd') {
        const nlpd = this.classificationService.getNlpdClassification(piiTypeCode);
        key = this.translocoService.translate(`nlpd.classification.${nlpd}.short`);
      } else {
        key = this.translatePiiType(label);
      }
      counts.set(key, (counts.get(key) ?? 0) + 1);
    }

    return Array.from(counts.entries()).map(([type, count]) => ({
      label: type,
      count,
    }));
  });

  readonly attachmentKind = computed(() =>
    this.piiItemCardUtils.attachmentKind(this.item().attachmentType)
  );

  private translatePiiType(key: string): string {
    if (!key) return 'Unknown';
    let cleanKey = key;
    if (key.toLowerCase().startsWith('piitype')) {
      const parts = key.split('.');
      cleanKey = parts.length > 1 ? parts.at(-1)! : key;
    }
    const normalizedKey = cleanKey.toUpperCase();
    const translationKey = `piiTypes.${normalizedKey}`;
    const translated = this.translocoService.translate(translationKey);
    const isMissing = translated === translationKey || translated.includes('piiTypes.');
    if (isMissing) {
      return cleanKey.split('_').map(w => w.charAt(0).toUpperCase() + w.slice(1).toLowerCase()).join(' ');
    }
    return translated;
  }

  onCardClick(): void {
    this.expand.emit();
  }

  onKeyDown(event: KeyboardEvent): void {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      this.expand.emit();
    }
  }
}
