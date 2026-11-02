import { HttpClient, HttpHeaders } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { catchError, map, Observable, throwError } from 'rxjs';

import { ApiError, toApiError } from './api-error';
import { AdminAccountDetail, AdminAccountSummary, AdminInterestRate } from './admin-api.models';

@Injectable({ providedIn: 'root' })
export class AdminApiService {
  private readonly http = inject(HttpClient);

  getAccounts(): Observable<readonly AdminAccountSummary[]> {
    return this.request(
      this.http.get<unknown>('/api/admin/accounts').pipe(
        map((response) => {
          if (!Array.isArray(response)) {
            throw new ApiError(
              'invalid_admin_response',
              'The admin API returned an invalid account list. Rebuild or restart the API.',
              200,
            );
          }
          return response as readonly AdminAccountSummary[];
        }),
      ),
    );
  }

  getAccount(accountId: string): Observable<AdminAccountDetail> {
    return this.request(
      this.http.get<AdminAccountDetail>(
        `/api/admin/accounts/${encodeURIComponent(accountId)}`,
      ),
    );
  }

  getInterestRate(password: string): Observable<AdminInterestRate> {
    return this.request(
      this.http.get<AdminInterestRate>('/api/admin/interest-rate', {
        headers: this.adminHeaders(password),
      }),
    );
  }

  setInterestRate(password: string, rate: number): Observable<AdminInterestRate> {
    return this.request(
      this.http.put<AdminInterestRate>(
        '/api/admin/interest-rate',
        { rate },
        { headers: this.adminHeaders(password) },
      ),
    );
  }

  private adminHeaders(password: string): HttpHeaders {
    const credentialBytes = new TextEncoder().encode(`admin:${password}`);
    const binary = Array.from(credentialBytes, (byte) => String.fromCharCode(byte)).join('');
    return new HttpHeaders({ Authorization: `Basic ${btoa(binary)}` });
  }

  private request<T>(request: Observable<T>): Observable<T> {
    return request.pipe(catchError((error: unknown) => throwError(() => toApiError(error))));
  }
}
