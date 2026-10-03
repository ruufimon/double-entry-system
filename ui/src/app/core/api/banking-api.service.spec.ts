import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { ApiError } from './api-error';
import { BankingApiService } from './banking-api.service';

describe('BankingApiService', () => {
  let service: BankingApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(BankingApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('opens an account idempotently', () => {
    let status = '';
    service.openAccount('account-123').subscribe((response) => (status = response.status));

    const request = http.expectOne('/api/accounts/account-123');
    expect(request.request.method).toBe('PUT');
    request.flush({ accountId: 'account-123', currency: 'THB', balance: 0, status: 'active' });

    expect(status).toBe('active');
  });

  it('loads an encoded account balance', () => {
    let balance = 0;
    service.getBalance('account/123').subscribe((response) => (balance = response.balance));

    http.expectOne('/api/accounts/account%2F123/balance').flush({
      accountId: 'account/123',
      currency: 'THB',
      balance: 125.5,
    });

    expect(balance).toBe(125.5);
  });

  it('sends a numeric deposit amount', () => {
    service.deposit('account-123', 10.25).subscribe();

    const request = http.expectOne('/api/accounts/account-123/deposits');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ amount: 10.25 });
    request.flush({ accountId: 'account-123', balance: 10.25 });
  });

  it('translates domain errors', () => {
    let received: unknown;
    service.withdraw('account-123', 20).subscribe({ error: (error: unknown) => (received = error) });

    http.expectOne('/api/accounts/account-123/withdrawals').flush(
      { error: 'insufficient_funds', message: 'Not enough funds' },
      { status: 409, statusText: 'Conflict' },
    );

    expect(received).toBeInstanceOf(ApiError);
    expect((received as ApiError).code).toBe('insufficient_funds');
  });
});
