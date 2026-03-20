import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import { ButtonModule } from 'primeng/button';
import { FieldsetModule } from 'primeng/fieldset';
import { InputNumberModule } from 'primeng/inputnumber';
import { InputTextModule } from 'primeng/inputtext';
import { PasswordModule } from 'primeng/password';
import { ProgressSpinnerModule } from 'primeng/progressspinner';
import { RadioButtonModule } from 'primeng/radiobutton';
import { ToastModule } from 'primeng/toast';
import { MessageService } from 'primeng/api';
import { JiraConnectionConfigService } from '../../core/services/jira-connection-config.service';
import {
    JiraConnectionConfig,
    JiraDeploymentType,
    TestJiraConnectionRequest,
    UpdateJiraConnectionConfigRequest
} from '../../core/models/jira-connection-config.model';

@Component({
  selector: 'app-jira-settings',
  templateUrl: './jira-settings.component.html',
  styleUrl: './jira-settings.component.css',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    TranslocoModule,
    ButtonModule,
    FieldsetModule,
    InputNumberModule,
    InputTextModule,
    PasswordModule,
    ProgressSpinnerModule,
    RadioButtonModule,
    ToastModule
  ],
  providers: [MessageService]
})
export class JiraSettingsComponent implements OnInit {
  configForm!: FormGroup;
  loading = signal(false);
  saving = signal(false);
  testing = signal(false);
  currentConfig = signal<JiraConnectionConfig | null>(null);

  constructor(
    private readonly fb: FormBuilder,
    private readonly configService: JiraConnectionConfigService,
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
      deploymentType: ['CLOUD' as JiraDeploymentType, [Validators.required]],
      baseUrl: ['', [Validators.required, Validators.pattern(/^https?:\/\/.+/)]],
      email: ['', [Validators.required, Validators.email]],
      apiToken: [''],
      connectTimeout: [30000, [Validators.required, Validators.min(1000), Validators.max(120000)]],
      readTimeout: [60000, [Validators.required, Validators.min(5000), Validators.max(300000)]],
      maxRetries: [3, [Validators.required, Validators.min(0), Validators.max(10)]],
      issuesLimit: [25, [Validators.required, Validators.min(1), Validators.max(1000)]],
      maxIssues: [1000, [Validators.required, Validators.min(1), Validators.max(10000)]]
    });
  }

  private loadConfig(): void {
    this.loading.set(true);

    this.configService.getConfig().subscribe({
      next: (config) => {
        this.currentConfig.set(config);
        this.configForm.patchValue({
          deploymentType: config.deploymentType || 'CLOUD',
          baseUrl: config.baseUrl,
          email: config.email,
          apiToken: '',
          connectTimeout: config.connectTimeout,
          readTimeout: config.readTimeout,
          maxRetries: config.maxRetries,
          issuesLimit: config.issuesLimit,
          maxIssues: config.maxIssues
        });
        this.loading.set(false);
      },
      error: (err) => {
        this.messageService.add({
          severity: 'error',
          summary: this.translocoService.translate('common.error'),
          detail: this.translocoService.translate('settings.jira.messages.loadError', { error: err.message || 'Unknown error' }),
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
    if (!this.currentConfig() && !formValue.apiToken) {
      this.configForm.get('apiToken')?.setErrors({ required: true });
      this.configForm.get('apiToken')?.markAsTouched();
      return;
    }

    this.saving.set(true);

    const request: UpdateJiraConnectionConfigRequest = {
      baseUrl: formValue.baseUrl,
      email: formValue.email,
      apiToken: formValue.apiToken,
      connectTimeout: formValue.connectTimeout,
      readTimeout: formValue.readTimeout,
      maxRetries: formValue.maxRetries,
      issuesLimit: formValue.issuesLimit,
      maxIssues: formValue.maxIssues,
      deploymentType: formValue.deploymentType
    };

    this.configService.updateConfig(request).subscribe({
      next: (config) => {
        this.currentConfig.set(config);
        this.configForm.get('apiToken')?.setValue('');
        this.configForm.markAsPristine();
        this.messageService.add({
          severity: 'success',
          summary: this.translocoService.translate('common.success'),
          detail: this.translocoService.translate('settings.jira.messages.saveSuccess'),
          life: 3000
        });
        this.saving.set(false);
      },
      error: (err) => {
        this.messageService.add({
          severity: 'error',
          summary: this.translocoService.translate('common.error'),
          detail: this.translocoService.translate('settings.jira.messages.saveError', { error: err.message || 'Unknown error' }),
          life: 5000
        });
        this.saving.set(false);
      }
    });
  }

  onTestConnection(): void {
    const baseUrl = this.configForm.get('baseUrl');
    const email = this.configForm.get('email');
    const apiToken = this.configForm.get('apiToken');

    if (baseUrl?.invalid || email?.invalid) {
      baseUrl?.markAsTouched();
      email?.markAsTouched();
      return;
    }

    if (!apiToken?.value && !this.currentConfig()) {
      apiToken?.setErrors({ required: true });
      apiToken?.markAsTouched();
      return;
    }

    this.testing.set(true);

    const request: TestJiraConnectionRequest = {
      baseUrl: baseUrl?.value,
      email: email?.value,
      apiToken: apiToken?.value,
      deploymentType: this.configForm.get('deploymentType')?.value || 'CLOUD'
    };

    this.configService.testConnection(request).subscribe({
      next: (response) => {
        if (response.success) {
          this.messageService.add({
            severity: 'success',
            summary: this.translocoService.translate('common.success'),
            detail: this.translocoService.translate('settings.jira.messages.testSuccess'),
            life: 3000
          });
        } else {
          this.messageService.add({
            severity: 'warn',
            summary: this.translocoService.translate('common.warning'),
            detail: this.translocoService.translate('settings.jira.messages.testFailed', { message: response.message || '' }),
            life: 5000
          });
        }
        this.testing.set(false);
      },
      error: (err) => {
        this.messageService.add({
          severity: 'error',
          summary: this.translocoService.translate('common.error'),
          detail: this.translocoService.translate('settings.jira.messages.testError', { error: err.message || 'Unknown error' }),
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
        deploymentType: config.deploymentType || 'CLOUD',
        baseUrl: config.baseUrl,
        email: config.email,
        apiToken: '',
        connectTimeout: config.connectTimeout,
        readTimeout: config.readTimeout,
        maxRetries: config.maxRetries,
        issuesLimit: config.issuesLimit,
        maxIssues: config.maxIssues
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
