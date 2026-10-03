import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter, Router } from '@angular/router';

import { MoneyOperation } from './money-operation';

describe('MoneyOperation', () => {
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
              data: { operation: 'deposit' },
            },
          },
        },
      ],
    });
  });

  it('accepts only positive amounts with at most two decimal places', () => {
    const fixture = TestBed.createComponent(MoneyOperation);
    const component = fixture.componentInstance;

    component.amount.setValue('10.25');
    expect(component.amount.valid).toBe(true);

    component.amount.setValue('0');
    expect(component.amount.invalid).toBe(true);

    component.amount.setValue('10.251');
    expect(component.amount.invalid).toBe(true);

    component.amount.setValue('0.001');
    expect(component.amount.invalid).toBe(true);
  });

  it('returns to the account dashboard after a successful deposit', () => {
    const fixture = TestBed.createComponent(MoneyOperation);
    const component = fixture.componentInstance;
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);

    component.amount.setValue('25.00');
    fixture.detectChanges();
    const form = fixture.nativeElement.querySelector('form') as HTMLFormElement;
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    TestBed.inject(HttpTestingController)
      .expectOne('/api/accounts/account-123/deposits')
      .flush({ accountId: 'account-123', balance: 25 });

    expect(navigate).toHaveBeenCalledWith(['/accounts', 'account-123']);
  });
});
