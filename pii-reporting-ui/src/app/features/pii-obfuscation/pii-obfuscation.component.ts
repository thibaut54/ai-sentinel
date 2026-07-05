import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, ParamMap } from '@angular/router';
import { TranslocoModule } from '@jsverse/transloco';
import { RemediationScope } from '../../core/models/remediation.model';
import { RemediationConfigService } from '../../core/services/remediation-config.service';
import { ObfuscationSelectionService } from './services/obfuscation-selection.service';
import { ObfuscationViewStateService } from './services/obfuscation-view-state.service';
import { TestIds } from '../test-ids.constants';

/**
 * Container for the PII obfuscation view. Resolves the remediation scope
 * from query params (spaceKey, pageId?, attachmentName?, preselect) and
 * hosts the feature-scoped selection and view-state services.
 */
@Component({
  selector: 'app-pii-obfuscation',
  standalone: true,
  imports: [TranslocoModule],
  providers: [ObfuscationSelectionService, ObfuscationViewStateService],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './pii-obfuscation.component.html',
  styleUrl: './pii-obfuscation.component.css',
})
export class PiiObfuscationComponent {
  readonly remediationConfig = inject(RemediationConfigService);
  readonly selection = inject(ObfuscationSelectionService);
  readonly viewState = inject(ObfuscationViewStateService);
  private readonly route = inject(ActivatedRoute);

  readonly testIds = TestIds.obfuscation;
  readonly preselectRequested = signal(false);

  constructor() {
    this.route.queryParamMap.pipe(takeUntilDestroyed()).subscribe((params) => {
      this.selection.setScope(toScope(params));
      this.preselectRequested.set(params.get('preselect') === 'true');
    });
  }
}

function toScope(params: ParamMap): RemediationScope {
  const scope: RemediationScope = { spaceKey: params.get('spaceKey') ?? '' };
  const pageId = params.get('pageId');
  if (pageId) {
    scope.pageId = pageId;
  }
  const attachmentName = params.get('attachmentName');
  if (attachmentName) {
    scope.attachmentName = attachmentName;
  }
  return scope;
}
