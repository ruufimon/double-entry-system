import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';

import { ApiError, toApiError } from '../../core/api/api-error';
import { BankingApiService } from '../../core/api/banking-api.service';

@Component({
  selector: 'app-account-entry',
  imports: [ReactiveFormsModule],
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

      <form class="card account-card" [formGroup]="form" (ngSubmit)="openAccount()">
        <p class="step-label">Start here</p>
        <h2>Open an account</h2>
        <p>Enter an account ID. A new account is created by making its first deposit.</p>

        <label for="account-id">Account ID</label>
        <input
          id="account-id"
          type="text"
          autocomplete="username"
          placeholder="e.g. account-123"
          [formControl]="accountId"
        >
        @if (accountId.invalid && accountId.touched) {
          <p class="field-error">Use 1–64 letters, numbers, underscores, or hyphens.</p>
        }
        @if (error()) {
          <div class="notice notice-error compact" role="alert">
            <div><strong>{{ error()?.code }}</strong><span>{{ error()?.message }}</span></div>
          </div>
        }

        <button class="button button-primary button-block" type="submit" [disabled]="accountId.invalid || submitting()">
          {{ submitting() ? 'Opening account…' : 'Continue to account' }}
          <span aria-hidden="true">→</span>
        </button>
        <p class="demo-hint">Try <button type="button" (click)="useDemoAccount()">account-123</button></p>
      </form>
    </section>
  `,
})
export class AccountEntry {
  private readonly router = inject(Router);
  private readonly api = inject(BankingApiService);

  readonly accountId = new FormControl('', {
    nonNullable: true,
    validators: [Validators.required, Validators.pattern(/^[A-Za-z0-9_-]{1,64}$/)],
  });
  readonly form = new FormGroup({ accountId: this.accountId });
  readonly submitting = signal(false);
  readonly error = signal<ApiError | null>(null);

  openAccount(): void {
    this.accountId.markAsTouched();
    if (this.accountId.invalid) {
      return;
    }

    const accountId = this.accountId.value.trim();
    this.submitting.set(true);
    this.error.set(null);
    this.api.openAccount(accountId).subscribe({
      next: () => void this.router.navigate(['/accounts', accountId]),
      error: (error: unknown) => {
        this.error.set(toApiError(error));
        this.submitting.set(false);
      },
    });
  }

  useDemoAccount(): void {
    this.accountId.setValue('account-123');
    this.openAccount();
  }
}
