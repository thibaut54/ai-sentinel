import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import {
  JiraConnectionConfig,
  UpdateJiraConnectionConfigRequest,
  TestJiraConnectionRequest,
  TestJiraConnectionResponse
} from '../models/jira-connection-config.model';

@Injectable({providedIn: 'root'})
export class JiraConnectionConfigService {
  private readonly apiUrl = '/api/v1/jira/connection-config';

  constructor(private readonly http: HttpClient) {
  }

  getConfig(): Observable<JiraConnectionConfig> {
    return this.http.get<JiraConnectionConfig>(this.apiUrl);
  }

  updateConfig(request: UpdateJiraConnectionConfigRequest): Observable<JiraConnectionConfig> {
    return this.http.put<JiraConnectionConfig>(this.apiUrl, request);
  }

  testConnection(request: TestJiraConnectionRequest): Observable<TestJiraConnectionResponse> {
    return this.http.post<TestJiraConnectionResponse>(`${this.apiUrl}/test`, request);
  }
}
