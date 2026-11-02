import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { ApiError } from './api-error';
import { AdminApiService } from './admin-api.service';

describe('AdminApiService', () => {
  let service: AdminApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AdminApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('loads all account summaries', () => {
    let count = 0;
    service.getAccounts().subscribe((accounts) => (count = accounts.length));

    const request = http.expectOne('/api/admin/accounts');
    expect(request.request.method).toBe('GET');
    request.flush([
      {
        accountId: 'account-123',
        currency: 'THB',
        status: 'active',
        balance: 100,
        activityCount: 1,
        lastActivityAt: '2026-10-07T04:00:00Z',
      },
    ]);

    expect(count).toBe(1);
  });

  it('loads an encoded account detail', () => {
    service.getAccount('account/123').subscribe();

    const request = http.expectOne('/api/admin/accounts/account%2F123');
    expect(request.request.method).toBe('GET');
    request.flush({
      accountId: 'account/123',
      currency: 'THB',
      status: 'active',
      balance: 0,
      activityCount: 0,
      lastActivityAt: null,
      activities: [],
    });
  });

  it('loads the interest rate with in-memory admin credentials', () => {
    service.getInterestRate('test-password').subscribe();

    const request = http.expectOne('/api/admin/interest-rate');
    expect(request.request.method).toBe('GET');
    expect(request.request.headers.get('Authorization')).toBe(`Basic ${btoa('admin:test-password')}`);
    request.flush({
      currentRate: 0,
      currentEffectiveFrom: '0001-01-01',
      nextEffectiveFrom: '2026-11-01',
      scheduledRate: null,
      scheduledEffectiveFrom: null,
      history: [],
    });
  });

  it('saves an annual rate with admin credentials', () => {
    service.setInterestRate('test-password', 3.65).subscribe();

    const request = http.expectOne('/api/admin/interest-rate');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({ rate: 3.65 });
    expect(request.request.headers.get('Authorization')).toBe(`Basic ${btoa('admin:test-password')}`);
    request.flush({
      currentRate: 0,
      currentEffectiveFrom: '0001-01-01',
      nextEffectiveFrom: '2026-11-01',
      scheduledRate: 3.65,
      scheduledEffectiveFrom: '2026-11-01',
      history: [],
    });
  });

  it('rejects a malformed account-list response instead of exposing undefined data', () => {
    let received: unknown;
    service.getAccounts().subscribe({ error: (error: unknown) => (received = error) });

    http.expectOne('/api/admin/accounts').flush({ message: 'pong' });

    expect(received).toBeInstanceOf(ApiError);
    expect((received as ApiError).code).toBe('invalid_admin_response');
  });

  it('translates admin API errors', () => {
    let received: unknown;
    service.getAccount('missing').subscribe({ error: (error: unknown) => (received = error) });

    http.expectOne('/api/admin/accounts/missing').flush(
      { error: 'account_not_found', message: "Account 'missing' was not found" },
      { status: 404, statusText: 'Not Found' },
    );

    expect(received).toBeInstanceOf(ApiError);
    expect((received as ApiError).code).toBe('account_not_found');
  });
});
