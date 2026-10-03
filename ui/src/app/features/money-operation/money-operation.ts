import { CurrencyPipe, TitleCasePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import { ApiError, toApiError } from '../../core/api/api-error';
import { BankingApiService } from '../../core/api/banking-api.service';
import { BalanceChangeResponse } from '../../core/api/banking-api.models';

type MoneyOperationName = 'deposit' | 'withdraw';

@Component({
  selector: 'app-money-operation',
  imports: [CurrencyPipe, ReactiveFormsModule, RouterLink, TitleCasePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="form-page">
      <a class="back-link" [routerLink]="['/accounts', accountId]">← Account overview</a>

      <div class="card form-card">
        <p class="eyebrow">{{ operation | titlecase }} funds</p>
        <h1>{{ operation === 'deposit' ? 'Add money to your account' : 'Withdraw from your balance' }}</h1>
        <p>
          {{ operation === 'deposit'
            ? 'Your deposit is recorded immediately as a balanced ledger transaction.'
            : 'The withdrawal will complete only when sufficient funds are available.' }}
        </p>

        @if (!result()) {
          <form [formGroup]="form" (ngSubmit)="submit()">
            <label for="amount">Amount</label>
            <div class="money-input">
              <span>฿</span>
              <input
                id="amount"
                type="text"
                inputmode="decimal"
                autocomplete="off"
                placeholder="0.00"
                [formControl]="amount"
              >
              <small>THB</small>
            </div>
            @if (amount.invalid && amount.touched) {
              <p class="field-error">Enter a positive amount with no more than two decimal places.</p>
            }
            @if (error()) {
              <div class="notice notice-error compact" role="alert">
                <div><strong>{{ error()?.code }}</strong><span>{{ error()?.message }}</span></div>
              </div>
            }
            <button class="button button-primary button-block" type="submit" [disabled]="amount.invalid || submitting()">
              {{ submitting() ? 'Recording…' : (operation === 'deposit' ? 'Confirm deposit' : 'Confirm withdrawal') }}
            </button>
          </form>
        } @else {
          <div class="success-state" aria-live="polite">
            <span class="success-mark">✓</span>
            <h2>{{ operation | titlecase }} complete</h2>
            <p>Your available balance is now</p>
            <strong>{{ result()?.balance | currency:'THB':'symbol-narrow':'1.2-2' }}</strong>
            <a class="button button-primary button-block" [routerLink]="['/accounts', accountId]">Return to account</a>
          </div>
        }
      </div>
    </section>
  `,
})
export class MoneyOperation {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly api = inject(BankingApiService);

  readonly accountId = this.route.snapshot.paramMap.get('accountId') ?? '';
  readonly operation = (this.route.snapshot.data['operation'] ?? 'deposit') as MoneyOperationName;
  readonly amount = new FormControl('', {
    nonNullable: true,
    validators: [
      Validators.required,
      Validators.pattern(/^(?:0*[1-9]\d*(?:\.\d{1,2})?|0+\.(?:0[1-9]|[1-9]\d?))$/),
    ],
  });
  readonly form = new FormGroup({ amount: this.amount });
  readonly submitting = signal(false);
  readonly error = signal<ApiError | null>(null);
  readonly result = signal<BalanceChangeResponse | null>(null);

  submit(): void {
    this.amount.markAsTouched();
    if (this.amount.invalid || this.submitting()) {
      return;
    }

    this.submitting.set(true);
    this.error.set(null);
    const amount = Number(this.amount.value);
    const request =
      this.operation === 'deposit'
        ? this.api.deposit(this.accountId, amount)
        : this.api.withdraw(this.accountId, amount);

    request.subscribe({
      next: (result) => {
        if (this.operation === 'deposit') {
          void this.router.navigate(['/accounts', this.accountId]);
        } else {
          this.result.set(result);
          this.submitting.set(false);
        }
      },
      error: (error: unknown) => {
        this.error.set(toApiError(error));
        this.submitting.set(false);
      },
    });
  }
}
