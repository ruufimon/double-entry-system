import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';

import { Transfer } from './transfer';

describe('Transfer', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { paramMap: convertToParamMap({ accountId: 'source-account' }) },
          },
        },
      ],
    });
  });

  it('posts a transfer and displays the completed result', () => {
    const fixture = TestBed.createComponent(Transfer);
    const component = fixture.componentInstance;
    component.destinationAccountId.setValue('destination-account');
    component.amount.setValue('25.50');
    fixture.detectChanges();

    fixture.nativeElement.querySelector('form').dispatchEvent(
      new Event('submit', { bubbles: true, cancelable: true }),
    );
    TestBed.inject(HttpTestingController)
      .expectOne('/api/accounts/source-account/transfers')
      .flush({
        transferId: 'transfer-123',
        sourceAccountId: 'source-account',
        destinationAccountId: 'destination-account',
        amount: 25.5,
        currency: 'THB',
        sourceBalance: 74.5,
        occurredAt: '2026-10-09T04:00:00Z',
      });
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Transfer complete');
    expect(fixture.nativeElement.textContent).toContain('destination-account');
    expect(fixture.nativeElement.textContent).toContain('฿74.50');
  });

  it('rejects a transfer to the source account before calling the API', () => {
    const fixture = TestBed.createComponent(Transfer);
    const component = fixture.componentInstance;
    component.destinationAccountId.setValue('source-account');
    component.amount.setValue('5.00');

    component.submit();

    expect(component.destinationAccountId.hasError('sameAccount')).toBe(true);
    TestBed.inject(HttpTestingController).expectNone('/api/accounts/source-account/transfers');
  });
});
