export type JiraDeploymentType = 'CLOUD' | 'DATA_CENTER';

export interface JiraConnectionConfig {
  baseUrl: string;
  email: string;
  apiTokenMasked?: string;
  connectTimeout: number;
  readTimeout: number;
  maxRetries: number;
  issuesLimit: number;
  maxIssues: number;
  deploymentType: JiraDeploymentType;
  updatedAt?: string;
  updatedBy?: string;
  configured: boolean;
}

export interface UpdateJiraConnectionConfigRequest {
  baseUrl: string;
  email: string;
  apiToken?: string;
  connectTimeout: number;
  readTimeout: number;
  maxRetries: number;
  issuesLimit: number;
  maxIssues: number;
  deploymentType: JiraDeploymentType;
}

export interface TestJiraConnectionRequest {
  baseUrl: string;
  email: string;
  apiToken?: string;
  deploymentType: JiraDeploymentType;
}

export interface TestJiraConnectionResponse {
  success: boolean;
  message?: string;
}
