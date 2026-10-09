import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';

import { AdminAccountDetail } from '../../core/api/admin-api.models';
import { AccountDetail } from './account-detail';

describe('AccountDetail', () => {
  const detail: AdminAccountDetail = {
    accountId: 'account-123',
    currency: 'THB',
    status: 'active',
    balance: 75,
    activityCount: 2,
    lastActivityAt: '2026-10-07T05:00:00Z',
    activities: [
      {
        transactionId: 'transaction-1',
        operation: 'deposit',
        effect: 'increase',
        amount: 100,
        currency: 'THB',
        balanceAfter: 100,
        occurredAt: '2026-10-07T04:00:00Z',
        status: 'posted',
        originalTransactionId: null,
        counterpartyAccountId: null,
      },
      {
        transactionId: 'transaction-2',
        operation: 'transfer_out',
        effect: 'decrease',
        amount: 25,
        currency: 'THB',
        balanceAfter: 75,
        occurredAt: '2026-10-07T05:00:00Z',
        status: 'posted',
        originalTransactionId: null,
        counterpartyAccountId: 'recipient-456',
      },
    ],
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ accountId: 'account-123' }) } },
        },
      ],
    });
  });

  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('renders every operational activity field newest first', () => {
    const fixture = TestBed.createComponent(AccountDetail);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Loading account details…');

    TestBed.inject(HttpTestingController)
      .expectOne('/api/admin/accounts/account-123')
      .flush(detail);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('฿75.00');
    const rows = fixture.nativeElement.querySelectorAll('.activity-table tbody tr');
    expect(rows).toHaveLength(2);
    expect(rows[0].textContent).toContain('transaction-2');
    expect(rows[0].textContent).toContain('Transfer sent');
    expect(rows[0].textContent).toContain('recipient-456');
    expect(rows[0].textContent).toContain('−฿25.00');
    expect(rows[1].textContent).toContain('transaction-1');
  });

  it('shows the API error for a missing account', () => {
    const fixture = TestBed.createComponent(AccountDetail);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController).expectOne('/api/admin/accounts/account-123').flush(
      { error: 'account_not_found', message: 'Account was not found' },
      { status: 404, statusText: 'Not Found' },
    );
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain(
      'Account was not found',
    );
  });

  it('preserves details when refresh fails', () => {
    const fixture = TestBed.createComponent(AccountDetail);
    const http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    http.expectOne('/api/admin/accounts/account-123').flush(detail);
    fixture.detectChanges();

    fixture.componentInstance.loadAccount();
    http.expectOne('/api/admin/accounts/account-123').flush(
      { error: 'request_failed', message: 'Temporary failure' },
      { status: 503, statusText: 'Unavailable' },
    );
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('฿75.00');
    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain(
      'Temporary failure',
    );
  });
});
