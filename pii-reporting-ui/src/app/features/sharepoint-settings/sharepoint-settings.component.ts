import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
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

@Component({
  selector: 'app-sharepoint-settings',
  templateUrl: './sharepoint-settings.component.html',
  styleUrl: './sharepoint-settings.component.css',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
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
  configForm!: FormGroup;
  loading = signal(false);
  saving = signal(false);
  testing = signal(false);
  currentConfig = signal<SharePointConnectionConfig | null>(null);

  constructor(
    private readonly fb: FormBuilder,
    private readonly configService: SharePointConnectionConfigService,
    private readonly messageService: MessageService,
    private readonly translocoService: TranslocoService
  ) {
    this.initForm();
  }

  ngOnInit(): void {
    this.loadConfig();
  }

  private initForm(): void {
    this.configForm = this.fb.group({
      tenantId: ['', [Validators.required, Validators.pattern(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i)]],
      clientId: ['', [Validators.required, Validators.pattern(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i)]],
      clientSecret: [''],
      enabled: [true]
    });
  }

  private loadConfig(): void {
    this.loading.set(true);

    this.configService.getConfig().subscribe({
      next: (config) => {
        this.currentConfig.set(config);
        this.configForm.patchValue({
          tenantId: config.tenantId,
          clientId: config.clientId,
          clientSecret: '',
          enabled: config.enabled
        });
        this.loading.set(false);
      },
      error: (err) => {
        this.messageService.add({
          severity: 'error',
          summary: this.translocoService.translate('common.error'),
          detail: this.translocoService.translate('settings.sharepoint.messages.loadError', { error: err.message || 'Unknown error' }),
          life: 5000
        });
        this.loading.set(false);
      }
    });
  }

  onSave(): void {
    if (this.configForm.invalid) {
      this.configForm.markAllAsTouched();
      return;
    }

    const formValue = this.configForm.value;
    if (!this.currentConfig() && !formValue.clientSecret) {
      this.configForm.get('clientSecret')?.setErrors({ required: true });
      this.configForm.get('clientSecret')?.markAsTouched();
      return;
    }

    this.saving.set(true);

    const request: UpdateSharePointConnectionConfigRequest = {
      tenantId: formValue.tenantId,
      clientId: formValue.clientId,
      clientSecret: formValue.clientSecret,
      enabled: formValue.enabled
    };

    this.configService.updateConfig(request).subscribe({
      next: (config) => {
        this.currentConfig.set(config);
        this.configForm.get('clientSecret')?.setValue('');
        this.configForm.markAsPristine();
        this.messageService.add({
          severity: 'success',
          summary: this.translocoService.translate('common.success'),
          detail: this.translocoService.translate('settings.sharepoint.messages.saveSuccess'),
          life: 3000
        });
        this.saving.set(false);
      },
      error: (err) => {
        this.messageService.add({
          severity: 'error',
          summary: this.translocoService.translate('common.error'),
          detail: this.translocoService.translate('settings.sharepoint.messages.saveError', { error: err.message || 'Unknown error' }),
          life: 5000
        });
        this.saving.set(false);
      }
    });
  }

  onTestConnection(): void {
    const tenantId = this.configForm.get('tenantId');
    const clientId = this.configForm.get('clientId');
    const clientSecret = this.configForm.get('clientSecret');

    if (tenantId?.invalid || clientId?.invalid) {
      tenantId?.markAsTouched();
      clientId?.markAsTouched();
      return;
    }

    if (!clientSecret?.value && !this.currentConfig()) {
      clientSecret?.setErrors({ required: true });
      clientSecret?.markAsTouched();
      return;
    }

    this.testing.set(true);

    const request: TestSharePointConnectionRequest = {
      tenantId: tenantId?.value,
      clientId: clientId?.value,
      clientSecret: clientSecret?.value
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
      error: (err) => {
        this.messageService.add({
          severity: 'error',
          summary: this.translocoService.translate('common.error'),
          detail: this.translocoService.translate('settings.sharepoint.messages.testError', { error: err.message || 'Unknown error' }),
          life: 5000
        });
        this.testing.set(false);
      }
    });
  }

  onReset(): void {
    if (this.currentConfig()) {
      const config = this.currentConfig()!;
      this.configForm.patchValue({
        tenantId: config.tenantId,
        clientId: config.clientId,
        clientSecret: '',
        enabled: config.enabled
      });
      this.configForm.markAsPristine();
    } else {
      this.configForm.reset();
      this.initForm();
    }
  }

  get hasUnsavedChanges(): boolean {
    return this.configForm.dirty;
  }
}
