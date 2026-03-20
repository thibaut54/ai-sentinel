export interface SharePointConnectionConfig {
  tenantId: string;
  clientId: string;
  clientSecretMasked?: string;
  enabled: boolean;
  updatedAt?: string;
  updatedBy?: string;
  configured: boolean;
}

export interface UpdateSharePointConnectionConfigRequest {
  tenantId: string;
  clientId: string;
  clientSecret?: string;
  enabled: boolean;
}

export interface TestSharePointConnectionRequest {
  tenantId: string;
  clientId: string;
  clientSecret?: string;
}

export interface TestSharePointConnectionResponse {
  success: boolean;
  message?: string;
}
