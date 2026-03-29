import { Component, computed, inject, OnInit, output, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import {
  form, required, email, pattern, min, max, validate,
  FormField, FieldTree
} from '@angular/forms/signals';
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

interface JiraFormModel {
  deploymentType: JiraDeploymentType;
  baseUrl: string;
  email: string;
  apiToken: string;
  connectTimeout: number;
  readTimeout: number;
  maxRetries: number;
  issuesLimit: number;
  maxIssues: number;
}

const DEFAULT_MODEL: JiraFormModel = {
  deploymentType: 'CLOUD',
  baseUrl: '',
  email: '',
  apiToken: '',
  connectTimeout: 30000,
  readTimeout: 60000,
  maxRetries: 3,
  issuesLimit: 25,
  maxIssues: 1000
};

@Component({
  selector: 'app-jira-settings',
  templateUrl: './jira-settings.component.html',
  styleUrl: './jira-settings.component.css',
  standalone: true,
  imports: [
    CommonModule,
    FormField,
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
  readonly saved = output();

  private readonly configService = inject(JiraConnectionConfigService);
  private readonly messageService = inject(MessageService);
  private readonly translocoService = inject(TranslocoService);

  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly testing = signal(false);
  readonly currentConfig = signal<JiraConnectionConfig | null>(null);

  readonly model = signal<JiraFormModel>({...DEFAULT_MODEL});

  readonly configForm: FieldTree<JiraFormModel> = form(this.model, (path) => {
    // Deployment type
    required(path.deploymentType);

    // Base URL
    required(path.baseUrl);
    pattern(path.baseUrl, /^https?:\/\/.+/);

    // Email (always visible for Jira, unlike Confluence username)
    required(path.email);
    email(path.email);

    // API token: required unless an existing token is stored for the current deployment type
    validate(path.apiToken, () => {
      const value = this.model().apiToken;
      if (value) return null;
      const config = this.currentConfig();
      const formType = this.model().deploymentType;
      if (config?.configured && config.deploymentType === formType) return null;
      return {kind: 'required'};
    });

    // Timeouts
    required(path.connectTimeout);
    min(path.connectTimeout, 1000);
    max(path.connectTimeout, 120000);

    required(path.readTimeout);
    min(path.readTimeout, 5000);
    max(path.readTimeout, 300000);

    required(path.maxRetries);
    min(path.maxRetries, 0);
    max(path.maxRetries, 10);

    // Pagination
    required(path.issuesLimit);
    min(path.issuesLimit, 1);
    max(path.issuesLimit, 1000);

    required(path.maxIssues);
    min(path.maxIssues, 1);
    max(path.maxIssues, 10000);
  });

  readonly isCloud = computed(() => this.model().deploymentType === 'CLOUD');

  readonly hasExistingTokenForCurrentType = computed(() => {
    const config = this.currentConfig();
    return !!config && config.configured && config.deploymentType === this.model().deploymentType;
  });

  readonly apiTokenPlaceholder = computed(() => {
    if (this.hasExistingTokenForCurrentType()) {
      return this.translocoService.translate('settings.jira.placeholders.apiTokenExisting');
    }
    return this.translocoService.translate('settings.jira.placeholders.apiToken');
  });

  ngOnInit(): void {
    this.loadConfig();
  }

  private loadConfig(): void {
    this.loading.set(true);

    this.configService.getConfig().subscribe({
      next: (config) => {
        this.currentConfig.set(config);
        this.model.set({
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
    const request: UpdateJiraConnectionConfigRequest = {
      baseUrl: value.baseUrl,
      email: value.email,
      apiToken: value.apiToken,
      connectTimeout: value.connectTimeout,
      readTimeout: value.readTimeout,
      maxRetries: value.maxRetries,
      issuesLimit: value.issuesLimit,
      maxIssues: value.maxIssues,
      deploymentType: value.deploymentType
    };

    this.configService.updateConfig(request).subscribe({
      next: (config) => {
        this.currentConfig.set(config);
        this.model.update(m => ({...m, apiToken: ''}));
        this.messageService.add({
          severity: 'success',
          summary: this.translocoService.translate('common.success'),
          detail: this.translocoService.translate('settings.jira.messages.saveSuccess'),
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
    const formState = this.configForm();
    const baseUrlState = this.configForm.baseUrl();
    const emailState = this.configForm.email();
    const apiTokenState = this.configForm.apiToken();

    if (baseUrlState.invalid() || emailState.invalid() || apiTokenState.invalid()) return;

    this.testing.set(true);

    const value = formState.value();
    const request: TestJiraConnectionRequest = {
      baseUrl: value.baseUrl,
      email: value.email,
      apiToken: value.apiToken,
      deploymentType: value.deploymentType
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
            detail: this.translocoService.translate('settings.jira.messages.testFailed', {message: response.message || ''}),
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
