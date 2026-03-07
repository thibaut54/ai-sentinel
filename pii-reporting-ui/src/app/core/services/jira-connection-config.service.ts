import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, Subject, tap } from 'rxjs';
import {
    JiraConnectionConfig,
    TestJiraConnectionRequest,
    TestJiraConnectionResponse,
    UpdateJiraConnectionConfigRequest
} from '../models/jira-connection-config.model';

@Injectable({providedIn: 'root'})
export class JiraConnectionConfigService {
  private readonly apiUrl = '/api/v1/jira/connection-config';
  private readonly _configSaved$ = new Subject<void>();
  readonly configSaved$ = this._configSaved$.asObservable();

  constructor(private readonly http: HttpClient) {
  }

  getConfig(): Observable<JiraConnectionConfig> {
    return this.http.get<JiraConnectionConfig>(this.apiUrl);
  }

  updateConfig(request: UpdateJiraConnectionConfigRequest): Observable<JiraConnectionConfig> {
    return this.http.put<JiraConnectionConfig>(this.apiUrl, request).pipe(
      tap(() => this._configSaved$.next())
    );
  }

  testConnection(request: TestJiraConnectionRequest): Observable<TestJiraConnectionResponse> {
    return this.http.post<TestJiraConnectionResponse>(`${this.apiUrl}/test`, request);
  }
}
