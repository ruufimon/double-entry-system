import { CurrencyPipe, DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import { ApiError, toApiError } from '../../core/api/api-error';
import { BankingApiService } from '../../core/api/banking-api.service';
import { BillPaymentInquiry } from '../../core/api/banking-api.models';

@Component({
  selector: 'app-bill-payment',
  imports: [CurrencyPipe, DatePipe, ReactiveFormsModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="form-page wide-form">
      <a class="back-link" [routerLink]="['/accounts', accountId]">← Account overview</a>

      <div class="flow-progress" aria-label="Bill payment progress">
        <span class="active"><b>1</b> Bill details</span>
        <i></i>
        <span [class.active]="inquiry()"><b>2</b> Review</span>
        <i></i>
        <span><b>3</b> Receipt</span>
      </div>

      <div class="card form-card">
        @if (!inquiry()) {
          <p class="eyebrow">Find your bill</p>
          <h1>Who are you paying?</h1>
          <p>We'll ask the biller for the current amount before anything leaves your account.</p>

          <form [formGroup]="form" (ngSubmit)="findBill()">
            <label for="biller-code">Biller code</label>
            <input id="biller-code" type="text" formControlName="billerCode" autocomplete="off">

            <div class="field-grid">
              <div>
                <label for="reference-1">Reference code 1</label>
                <input id="reference-1" type="text" formControlName="referenceCode1" autocomplete="off">
              </div>
              <div>
                <label for="reference-2">Reference code 2</label>
                <input id="reference-2" type="text" formControlName="referenceCode2" autocomplete="off">
              </div>
            </div>

            @if (form.invalid && form.touched) {
              <p class="field-error">All biller and reference fields are required.</p>
            }
            @if (error()) {
              <div class="notice notice-error compact" role="alert">
                <div><strong>{{ error()?.code }}</strong><span>{{ error()?.message }}</span></div>
              </div>
            }
            <button class="button button-primary button-block" type="submit" [disabled]="form.invalid || submitting()">
              {{ submitting() ? 'Looking up bill…' : 'Check current amount' }}
            </button>
          </form>
          <p class="demo-hint">Demo bill details are filled in for you.</p>
        } @else {
          <p class="eyebrow">Review payment</p>
          <h1>Confirm the details</h1>
          <p>No payment is made until you confirm this current bill amount.</p>

          <div class="bill-summary">
            <div class="bill-amount">
              <span>Amount due</span>
              <strong>{{ inquiry()?.currentDebt | currency:'THB':'symbol-narrow':'1.2-2' }}</strong>
              <small>THB</small>
            </div>
            <dl>
              <div><dt>Biller</dt><dd>{{ inquiry()?.billerCode }}</dd></div>
              <div><dt>Reference 1</dt><dd>{{ inquiry()?.referenceCode1 }}</dd></div>
              <div><dt>Reference 2</dt><dd>{{ inquiry()?.referenceCode2 }}</dd></div>
              <div><dt>Quote expires</dt><dd>{{ inquiry()?.expiresAt | date:'mediumTime' }}</dd></div>
            </dl>
          </div>

          @if (error()) {
            <div class="notice notice-error compact" role="alert">
              <div><strong>{{ error()?.code }}</strong><span>{{ error()?.message }}</span></div>
            </div>
          }
          <div class="button-row">
            <button class="button button-quiet" type="button" (click)="changeDetails()" [disabled]="submitting()">Change details</button>
            <button class="button button-primary" type="button" (click)="confirmPayment()" [disabled]="submitting() || quoteExpired()">
              {{ submitting() ? 'Submitting…' : 'Confirm payment' }}
            </button>
          </div>
          @if (quoteExpired()) {
            <p class="field-error centered">This quote has expired. Please look up the bill again.</p>
          }
        }
      </div>
    </section>
  `,
})
export class BillPayment {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly api = inject(BankingApiService);

  readonly accountId = this.route.snapshot.paramMap.get('accountId') ?? '';
  readonly form = new FormGroup({
    billerCode: new FormControl('demo-biller', { nonNullable: true, validators: Validators.required }),
    referenceCode1: new FormControl('customer-001', {
      nonNullable: true,
      validators: Validators.required,
    }),
    referenceCode2: new FormControl('invoice-001', {
      nonNullable: true,
      validators: Validators.required,
    }),
  });
  readonly inquiry = signal<BillPaymentInquiry | null>(null);
  readonly submitting = signal(false);
  readonly error = signal<ApiError | null>(null);

  findBill(): void {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.submitting()) {
      return;
    }

    this.submitting.set(true);
    this.error.set(null);
    this.api.inquireBill(this.accountId, this.form.getRawValue()).subscribe({
      next: (inquiry) => {
        this.inquiry.set(inquiry);
        this.submitting.set(false);
      },
      error: (error: unknown) => {
        this.error.set(toApiError(error));
        this.submitting.set(false);
      },
    });
  }

  confirmPayment(): void {
    const inquiry = this.inquiry();
    if (!inquiry || this.submitting() || this.quoteExpired()) {
      return;
    }

    this.submitting.set(true);
    this.error.set(null);
    this.api.confirmBillPayment(this.accountId, inquiry.inquiryId).subscribe({
      next: (accepted) => {
        void this.router.navigate([
          '/accounts',
          this.accountId,
          'bill-payments',
          accepted.paymentId,
        ]);
      },
      error: (error: unknown) => {
        this.error.set(toApiError(error));
        this.submitting.set(false);
      },
    });
  }

  changeDetails(): void {
    this.inquiry.set(null);
    this.error.set(null);
  }

  quoteExpired(): boolean {
    const expiresAt = this.inquiry()?.expiresAt;
    return expiresAt ? Date.parse(expiresAt) <= Date.now() : false;
  }
}
