import { inject, Injectable, signal } from '@angular/core';
import { ItemsBySpace } from '../../../core/models/item-by-space';
import {
  PersonallyIdentifiableInformationScanResult
} from '../../../core/models/personally-identifiable-information-scan-result';
import { DetectorSource } from '../../../core/models/detected-personally-identifiable-information';
import { ConfluenceContentPersonallyIdentifiableInformationScanResult } from '../../../core/models/stream-event-type';
import { Severity } from '../../../core/models/severity';
import { JiraProjectsDashboardUtils } from '../jira-projects-dashboard.utils';

@Injectable({
  providedIn: 'root'
})
export class JiraPiiItemsStorageService {
  private readonly dashboardUtils = inject(JiraProjectsDashboardUtils);

  readonly itemsByProject = signal<ItemsBySpace>({});

  addPiiItemToProject(projectKey: string, payload: ConfluenceContentPersonallyIdentifiableInformationScanResult): boolean {
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
      spaceKey: projectKey,
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

    const previous = this.itemsByProject()[projectKey] ?? [];

    const isDuplicate = previous.some(
      it => it.pageId === piiItem.pageId && it.attachmentName === piiItem.attachmentName
    );

    if (isDuplicate) {
      return false;
    }

    const nextItems = [...previous, piiItem];
    if (nextItems.length > 400) {
      nextItems.length = 400;
    }

    this.itemsByProject.set({ ...this.itemsByProject(), [projectKey]: nextItems });
    return true;
  }

  clearAllItems(): void {
    const map = this.itemsByProject();
    this.itemsByProject.set({});
    for (const key of Object.keys(map)) {
      this.dashboardUtils.updateProject(key, {
        counts: { total: 0, high: 0, medium: 0, low: 0 }
      });
    }
  }

  clearItemsForProject(projectKey: string): void {
    const map = this.itemsByProject();
    if (!map[projectKey]) return;
    const { [projectKey]: _, ...rest } = map;
    this.itemsByProject.set(rest);
    this.dashboardUtils.updateProject(projectKey, {
      counts: { total: 0, high: 0, medium: 0, low: 0 }
    });
  }
}
