import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';

import { AdminAccountSummary } from '../../core/api/admin-api.models';
import { AccountList } from './account-list';

describe('AccountList', () => {
  const accounts: readonly AdminAccountSummary[] = [
    {
      accountId: 'alpha-account',
      currency: 'THB',
      status: 'active',
      balance: 25,
      activityCount: 2,
      lastActivityAt: '2026-10-07T04:00:00Z',
    },
    {
      accountId: 'beta-account',
      currency: 'THB',
      status: 'active',
      balance: 75,
      activityCount: 0,
      lastActivityAt: null,
    },
  ];

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
  });

  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('shows loading state then renders account metrics and rows', () => {
    const fixture = TestBed.createComponent(AccountList);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Loading all accounts…');

    TestBed.inject(HttpTestingController).expectOne('/api/admin/accounts').flush(accounts);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Total balance');
    expect(fixture.nativeElement.textContent).toContain('฿100.00');
    expect(fixture.nativeElement.querySelectorAll('tbody tr')).toHaveLength(2);
  });

  it('filters account IDs case-insensitively', () => {
    const fixture = TestBed.createComponent(AccountList);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController).expectOne('/api/admin/accounts').flush(accounts);
    fixture.componentInstance.query.set('BETA');
    fixture.detectChanges();

    const rows = fixture.nativeElement.querySelectorAll('tbody tr');
    expect(rows).toHaveLength(1);
    expect(rows[0].textContent).toContain('beta-account');
  });

  it('opens a specific valid account ID directly', () => {
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    const fixture = TestBed.createComponent(AccountList);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController).expectOne('/api/admin/accounts').flush(accounts);

    fixture.componentInstance.specificAccountId.set('  alpha-account  ');
    fixture.componentInstance.viewSpecificAccount();

    expect(navigate).toHaveBeenCalledWith(['/accounts', 'alpha-account']);
  });

  it('keeps direct lookup disabled for an invalid account ID', () => {
    const fixture = TestBed.createComponent(AccountList);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController).expectOne('/api/admin/accounts').flush(accounts);
    fixture.componentInstance.specificAccountId.set('not valid!');
    fixture.detectChanges();

    const button = fixture.nativeElement.querySelector('.account-lookup button');
    expect(button.disabled).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('Use 1–64 letters');
  });

  it('renders an empty account directory', () => {
    const fixture = TestBed.createComponent(AccountList);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController).expectOne('/api/admin/accounts').flush([]);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('No accounts yet');
  });

  it('shows a recoverable error for a malformed API response', () => {
    const fixture = TestBed.createComponent(AccountList);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/admin/accounts')
      .flush({ message: 'pong' });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain(
      'Rebuild or restart the API',
    );
  });

  it('keeps current data visible when refresh fails', () => {
    const fixture = TestBed.createComponent(AccountList);
    const http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    http.expectOne('/api/admin/accounts').flush(accounts);
    fixture.detectChanges();

    fixture.componentInstance.loadAccounts();
    http.expectOne('/api/admin/accounts').flush(
      { error: 'request_failed', message: 'Temporary failure' },
      { status: 503, statusText: 'Unavailable' },
    );
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelectorAll('tbody tr')).toHaveLength(2);
    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain(
      'Temporary failure',
    );
  });
});
