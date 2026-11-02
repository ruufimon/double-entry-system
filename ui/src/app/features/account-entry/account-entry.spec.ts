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

  it('shows only the seeded accounts and navigates without creating an account', async () => {
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/', AccountEntry);

    harness.fixture.detectChanges();
    const links = harness.fixture.nativeElement.querySelectorAll('a[routerLink]') as NodeListOf<HTMLAnchorElement>;
    expect([...links].map((link) => link.textContent?.trim())).toEqual(['acc123 →', 'acc456 →']);
    links[0].click();
    await harness.fixture.whenStable();

    expect(TestBed.inject(Location).path()).toBe('/accounts/acc123');
    TestBed.inject(HttpTestingController).expectNone('/api/accounts/acc123');
  });
});
