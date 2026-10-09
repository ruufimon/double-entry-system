import { CurrencyPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';

import { ApiError, toApiError } from '../../core/api/api-error';
import { BankingApiService } from '../../core/api/banking-api.service';
import { TransferResponse } from '../../core/api/banking-api.models';

@Component({
  selector: 'app-transfer',
  imports: [CurrencyPipe, ReactiveFormsModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="form-page">
      <a class="back-link" [routerLink]="['/accounts', accountId]">← Account overview</a>

      <div class="card form-card">
        <p class="eyebrow">Account transfer</p>
        <h1>Send money instantly</h1>
        <p>The debit and credit are recorded together as one atomic ledger transaction.</p>

        @if (!result()) {
          <form [formGroup]="form" (ngSubmit)="submit()">
            <label for="destinationAccountId">Destination account</label>
            <input
              id="destinationAccountId"
              type="text"
              autocomplete="off"
              placeholder="recipient-account"
              [formControl]="destinationAccountId"
            >
            @if (destinationAccountId.invalid && destinationAccountId.touched) {
              <p class="field-error">
                {{ destinationAccountId.hasError('sameAccount')
                  ? 'Choose an account other than the source account.'
                  : 'Enter a valid account ID.' }}
              </p>
            }

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
            <button class="button button-primary button-block" type="submit" [disabled]="form.invalid || submitting()">
              {{ submitting() ? 'Transferring…' : 'Confirm transfer' }}
            </button>
          </form>
        } @else {
          <div class="success-state" aria-live="polite">
            <span class="success-mark">✓</span>
            <h2>Transfer complete</h2>
            <p>
              {{ result()!.amount | currency:'THB':'symbol-narrow':'1.2-2' }} sent to
              <strong>{{ result()!.destinationAccountId }}</strong>
            </p>
            <p>Your available balance is now</p>
            <strong>{{ result()!.sourceBalance | currency:'THB':'symbol-narrow':'1.2-2' }}</strong>
            <small class="mono">Transfer {{ result()!.transferId }}</small>
            <a class="button button-primary button-block" [routerLink]="['/accounts', accountId]">Return to account</a>
          </div>
        }
      </div>
    </section>
  `,
})
export class Transfer {
  private readonly route = inject(ActivatedRoute);
  private readonly api = inject(BankingApiService);

  readonly accountId = this.route.snapshot.paramMap.get('accountId') ?? '';
  readonly destinationAccountId = new FormControl('', {
    nonNullable: true,
    validators: [Validators.required, Validators.pattern(/^[A-Za-z0-9_-]{1,64}$/)],
  });
  readonly amount = new FormControl('', {
    nonNullable: true,
    validators: [
      Validators.required,
      Validators.pattern(/^(?:0*[1-9]\d*(?:\.\d{1,2})?|0+\.(?:0[1-9]|[1-9]\d?))$/),
    ],
  });
  readonly form = new FormGroup({
    destinationAccountId: this.destinationAccountId,
    amount: this.amount,
  });
  readonly submitting = signal(false);
  readonly error = signal<ApiError | null>(null);
  readonly result = signal<TransferResponse | null>(null);

  submit(): void {
    this.destinationAccountId.markAsTouched();
    this.amount.markAsTouched();
    if (this.destinationAccountId.value === this.accountId) {
      this.destinationAccountId.setErrors({ sameAccount: true });
    }
    if (this.form.invalid || this.submitting()) {
      return;
    }

    this.submitting.set(true);
    this.error.set(null);
    this.api
      .transfer(this.accountId, this.destinationAccountId.value, Number(this.amount.value))
      .subscribe({
        next: (result) => {
          this.result.set(result);
          this.submitting.set(false);
        },
        error: (error: unknown) => {
          this.error.set(toApiError(error));
          this.submitting.set(false);
        },
      });
  }
}
