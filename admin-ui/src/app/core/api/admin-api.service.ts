import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { catchError, map, Observable, throwError } from 'rxjs';

import { ApiError, toApiError } from './api-error';
import { AdminAccountDetail, AdminAccountSummary } from './admin-api.models';

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

  private request<T>(request: Observable<T>): Observable<T> {
    return request.pipe(catchError((error: unknown) => throwError(() => toApiError(error))));
  }
}
