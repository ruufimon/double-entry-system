import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { catchError, Observable, throwError } from 'rxjs';

import { toApiError } from './api-error';
import {
  AccountActivity,
  AccountBalance,
  AccountResponse,
  BalanceChangeResponse,
  BillPaymentAccepted,
  BillPaymentInquiry,
  BillPaymentInquiryRequest,
  BillPaymentProcess,
} from './banking-api.models';

@Injectable({ providedIn: 'root' })
export class BankingApiService {
  private readonly http = inject(HttpClient);

  openAccount(accountId: string): Observable<AccountResponse> {
    return this.request(
      this.http.put<AccountResponse>(this.accountUrl(accountId), {}),
    );
  }

  getBalance(accountId: string): Observable<AccountBalance> {
    return this.request(
      this.http.get<AccountBalance>(`${this.accountUrl(accountId)}/balance`),
    );
  }

  getActivities(accountId: string): Observable<readonly AccountActivity[]> {
    return this.request(
      this.http.get<readonly AccountActivity[]>(`${this.accountUrl(accountId)}/activities`),
    );
  }

  deposit(accountId: string, amount: number): Observable<BalanceChangeResponse> {
    return this.request(
      this.http.post<BalanceChangeResponse>(`${this.accountUrl(accountId)}/deposits`, { amount }),
    );
  }

  withdraw(accountId: string, amount: number): Observable<BalanceChangeResponse> {
    return this.request(
      this.http.post<BalanceChangeResponse>(`${this.accountUrl(accountId)}/withdrawals`, { amount }),
    );
  }

  inquireBill(
    accountId: string,
    inquiry: BillPaymentInquiryRequest,
  ): Observable<BillPaymentInquiry> {
    return this.request(
      this.http.post<BillPaymentInquiry>(
        `${this.accountUrl(accountId)}/bill-payments/inquiries`,
        inquiry,
      ),
    );
  }

  confirmBillPayment(
    accountId: string,
    inquiryId: string,
  ): Observable<BillPaymentAccepted> {
    return this.request(
      this.http.post<BillPaymentAccepted>(
        `${this.accountUrl(accountId)}/bill-payments/${encodeURIComponent(inquiryId)}/confirm`,
        {},
      ),
    );
  }

  getBillPayment(accountId: string, paymentId: string): Observable<BillPaymentProcess> {
    return this.request(
      this.http.get<BillPaymentProcess>(
        `${this.accountUrl(accountId)}/bill-payments/${encodeURIComponent(paymentId)}`,
      ),
    );
  }

  private accountUrl(accountId: string): string {
    return `/api/accounts/${encodeURIComponent(accountId)}`;
  }

  private request<T>(request: Observable<T>): Observable<T> {
    return request.pipe(catchError((error: unknown) => throwError(() => toApiError(error))));
  }
}
