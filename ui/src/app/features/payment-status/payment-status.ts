import { CurrencyPipe, DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { catchError, EMPTY, switchMap, takeWhile, timer } from 'rxjs';

import { ApiError, toApiError } from '../../core/api/api-error';
import { BankingApiService } from '../../core/api/banking-api.service';
import { BillPaymentProcess, BillPaymentStatus as PaymentState } from '../../core/api/banking-api.models';

@Component({
  selector: 'app-payment-status',
  imports: [CurrencyPipe, DatePipe, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="form-page wide-form">
      <a class="back-link" [routerLink]="['/accounts', accountId]">← Account overview</a>

      <div class="flow-progress" aria-label="Bill payment progress">
        <span class="active"><b>✓</b> Bill details</span><i></i>
        <span class="active"><b>✓</b> Review</span><i></i>
        <span class="active"><b>3</b> Receipt</span>
      </div>

      <div class="card receipt-card" aria-live="polite">
        @if (error()) {
          <div class="notice notice-error" role="alert">
            <div><strong>Unable to check this payment.</strong><span>{{ error()?.message }}</span></div>
            <button class="button button-quiet" type="button" (click)="watchPayment()">Try again</button>
          </div>
        } @else if (!payment()) {
          <div class="processing-state">
            <span class="spinner large"></span>
            <h1>Finding your payment…</h1>
          </div>
        } @else if (isProcessing(payment()!.status)) {
          <div class="processing-state">
            <span class="processing-orbit"><i></i></span>
            <p class="eyebrow">{{ statusLabel(payment()!.status) }}</p>
            <h1>Your payment is in motion</h1>
            <p>Keep this page open. We'll update it as soon as the account and biller finish processing.</p>
            <small>Payment {{ shortId(payment()!.paymentId) }}</small>
          </div>
        } @else if (payment()!.status === 'completed') {
          <div class="receipt-heading success">
            <span class="success-mark">✓</span>
            <p class="eyebrow">Payment complete</p>
            <h1>{{ payment()!.amount | currency:'THB':'symbol-narrow':'1.2-2' }}</h1>
            <p>Paid to <strong>{{ payment()!.billerCode }}</strong></p>
          </div>
          <dl class="receipt-details">
            <div><dt>Receipt</dt><dd>{{ payment()!.billerReceiptCode }}</dd></div>
            <div><dt>Paid at</dt><dd>{{ payment()!.paidAt | date:'medium' }}</dd></div>
            <div><dt>Remaining balance</dt><dd>{{ payment()!.resultingBalance | currency:'THB':'symbol-narrow':'1.2-2' }}</dd></div>
            <div><dt>Payment ID</dt><dd class="mono">{{ payment()!.paymentId }}</dd></div>
          </dl>
          <a class="button button-primary button-block" [routerLink]="['/accounts', accountId]">Return to account</a>
        } @else {
          <div class="receipt-heading failed">
            <span class="failure-mark">!</span>
            <p class="eyebrow">{{ payment()!.status === 'manual_review' ? 'Manual review' : 'Payment not completed' }}</p>
            <h1>{{ payment()!.status === 'manual_review' ? 'We’re checking this payment' : 'Your money is safe' }}</h1>
            <p>{{ payment()!.failure?.message ?? 'The payment could not be completed.' }}</p>
          </div>
          @if (payment()!.resultingBalance !== null) {
            <p class="balance-note">Available balance: <strong>{{ payment()!.resultingBalance | currency:'THB':'symbol-narrow':'1.2-2' }}</strong></p>
          }
          <a class="button button-primary button-block" [routerLink]="['/accounts', accountId]">Return to account</a>
        }
      </div>
    </section>
  `,
})
export class PaymentStatus implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly api = inject(BankingApiService);
  private readonly destroyRef = inject(DestroyRef);

  readonly accountId = this.route.snapshot.paramMap.get('accountId') ?? '';
  readonly paymentId = this.route.snapshot.paramMap.get('paymentId') ?? '';
  readonly payment = signal<BillPaymentProcess | null>(null);
  readonly error = signal<ApiError | null>(null);

  ngOnInit(): void {
    this.watchPayment();
  }

  watchPayment(): void {
    this.error.set(null);
    timer(0, 750)
      .pipe(
        switchMap(() => this.api.getBillPayment(this.accountId, this.paymentId)),
        takeWhile((payment) => this.isProcessing(payment.status), true),
        takeUntilDestroyed(this.destroyRef),
        catchError((error: unknown) => {
          this.error.set(toApiError(error));
          return EMPTY;
        }),
      )
      .subscribe((payment) => this.payment.set(payment));
  }

  isProcessing(status: PaymentState): boolean {
    return status === 'awaiting_account_charge' || status === 'settling' || status === 'reversing';
  }

  statusLabel(status: PaymentState): string {
    const labels: Record<PaymentState, string> = {
      awaiting_account_charge: 'Reserving funds',
      settling: 'Contacting biller',
      reversing: 'Restoring balance',
      completed: 'Complete',
      failed: 'Failed',
      manual_review: 'Manual review',
    };
    return labels[status];
  }

  shortId(paymentId: string): string {
    return paymentId.slice(0, 8).toUpperCase();
  }
}
