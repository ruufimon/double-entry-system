import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-account-entry',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="hero-grid">
      <div class="hero-copy">
        <p class="eyebrow">Every baht, accounted for</p>
        <h1>Your balance.<br><em>Nothing hidden.</em></h1>
        <p class="hero-description">
          Deposit, withdraw, pay bills, and follow every movement through an immutable
          double-entry ledger.
        </p>
        <div class="trust-row" aria-label="Product characteristics">
          <span>Balanced entries</span>
          <span>Clear activity</span>
          <span>Instant feedback</span>
        </div>
      </div>

      <section class="card account-card" aria-labelledby="account-selection-title">
        <p class="step-label">Demo accounts</p>
        <h2 id="account-selection-title">Choose an account</h2>
        <p>Each demo account starts with THB 100.00.</p>
        <nav class="demo-accounts" aria-label="Select a demo account">
          <a class="button button-primary button-block" routerLink="/accounts/acc123">
            acc123 <span aria-hidden="true">→</span>
          </a>
          <a class="button button-primary button-block" routerLink="/accounts/acc456">
            acc456 <span aria-hidden="true">→</span>
          </a>
        </nav>
      </section>
    </section>
  `,
})
export class AccountEntry {}
