import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, Subject, tap } from 'rxjs';
import {
    SharePointConnectionConfig,
    TestSharePointConnectionRequest,
    TestSharePointConnectionResponse,
    UpdateSharePointConnectionConfigRequest
} from '../models/sharepoint-connection-config.model';

@Injectable({providedIn: 'root'})
export class SharePointConnectionConfigService {
  private readonly apiUrl = '/api/v1/sharepoint/connection-config';
  private readonly _configSaved$ = new Subject<void>();
  readonly configSaved$ = this._configSaved$.asObservable();

  constructor(private readonly http: HttpClient) {
  }

  getConfig(): Observable<SharePointConnectionConfig> {
    return this.http.get<SharePointConnectionConfig>(this.apiUrl);
  }

  updateConfig(request: UpdateSharePointConnectionConfigRequest): Observable<SharePointConnectionConfig> {
    return this.http.put<SharePointConnectionConfig>(this.apiUrl, request).pipe(
      tap(() => this._configSaved$.next())
    );
  }

  testConnection(request: TestSharePointConnectionRequest): Observable<TestSharePointConnectionResponse> {
    return this.http.post<TestSharePointConnectionResponse>(`${this.apiUrl}/test`, request);
  }
}
