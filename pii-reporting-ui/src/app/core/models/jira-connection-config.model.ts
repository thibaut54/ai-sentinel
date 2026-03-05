export interface JiraConnectionConfig {
  baseUrl: string;
  email: string;
  apiTokenMasked?: string;
  connectTimeout: number;
  readTimeout: number;
  maxRetries: number;
  issuesLimit: number;
  maxIssues: number;
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
}

export interface TestJiraConnectionRequest {
  baseUrl: string;
  email: string;
  apiToken?: string;
}

export interface TestJiraConnectionResponse {
  success: boolean;
  message?: string;
}
