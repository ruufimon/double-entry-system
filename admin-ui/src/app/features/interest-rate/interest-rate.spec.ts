import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { InterestRate } from './interest-rate';

describe('InterestRate', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('signs in, previews the effective rate change, and saves it', () => {
    const fixture = TestBed.createComponent(InterestRate);
    fixture.detectChanges();

    const password = fixture.nativeElement.querySelector('#admin-password') as HTMLInputElement;
    password.value = 'secret';
    password.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('form') as HTMLFormElement).dispatchEvent(
      new Event('submit', { bubbles: true, cancelable: true }),
    );
    const signIn = http.expectOne('/api/admin/interest-rate');
    expect(signIn.request.headers.get('Authorization')).toBe(`Basic ${btoa('admin:secret')}`);
    signIn.flush({
      currentRate: 0,
      currentEffectiveFrom: '0001-01-01',
      nextEffectiveFrom: '2026-11-01',
      scheduledRate: null,
      scheduledEffectiveFrom: null,
      history: [
        {
          rate: 0,
          effectiveFrom: '0001-01-01',
          createdAt: '1970-01-01T00:00:00Z',
          updatedBy: 'System default',
          status: 'current',
        },
      ],
    });
    fixture.detectChanges();

    const rate = fixture.nativeElement.querySelector('#annual-rate') as HTMLInputElement;
    rate.value = '3.65';
    rate.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('0.00%');
    expect(fixture.nativeElement.textContent).toContain('3.65%');
    expect(fixture.nativeElement.textContent).toContain('1 Nov 2026');

    (fixture.nativeElement.querySelector('.interest-form') as HTMLFormElement).dispatchEvent(
      new Event('submit', { bubbles: true, cancelable: true }),
    );
    const save = http.expectOne('/api/admin/interest-rate');
    expect(save.request.method).toBe('PUT');
    expect(save.request.body).toEqual({ rate: 3.65 });
    save.flush({
      currentRate: 0,
      currentEffectiveFrom: '0001-01-01',
      nextEffectiveFrom: '2026-11-01',
      scheduledRate: 3.65,
      scheduledEffectiveFrom: '2026-11-01',
      history: [
        {
          rate: 3.65,
          effectiveFrom: '2026-11-01',
          createdAt: '2026-10-28T05:00:00Z',
          updatedBy: 'admin',
          status: 'scheduled',
        },
      ],
    });
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('3.65% scheduled to take effect 1 Nov 2026.');
    expect(fixture.nativeElement.textContent).toContain('Scheduled');
  });

  it('keeps rate settings hidden when authentication fails', () => {
    const fixture = TestBed.createComponent(InterestRate);
    fixture.detectChanges();
    const password = fixture.nativeElement.querySelector('#admin-password') as HTMLInputElement;
    password.value = 'wrong';
    password.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('form') as HTMLFormElement).dispatchEvent(
      new Event('submit', { bubbles: true, cancelable: true }),
    );
    http.expectOne('/api/admin/interest-rate').flush(
      { error: 'admin_auth_required', message: 'A valid admin password is required' },
      { status: 401, statusText: 'Unauthorized' },
    );
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('#annual-rate')).toBeNull();
    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain(
      'admin password is incorrect',
    );
  });
});
