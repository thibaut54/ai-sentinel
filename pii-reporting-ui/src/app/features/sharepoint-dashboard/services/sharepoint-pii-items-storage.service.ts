import { inject, Injectable, signal } from '@angular/core';
import { ItemsBySpace } from '../../../core/models/item-by-space';
import {
    PersonallyIdentifiableInformationScanResult
} from '../../../core/models/personally-identifiable-information-scan-result';
import { DetectorSource } from '../../../core/models/detected-personally-identifiable-information';
import { ConfluenceContentPersonallyIdentifiableInformationScanResult } from '../../../core/models/stream-event-type';
import { Severity } from '../../../core/models/severity';
import { SharePointSitesDashboardUtils } from '../sharepoint-sites-dashboard.utils';

@Injectable({
  providedIn: 'root'
})
export class SharePointPiiItemsStorageService {
  private readonly dashboardUtils = inject(SharePointSitesDashboardUtils);

  readonly itemsBySite = signal<ItemsBySpace>({});

  addPiiItemToSite(siteId: string, payload: ConfluenceContentPersonallyIdentifiableInformationScanResult): boolean {
    const entities = Array.isArray(payload.detectedPIIList) ? payload.detectedPIIList : [];

    if (!entities.length) {
      return false;
    }

    const backendSeverity = payload.severity?.toLowerCase() as Severity | undefined;
    const severity: Severity = backendSeverity && ['high', 'medium', 'low'].includes(backendSeverity)
      ? backendSeverity
      : 'low';

    const piiItem: PersonallyIdentifiableInformationScanResult = {
      scanId: payload.scanId ?? '',
      spaceKey: siteId,
      pageId: String(payload.pageId ?? ''),
      pageTitle: payload.pageTitle,
      pageUrl: payload.pageUrl,
      emittedAt: payload.emittedAt,
      isFinal: !!payload.isFinal,
      severity,
      summary: (payload.nbOfDetectedPIIBySeverity && typeof payload.nbOfDetectedPIIBySeverity === 'object') ? payload.nbOfDetectedPIIBySeverity : undefined,
      piiTypeSummary: (payload.nbOfDetectedPIIByType && typeof payload.nbOfDetectedPIIByType === 'object') ? payload.nbOfDetectedPIIByType : undefined,
      detectedPersonallyIdentifiableInformationList: entities.map((e: Record<string, unknown>) => ({
        startPosition: (e?.startPosition as number) ?? 0,
        endPosition: (e?.endPosition as number) ?? 0,
        piiTypeLabel: (e?.piiTypeLabel as string) ?? (e?.piiType as string) ?? 'UNKNOWN',
        piiType: e?.piiType as string | undefined,
        sensitiveValue: e?.sensitiveValue as string | undefined,
        sensitiveContext: e?.sensitiveContext as string | undefined,
        maskedContext: e?.maskedContext as string | undefined,
        confidence: typeof e?.confidence === 'number' ? e.confidence : undefined,
        source: e?.source as DetectorSource | undefined
      })),
      attachmentName: payload.attachmentName,
      attachmentType: payload.attachmentType,
      attachmentUrl: payload.attachmentUrl
    };

    const previous = this.itemsBySite()[siteId] ?? [];

    const isDuplicate = previous.some(
      it => it.pageId === piiItem.pageId && it.attachmentName === piiItem.attachmentName
    );

    if (isDuplicate) {
      return false;
    }

    if (previous.length >= 400) {
      return false;
    }

    const nextItems = [...previous, piiItem];

    this.itemsBySite.set({ ...this.itemsBySite(), [siteId]: nextItems });
    return true;
  }

  clearAllItems(): void {
    const map = this.itemsBySite();
    this.itemsBySite.set({});
    for (const key of Object.keys(map)) {
      this.dashboardUtils.updateSite(key, {
        counts: { total: 0, high: 0, medium: 0, low: 0 }
      });
    }
  }

  clearItemsForSite(siteId: string): void {
    const map = this.itemsBySite();
    if (!map[siteId]) return;
    const { [siteId]: _, ...rest } = map;
    this.itemsBySite.set(rest);
    this.dashboardUtils.updateSite(siteId, {
      counts: { total: 0, high: 0, medium: 0, low: 0 }
    });
  }
}
