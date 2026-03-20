import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, Subject, tap } from 'rxjs';
import {
    ConfluenceConnectionConfig,
    TestConnectionRequest,
    TestConnectionResponse,
    UpdateConfluenceConnectionConfigRequest
} from '../models/confluence-connection-config.model';

@Injectable({providedIn: 'root'})
export class ConfluenceConnectionConfigService {
  private readonly apiUrl = '/api/v1/confluence/connection-config';
  private readonly _configSaved$ = new Subject<void>();
  readonly configSaved$ = this._configSaved$.asObservable();

  constructor(private readonly http: HttpClient) {
  }

  getConfig(): Observable<ConfluenceConnectionConfig> {
    return this.http.get<ConfluenceConnectionConfig>(this.apiUrl);
  }

  updateConfig(request: UpdateConfluenceConnectionConfigRequest): Observable<ConfluenceConnectionConfig> {
    return this.http.put<ConfluenceConnectionConfig>(this.apiUrl, request).pipe(
      tap(() => this._configSaved$.next())
    );
  }

  testConnection(request: TestConnectionRequest): Observable<TestConnectionResponse> {
    return this.http.post<TestConnectionResponse>(`${this.apiUrl}/test`, request);
  }
}
