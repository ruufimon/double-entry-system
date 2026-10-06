import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';

import { AccountOverview } from '../../core/api/banking-api.models';
import { AccountDashboard } from './account-dashboard';

describe('AccountDashboard', () => {
  const initialOverview: AccountOverview = {
    accountId: 'account-123',
    currency: 'THB',
    balance: 100,
    activities: [
      {
        transactionId: 'transaction-1',
        operation: 'deposit',
        effect: 'increase',
        amount: 100,
        currency: 'THB',
        balanceAfter: 100,
        occurredAt: '2026-10-05T01:00:00Z',
        status: 'posted',
        originalTransactionId: null,
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
          useValue: {
            snapshot: {
              paramMap: convertToParamMap({ accountId: 'account-123' }),
            },
          },
        },
      ],
    });
  });

  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('shows a layout-matched skeleton until the overview loads', () => {
    const fixture = TestBed.createComponent(AccountDashboard);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.account-skeleton')).not.toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Loading account details…');

    TestBed.inject(HttpTestingController)
      .expectOne('/api/accounts/account-123/overview')
      .flush(initialOverview);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.account-skeleton')).toBeNull();
    expect(fixture.nativeElement.querySelector('.balance-card strong').textContent).toContain(
      '฿100.00',
    );
  });

  it('keeps current account data visible while refreshing', () => {
    const fixture = TestBed.createComponent(AccountDashboard);
    const http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    http.expectOne('/api/accounts/account-123/overview').flush(initialOverview);
    fixture.detectChanges();

    fixture.componentInstance.loadAccount();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.balance-card strong').textContent).toContain(
      '฿100.00',
    );
    expect(fixture.nativeElement.querySelector('.dashboard-heading button').textContent).toContain(
      'Refreshing…',
    );

    http.expectOne('/api/accounts/account-123/overview').flush({
      ...initialOverview,
      balance: 125,
      activities: [
        ...initialOverview.activities,
        {
          ...initialOverview.activities[0],
          transactionId: 'transaction-2',
          amount: 25,
          balanceAfter: 125,
        },
      ],
    });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.balance-card strong').textContent).toContain(
      '฿125.00',
    );
  });

  it('shows the newest activity first', () => {
    const fixture = TestBed.createComponent(AccountDashboard);
    fixture.detectChanges();

    TestBed.inject(HttpTestingController)
      .expectOne('/api/accounts/account-123/overview')
      .flush({
        ...initialOverview,
        balance: 125,
        activities: [
          ...initialOverview.activities,
          {
            ...initialOverview.activities[0],
            transactionId: 'transaction-2',
            amount: 25,
            balanceAfter: 125,
          },
        ],
      });
    fixture.detectChanges();

    const activityRows = fixture.nativeElement.querySelectorAll('.activity-row');
    expect(activityRows[0].textContent).toContain('+฿25.00');
    expect(activityRows[1].textContent).toContain('+฿100.00');
  });

  it('renders an empty activity list when an older API omits activities', () => {
    const fixture = TestBed.createComponent(AccountDashboard);
    fixture.detectChanges();

    TestBed.inject(HttpTestingController)
      .expectOne('/api/accounts/account-123/overview')
      .flush({
        accountId: 'account-123',
        currency: 'THB',
        balance: 100,
      });
    fixture.detectChanges();

    expect(fixture.componentInstance.loading()).toBe(false);
    expect(fixture.nativeElement.textContent).toContain('No activity has been recorded yet.');
  });

  it('preserves current data and shows a warning when refresh fails', () => {
    const fixture = TestBed.createComponent(AccountDashboard);
    const http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    http.expectOne('/api/accounts/account-123/overview').flush(initialOverview);
    fixture.detectChanges();

    fixture.componentInstance.loadAccount();
    http.expectOne('/api/accounts/account-123/overview').flush(
      { error: 'request_failed', message: 'Temporary failure' },
      { status: 503, statusText: 'Service Unavailable' },
    );
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.balance-card strong').textContent).toContain(
      '฿100.00',
    );
    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain(
      'Temporary failure',
    );
  });
});
