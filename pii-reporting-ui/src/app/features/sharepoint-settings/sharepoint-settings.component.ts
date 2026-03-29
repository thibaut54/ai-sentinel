import { Component, computed, inject, OnInit, output, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { form, required, pattern, validate, FormField, FieldTree } from '@angular/forms/signals';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import { ButtonModule } from 'primeng/button';
import { FieldsetModule } from 'primeng/fieldset';
import { InputTextModule } from 'primeng/inputtext';
import { PasswordModule } from 'primeng/password';
import { ProgressSpinnerModule } from 'primeng/progressspinner';
import { ToastModule } from 'primeng/toast';
import { MessageService } from 'primeng/api';
import { SharePointConnectionConfigService } from '../../core/services/sharepoint-connection-config.service';
import {
  SharePointConnectionConfig,
  TestSharePointConnectionRequest,
  UpdateSharePointConnectionConfigRequest
} from '../../core/models/sharepoint-connection-config.model';

interface SharePointFormModel {
  tenantId: string;
  clientId: string;
  clientSecret: string;
  enabled: boolean;
}

const DEFAULT_MODEL: SharePointFormModel = {
  tenantId: '',
  clientId: '',
  clientSecret: '',
  enabled: true
};

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

@Component({
  selector: 'app-sharepoint-settings',
  templateUrl: './sharepoint-settings.component.html',
  styleUrl: './sharepoint-settings.component.css',
  standalone: true,
  imports: [
    CommonModule,
    FormField,
    TranslocoModule,
    ButtonModule,
    FieldsetModule,
    InputTextModule,
    PasswordModule,
    ProgressSpinnerModule,
    ToastModule
  ],
  providers: [MessageService]
})
export class SharePointSettingsComponent implements OnInit {
  readonly saved = output();

  private readonly configService = inject(SharePointConnectionConfigService);
  private readonly messageService = inject(MessageService);
  private readonly translocoService = inject(TranslocoService);

  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly testing = signal(false);
  readonly currentConfig = signal<SharePointConnectionConfig | null>(null);

  readonly model = signal<SharePointFormModel>({...DEFAULT_MODEL});

  readonly configForm: FieldTree<SharePointFormModel> = form(this.model, (path) => {
    required(path.tenantId);
    pattern(path.tenantId, UUID_PATTERN);

    required(path.clientId);
    pattern(path.clientId, UUID_PATTERN);

    validate(path.clientSecret, () => {
      const value = this.model().clientSecret;
      if (value) return null;
      if (this.currentConfig()?.configured) return null;
      return {kind: 'required'};
    });
  });

  readonly hasExistingSecret = computed(() => {
    const config = this.currentConfig();
    return !!config && config.configured;
  });

  readonly clientSecretPlaceholder = computed(() =>
    this.hasExistingSecret()
      ? this.translocoService.translate('settings.sharepoint.placeholders.clientSecretExisting')
      : this.translocoService.translate('settings.sharepoint.placeholders.clientSecret')
  );

  ngOnInit(): void {
    this.loadConfig();
  }

  private loadConfig(): void {
    this.loading.set(true);

    this.configService.getConfig().subscribe({
      next: (config) => {
        this.currentConfig.set(config);
        this.model.set({
          tenantId: config.tenantId,
          clientId: config.clientId,
          clientSecret: '',
          enabled: config.enabled
        });
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
      }
    });
  }

  onSave(): void {
    const formState = this.configForm();
    if (formState.invalid()) return;

    this.saving.set(true);

    const value = formState.value();
    const request: UpdateSharePointConnectionConfigRequest = {
      tenantId: value.tenantId,
      clientId: value.clientId,
      clientSecret: value.clientSecret,
      enabled: value.enabled
    };

    this.configService.updateConfig(request).subscribe({
      next: (config) => {
        this.currentConfig.set(config);
        this.model.update(m => ({...m, clientSecret: ''}));
        this.messageService.add({
          severity: 'success',
          summary: this.translocoService.translate('common.success'),
          detail: this.translocoService.translate('settings.sharepoint.messages.saveSuccess'),
          life: 3000
        });
        this.saving.set(false);
        this.saved.emit();
      },
      error: () => {
        this.saving.set(false);
      }
    });
  }

  onTestConnection(): void {
    const tenantIdState = this.configForm.tenantId();
    const clientIdState = this.configForm.clientId();
    const clientSecretState = this.configForm.clientSecret();

    if (tenantIdState.invalid() || clientIdState.invalid() || clientSecretState.invalid()) return;

    this.testing.set(true);

    const formState = this.configForm();
    const value = formState.value();
    const request: TestSharePointConnectionRequest = {
      tenantId: value.tenantId,
      clientId: value.clientId,
      clientSecret: value.clientSecret
    };

    this.configService.testConnection(request).subscribe({
      next: (response) => {
        if (response.success) {
          this.messageService.add({
            severity: 'success',
            summary: this.translocoService.translate('common.success'),
            detail: this.translocoService.translate('settings.sharepoint.messages.testSuccess'),
            life: 3000
          });
        } else {
          this.messageService.add({
            severity: 'warn',
            summary: this.translocoService.translate('common.warning'),
            detail: this.translocoService.translate('settings.sharepoint.messages.testFailed', { message: response.message || '' }),
            life: 5000
          });
        }
        this.testing.set(false);
      },
      error: () => {
        this.testing.set(false);
      }
    });
  }

  onReset(): void {
    const config = this.currentConfig();
    if (config) {
      this.model.set({
        tenantId: config.tenantId,
        clientId: config.clientId,
        clientSecret: '',
        enabled: config.enabled
      });
    } else {
      this.model.set({...DEFAULT_MODEL});
    }
  }

  get hasUnsavedChanges(): boolean {
    return this.configForm().dirty();
  }

  get isFormValid(): boolean {
    return this.configForm().valid();
  }
}
