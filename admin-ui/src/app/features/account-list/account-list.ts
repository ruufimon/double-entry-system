import { CurrencyPipe, DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';

import { AdminAccountSummary } from '../../core/api/admin-api.models';
import { AdminApiService } from '../../core/api/admin-api.service';
import { ApiError, toApiError } from '../../core/api/api-error';

@Component({
  selector: 'admin-account-list',
  imports: [CurrencyPipe, DatePipe, FormsModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="page-heading">
      <div><p class="eyebrow">Operations</p><h1>Accounts</h1><p>Inspect every account held by this API instance.</p></div>
      <button class="button button-quiet" type="button" (click)="loadAccounts()" [disabled]="loading() || refreshing()">
        {{ refreshing() ? 'Refreshing…' : 'Refresh data' }}
      </button>
    </section>

    @if (refreshError()) {
      <div class="notice notice-error" role="alert">
        <div><strong>Account data could not be refreshed.</strong><span>{{ refreshError()?.message }}</span></div>
        <button class="button button-quiet" type="button" (click)="loadAccounts()">Try again</button>
      </div>
    }

    @if (loading()) {
      <div class="metric-grid" aria-hidden="true">
        <div class="metric-card skeleton"></div><div class="metric-card skeleton"></div>
      </div>
      <div class="table-card loading-card" role="status">Loading all accounts…</div>
    } @else if (error()) {
      <div class="notice notice-error" role="alert">
        <div><strong>Accounts could not be loaded.</strong><span>{{ error()?.message }}</span></div>
        <button class="button button-quiet" type="button" (click)="loadAccounts()">Try again</button>
      </div>
    } @else {
      <section class="metric-grid" [attr.aria-busy]="refreshing()">
        <article class="metric-card"><span>Accounts</span><strong>{{ accounts().length }}</strong><small>Active in this instance</small></article>
        <article class="metric-card accent"><span>Aggregate balance</span><strong>{{ totalBalance() | currency:'THB':'symbol-narrow':'1.2-2' }}</strong><small>Ledger-derived THB</small></article>
      </section>

      <section class="account-lookup" aria-labelledby="account-lookup-title">
        <div>
          <p class="eyebrow">Direct lookup</p>
          <h2 id="account-lookup-title">View a specific account</h2>
          <p>Open an account directly when you already know its exact ID.</p>
        </div>
        <form (ngSubmit)="viewSpecificAccount()">
          <label for="specific-account-id">Account ID to view</label>
          <div class="lookup-controls">
            <input
              id="specific-account-id"
              name="specificAccountId"
              type="text"
              autocomplete="off"
              placeholder="e.g. account-123"
              [ngModel]="specificAccountId()"
              (ngModelChange)="specificAccountId.set($event)"
            >
            <button class="button button-primary" type="submit" [disabled]="!specificAccountIdValid()">
              View Detail <span aria-hidden="true">→</span>
            </button>
          </div>
          @if (specificAccountId().length > 0 && !specificAccountIdValid()) {
            <span class="field-error">Use 1–64 letters, numbers, underscores, or hyphens.</span>
          }
        </form>
      </section>      

      <section class="data-section">
        @if (accounts().length === 0) {
          <div class="table-card empty-state"><strong>No accounts yet</strong><span>Accounts appear here after they are opened.</span></div>
        } @else if (filteredAccounts().length === 0) {
          <div class="table-card empty-state"><strong>No matching accounts</strong><span>Try a different account ID.</span></div>
        } @else {
          <div class="table-card table-scroll">
            <table>
              <thead><tr><th>Account ID</th><th>Status</th><th class="numeric">Balance</th><th>Activity</th><th>Latest activity</th><th><span class="visually-hidden">Actions</span></th></tr></thead>
              <tbody>
                @for (account of filteredAccounts(); track account.accountId) {
                  <tr>
                    <td><span class="mono account-id">{{ account.accountId }}</span></td>
                    <td><span class="status-chip">{{ account.status }}</span></td>
                    <td class="numeric"><strong>{{ account.balance | currency:'THB':'symbol-narrow':'1.2-2' }}</strong><small>{{ account.currency }}</small></td>
                    <td>{{ account.activityCount }} {{ account.activityCount === 1 ? 'entry' : 'entries' }}</td>
                    <td>{{ account.lastActivityAt ? (account.lastActivityAt | date:'medium') : 'No activity' }}</td>
                    <td><a class="row-link" [routerLink]="['/accounts', account.accountId]">View details <span aria-hidden="true">→</span></a></td>
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
export class AccountList implements OnInit {
  private readonly api = inject(AdminApiService);
  private readonly router = inject(Router);

  readonly accounts = signal<readonly AdminAccountSummary[]>([]);
  readonly query = signal('');
  readonly specificAccountId = signal('');
  readonly loading = signal(true);
  readonly refreshing = signal(false);
  readonly loaded = signal(false);
  readonly error = signal<ApiError | null>(null);
  readonly refreshError = signal<ApiError | null>(null);
  readonly filteredAccounts = computed(() => {
    const query = this.query().trim().toLocaleLowerCase();
    return query.length === 0
      ? this.accounts()
      : this.accounts().filter((account) =>
          account.accountId.toLocaleLowerCase().includes(query),
        );
  });
  readonly totalBalance = computed(() =>
    this.accounts().reduce((total, account) => total + account.balance, 0),
  );
  readonly specificAccountIdValid = computed(() =>
    /^[A-Za-z0-9_-]{1,64}$/.test(this.specificAccountId().trim()),
  );

  ngOnInit(): void {
    this.loadAccounts();
  }

  loadAccounts(): void {
    if (this.loaded()) {
      this.refreshing.set(true);
      this.refreshError.set(null);
    } else {
      this.loading.set(true);
      this.error.set(null);
    }

    this.api.getAccounts().subscribe({
      next: (accounts) => {
        this.accounts.set(accounts);
        this.loaded.set(true);
        this.loading.set(false);
        this.refreshing.set(false);
      },
      error: (error: unknown) => {
        const apiError = toApiError(error);
        if (this.loaded()) {
          this.refreshError.set(apiError);
          this.refreshing.set(false);
        } else {
          this.error.set(apiError);
          this.loading.set(false);
        }
      },
    });
  }

  viewSpecificAccount(): void {
    if (!this.specificAccountIdValid()) {
      return;
    }
    void this.router.navigate(['/accounts', this.specificAccountId().trim()]);
  }
}
