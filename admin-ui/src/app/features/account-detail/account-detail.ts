import { CurrencyPipe, DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';

import { AdminAccountDetail, BankingOperation } from '../../core/api/admin-api.models';
import { AdminApiService } from '../../core/api/admin-api.service';
import { ApiError, toApiError } from '../../core/api/api-error';

@Component({
  selector: 'admin-account-detail',
  imports: [CurrencyPipe, DatePipe, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="page-heading">
      <div>
        <a class="back-link" routerLink="/accounts">← All accounts</a>
        <p class="eyebrow">Account detail</p><h1>{{ accountId }}</h1>
      </div>
      <button class="button button-quiet" type="button" (click)="loadAccount()" [disabled]="loading() || refreshing()">
        {{ refreshing() ? 'Refreshing…' : 'Refresh data' }}
      </button>
    </section>

    @if (refreshError()) {
      <div class="notice notice-error" role="alert">
        <div><strong>Account data could not be refreshed.</strong><span>{{ refreshError()?.message }}</span></div>
        <button class="button button-quiet" type="button" (click)="loadAccount()">Try again</button>
      </div>
    }

    @if (loading()) {
      <div class="metric-grid detail-metrics" aria-hidden="true">
        <div class="metric-card skeleton"></div><div class="metric-card skeleton"></div><div class="metric-card skeleton"></div>
      </div>
      <div class="table-card loading-card" role="status">Loading account details…</div>
    } @else if (error()) {
      <div class="notice notice-error" role="alert">
        <div><strong>Account details could not be loaded.</strong><span>{{ error()?.message }}</span></div>
        <button class="button button-quiet" type="button" (click)="loadAccount()">Try again</button>
      </div>
    } @else if (account(); as current) {
      <section class="metric-grid detail-metrics" [attr.aria-busy]="refreshing()">
        <article class="metric-card accent"><span>Available balance</span><strong>{{ current.balance | currency:'THB':'symbol-narrow':'1.2-2' }}</strong><small>{{ current.currency }} · Ledger-derived</small></article>
        <article class="metric-card"><span>Account status</span><strong class="status-value">{{ current.status }}</strong><small>Read-only operational state</small></article>
        <article class="metric-card"><span>Activity records</span><strong>{{ current.activityCount }}</strong><small>{{ current.lastActivityAt ? 'Latest ' + (current.lastActivityAt | date:'medium') : 'No activity yet' }}</small></article>
      </section>

      <section class="data-section">
        <div class="data-toolbar"><div><p class="eyebrow">Immutable record</p><h2>Complete activity</h2></div><span>{{ current.activityCount }} entries</span></div>
        @if (activities().length === 0) {
          <div class="table-card empty-state"><strong>No activity recorded</strong><span>This active account currently has no ledger transactions.</span></div>
        } @else {
          <div class="table-card table-scroll">
            <table class="activity-table">
              <thead><tr><th>Occurred</th><th>Operation</th><th>Counterparty</th><th>Transaction ID</th><th>Effect</th><th class="numeric">Amount</th><th class="numeric">Balance after</th><th>Status</th><th>Original transaction</th></tr></thead>
              <tbody>
                @for (activity of activities(); track activity.transactionId) {
                  <tr [class.reversed-row]="activity.status === 'reversed'">
                    <td>{{ activity.occurredAt | date:'medium' }}</td>
                    <td><strong>{{ operationLabel(activity.operation) }}</strong></td>
                    <td><span class="mono id-value">{{ activity.counterpartyAccountId ?? '—' }}</span></td>
                    <td><span class="mono id-value">{{ activity.transactionId }}</span></td>
                    <td><span class="effect" [class.increase]="activity.effect === 'increase'">{{ activity.effect }}</span></td>
                    <td class="numeric">{{ activity.effect === 'increase' ? '+' : '−' }}{{ activity.amount | currency:'THB':'symbol-narrow':'1.2-2' }}</td>
                    <td class="numeric">{{ activity.balanceAfter | currency:'THB':'symbol-narrow':'1.2-2' }}</td>
                    <td><span class="status-chip" [class.neutral]="activity.status === 'reversed'">{{ activity.status }}</span></td>
                    <td><span class="mono id-value">{{ activity.originalTransactionId ?? '—' }}</span></td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      </section>
    }
  `,
})
export class AccountDetail implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly api = inject(AdminApiService);

  readonly accountId = this.route.snapshot.paramMap.get('accountId') ?? '';
  readonly account = signal<AdminAccountDetail | null>(null);
  readonly loading = signal(true);
  readonly refreshing = signal(false);
  readonly error = signal<ApiError | null>(null);
  readonly refreshError = signal<ApiError | null>(null);
  readonly activities = computed(() => [...(this.account()?.activities ?? [])].reverse());

  ngOnInit(): void {
    this.loadAccount();
  }

  loadAccount(): void {
    const hasData = this.account() !== null;
    if (hasData) {
      this.refreshing.set(true);
      this.refreshError.set(null);
    } else {
      this.loading.set(true);
      this.error.set(null);
    }

    this.api.getAccount(this.accountId).subscribe({
      next: (account) => {
        this.account.set(account);
        this.loading.set(false);
        this.refreshing.set(false);
      },
      error: (error: unknown) => {
        const apiError = toApiError(error);
        if (hasData) {
          this.refreshError.set(apiError);
          this.refreshing.set(false);
        } else {
          this.error.set(apiError);
          this.loading.set(false);
        }
      },
    });
  }

  operationLabel(operation: BankingOperation): string {
    const labels: Record<BankingOperation, string> = {
      deposit: 'Deposit',
      withdrawal: 'Withdrawal',
      transfer_out: 'Transfer sent',
      transfer_in: 'Transfer received',
      bill_payment: 'Bill payment',
      bill_payment_reversal: 'Bill payment reversal',
    };
    return labels[operation];
  }
}
