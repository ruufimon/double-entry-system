import { CurrencyPipe, DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';

import { ApiError, toApiError } from '../../core/api/api-error';
import { BankingApiService } from '../../core/api/banking-api.service';
import { AccountActivity, AccountBalance, BankingOperation } from '../../core/api/banking-api.models';

@Component({
  selector: 'app-account-dashboard',
  imports: [CurrencyPipe, DatePipe, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="dashboard-heading">
      <div>
        <a class="back-link" routerLink="/">← Switch account</a>
        <p class="eyebrow">Account overview</p>
        <h1>{{ accountId }}</h1>
      </div>
      <button class="button button-quiet" type="button" (click)="loadAccount()" [disabled]="loading() || refreshing()">
        {{ refreshing() ? 'Refreshing…' : 'Refresh' }}
      </button>
    </section>

    @if (refreshError()) {
      <div class="notice notice-error refresh-notice" role="alert">
        <div><strong>We couldn't refresh this account.</strong><span>{{ refreshError()?.message }}</span></div>
        <button class="button button-quiet" type="button" (click)="loadAccount()">Try again</button>
      </div>
    }

    @if (loading()) {
      <span class="visually-hidden" role="status">Loading account details…</span>
      <div class="account-skeleton" aria-hidden="true">
        <section class="balance-layout">
          <article class="balance-card skeleton-surface">
            <span class="skeleton-line skeleton-label"></span>
            <span class="skeleton-line skeleton-balance"></span>
            <span class="skeleton-line skeleton-meta"></span>
          </article>
          <div class="quick-actions">
            <div class="action-card skeleton-surface"><span class="skeleton-circle"></span><span class="skeleton-line"></span><span class="skeleton-line skeleton-short"></span></div>
            <div class="action-card skeleton-surface"><span class="skeleton-circle"></span><span class="skeleton-line"></span><span class="skeleton-line skeleton-short"></span></div>
            <div class="action-card skeleton-surface"><span class="skeleton-circle"></span><span class="skeleton-line"></span><span class="skeleton-line skeleton-short"></span></div>
          </div>
        </section>
        <section class="activity-section">
          <div class="section-heading">
            <div><span class="skeleton-line skeleton-label"></span><span class="skeleton-line skeleton-heading"></span></div>
          </div>
          <div class="activity-list skeleton-activities">
            <div class="activity-row"><span class="skeleton-circle"></span><span class="skeleton-line"></span><span class="skeleton-line"></span><span class="skeleton-line"></span></div>
            <div class="activity-row"><span class="skeleton-circle"></span><span class="skeleton-line"></span><span class="skeleton-line"></span><span class="skeleton-line"></span></div>
            <div class="activity-row"><span class="skeleton-circle"></span><span class="skeleton-line"></span><span class="skeleton-line"></span><span class="skeleton-line"></span></div>
          </div>
        </section>
      </div>
    } @else if (error() && !accountMissing()) {
      <div class="notice notice-error" role="alert">
        <div><strong>We couldn't load this account.</strong><span>{{ error()?.message }}</span></div>
        <button class="button button-quiet" type="button" (click)="loadAccount()">Try again</button>
      </div>
    } @else if (accountMissing()) {
      <section class="empty-account card">
        <span class="empty-icon" aria-hidden="true">＋</span>
        <p class="eyebrow">New account</p>
        <h2>Start with your first deposit</h2>
        <p>No ledger entries exist for <strong>{{ accountId }}</strong> yet.</p>
        <a class="button button-primary" [routerLink]="['/accounts', accountId, 'deposit']">Make a deposit</a>
      </section>
    } @else {
      <div class="account-content" [attr.aria-busy]="refreshing()">
        <section class="balance-layout">
          <article class="balance-card">
            <p>Available balance</p>
            <strong>{{ balance()?.balance | currency:'THB':'symbol-narrow':'1.2-2' }}</strong>
            <span>{{ balance()?.currency }} · Ledger derived</span>
            <div class="balance-decoration" aria-hidden="true"></div>
          </article>

          <nav class="quick-actions" aria-label="Account actions">
            <a class="action-card" [routerLink]="['/accounts', accountId, 'deposit']">
              <span class="action-icon increase">＋</span><strong>Deposit</strong><small>Add funds</small>
            </a>
            <a class="action-card" [routerLink]="['/accounts', accountId, 'withdraw']">
              <span class="action-icon decrease">−</span><strong>Withdraw</strong><small>Take out funds</small>
            </a>
            <a class="action-card" [routerLink]="['/accounts', accountId, 'bill-payment']">
              <span class="action-icon bill">↗</span><strong>Pay a bill</strong><small>Settle a biller</small>
            </a>
          </nav>
        </section>

        <section class="activity-section">
          <div class="section-heading">
            <div><p class="eyebrow">Immutable record</p><h2>Recent activity</h2></div>
            <span>{{ activities().length }} entries</span>
          </div>

          @if (activities().length === 0) {
            <div class="card empty-list">No activity has been recorded yet.</div>
          } @else {
            <div class="activity-list">
              @for (activity of activities(); track activity.transactionId) {
                <article class="activity-row" [class.reversed]="activity.status === 'reversed'">
                  <span class="activity-icon" [class.increase]="activity.effect === 'increase'">
                    {{ activity.effect === 'increase' ? '↓' : '↑' }}
                  </span>
                  <div class="activity-name">
                    <strong>{{ operationLabel(activity.operation) }}</strong>
                    <span>{{ activity.occurredAt | date:'medium' }}</span>
                  </div>
                  <div class="activity-status">
                    <span class="status-chip" [class.status-reversed]="activity.status === 'reversed'">
                      {{ activity.status }}
                    </span>
                  </div>
                  <div class="activity-amount" [class.positive]="activity.effect === 'increase'">
                    <strong>{{ activity.effect === 'increase' ? '+' : '−' }}{{ activity.amount | currency:'THB':'symbol-narrow':'1.2-2' }}</strong>
                    <span>Balance {{ activity.balanceAfter | currency:'THB':'symbol-narrow':'1.2-2' }}</span>
                  </div>
                </article>
              }
            </div>
          }
        </section>
      </div>
    }
  `,
})
export class AccountDashboard implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly api = inject(BankingApiService);

  readonly accountId = this.route.snapshot.paramMap.get('accountId') ?? '';
  readonly loading = signal(true);
  readonly refreshing = signal(false);
  readonly accountMissing = signal(false);
  readonly error = signal<ApiError | null>(null);
  readonly refreshError = signal<ApiError | null>(null);
  readonly balance = signal<AccountBalance | null>(null);
  readonly activities = signal<readonly AccountActivity[]>([]);

  ngOnInit(): void {
    this.loadAccount();
  }

  loadAccount(): void {
    const hasAccountData = this.balance() !== null;
    if (hasAccountData) {
      this.refreshing.set(true);
      this.refreshError.set(null);
    } else {
      this.loading.set(true);
      this.error.set(null);
      this.accountMissing.set(false);
    }

    this.api.getAccountOverview(this.accountId).subscribe({
      next: (overview) => {
        this.balance.set(overview);
        this.activities.set([...(overview.activities ?? [])].reverse());
        this.loading.set(false);
        this.refreshing.set(false);
      },
      error: (error: unknown) => {
        const apiError = toApiError(error);
        if (hasAccountData) {
          this.refreshError.set(apiError);
          this.refreshing.set(false);
        } else {
          this.accountMissing.set(apiError.code === 'account_not_found');
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
      bill_payment: 'Bill payment',
      bill_payment_reversal: 'Bill payment reversal',
    };
    return labels[operation];
  }
}
