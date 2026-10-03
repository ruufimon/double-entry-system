import { Location } from '@angular/common';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';

import { AccountEntry } from './account-entry';

describe('AccountEntry', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([
          { path: '', component: AccountEntry },
          { path: 'accounts/:accountId', component: AccountEntry },
        ]),
      ],
    });
  });

  it('navigates to the account dashboard after submission', async () => {
    const harness = await RouterTestingHarness.create();
    const component = await harness.navigateByUrl('/', AccountEntry);

    component.accountId.setValue('account-123');
    harness.fixture.detectChanges();
    const form = harness.fixture.nativeElement.querySelector('form') as HTMLFormElement;
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    TestBed.inject(HttpTestingController)
      .expectOne('/api/accounts/account-123')
      .flush({ accountId: 'account-123', currency: 'THB', balance: 0, status: 'active' });
    await harness.fixture.whenStable();

    expect(TestBed.inject(Location).path()).toBe('/accounts/account-123');
  });
});
