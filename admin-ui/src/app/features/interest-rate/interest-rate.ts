import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { AdminInterestRate, InterestRateHistoryItem } from '../../core/api/admin-api.models';
import { AdminApiService } from '../../core/api/admin-api.service';
import { ApiError, toApiError } from '../../core/api/api-error';

@Component({
  selector: 'admin-interest-rate',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (loading()) {
      <div class="table-card loading-card" role="status">Loading interest rate…</div>
    } @else if (!rate()) {
      <section class="interest-sign-in">
        <p class="eyebrow">Protected settings</p>
        <h1>Admin sign-in</h1>
        <p>Enter the admin password to view and configure interest rates.</p>
        @if (error()) {
          <div class="notice notice-error" role="alert"><span>{{ error() }}</span></div>
        }
        <form (submit)="signIn($event)">
          <label for="admin-password">Admin password</label>
          <input id="admin-password" type="password" autocomplete="current-password" required
            (input)="passwordInput.set(inputValue($event))">
          <button class="button button-primary" type="submit" [disabled]="submitting()">
            {{ submitting() ? 'Checking…' : 'Continue' }}
          </button>
        </form>
      </section>
    } @else if (rate(); as current) {
      <section class="page-heading">
        <div>
          <p class="eyebrow">Account settings</p>
          <h1>Interest rate</h1>
          <p>Set the annual rate used to calculate monthly interest for all accounts.</p>
        </div>
        <span class="signed-in-chip">Admin session</span>
      </section>

      @if (error()) {
        <div class="notice notice-error" role="alert"><span>{{ error() }}</span></div>
      }
      @if (success()) {
        <div class="notice notice-success" role="status" aria-live="polite"><span>{{ success() }}</span></div>
      }

      <section class="interest-current" aria-label="Current interest rate">
        <div><span>Current annual rate</span><strong>{{ percent(current.currentRate) }}</strong></div>
        <div class="interest-effective">Effective from<strong>{{ dateLabel(current.currentEffectiveFrom) }}</strong></div>
      </section>

      <section class="interest-panel" aria-labelledby="rate-edit-title">
        <div class="interest-panel-heading">
          <h2 id="rate-edit-title">Set a new rate</h2>
          <p>Changes apply from the first day of next month.</p>
        </div>
        <form class="interest-form" (submit)="saveRate($event)">
          <label for="annual-rate">Annual interest rate</label>
          <div class="interest-input-row">
            <input id="annual-rate" name="rate" type="number" min="0" step="0.01" required
              inputmode="decimal" [value]="draftRate()" (input)="updateDraftRate($event)"
              aria-describedby="rate-input-hint">
            <span>% per year</span>
          </div>
          <small id="rate-input-hint">Enter an annual percentage with up to two decimal places.</small>
          <div class="interest-rule">
            <i aria-hidden="true"></i>
            <span>Effective from <strong>{{ dateLabel(current.scheduledEffectiveFrom ?? current.nextEffectiveFrom) }}</strong>
              · Interest is calculated from daily closing balances and credited monthly.</span>
          </div>
          <div class="interest-actions">
            <button class="button button-primary" type="submit" [disabled]="submitting() || !draftRate()">
              {{ submitting() ? 'Saving…' : 'Save rate' }}
            </button>
            <button class="button button-quiet" type="button" (click)="resetDraft(current)" [disabled]="submitting()">Cancel</button>
          </div>
        </form>
      </section>

      <section class="interest-impact" aria-labelledby="rate-impact-title">
        <div class="interest-section-heading"><h2 id="rate-impact-title">What changes</h2></div>
        <div class="interest-table-wrap">
          <table>
            <tbody>
              <tr><th scope="row">Rate</th><td>{{ percent(current.currentRate) }}</td><td aria-hidden="true">→</td><td>{{ draftPercent() }}</td></tr>
              <tr><th scope="row">Effective date</th><td>Current rate</td><td aria-hidden="true">→</td><td>{{ dateLabel(current.scheduledEffectiveFrom ?? current.nextEffectiveFrom) }}</td></tr>
              <tr><th scope="row">Calculation</th><td colspan="3">Daily closing balance · monthly credit · THB rounded to two decimals</td></tr>
            </tbody>
          </table>
        </div>
      </section>

      <section class="interest-history" aria-labelledby="rate-history-title">
        <div class="interest-section-heading"><h2 id="rate-history-title">Recent rate changes</h2><span>Audit history</span></div>
        @if (current.history.length === 0) {
          <div class="table-card empty-state"><strong>No rate history</strong><span>The default rate is 0.00%.</span></div>
        } @else {
          <div class="interest-table-wrap">
            <table>
              <thead><tr><th scope="col">Annual rate</th><th scope="col">Effective from</th><th scope="col">Updated by</th><th scope="col">Status</th></tr></thead>
              <tbody>
                @for (item of current.history; track $index) {
                  <tr><td>{{ percent(item.rate) }}</td><td>{{ dateLabel(item.effectiveFrom) }}</td><td>{{ item.updatedBy }}</td>
                    <td><span class="interest-status" [class.scheduled]="item.status === 'scheduled'">{{ statusLabel(item) }}</span></td>
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
export class InterestRate {
  private readonly api = inject(AdminApiService);

  readonly rate = signal<AdminInterestRate | null>(null);
  readonly passwordInput = signal('');
  readonly draftRate = signal('');
  readonly loading = signal(false);
  readonly submitting = signal(false);
  readonly error = signal<string | null>(null);
  readonly success = signal<string | null>(null);
  private readonly adminPassword = signal<string | null>(null);

  inputValue(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  signIn(event: Event): void {
    event.preventDefault();
    const password = this.passwordInput();
    if (!password || this.submitting()) return;
    this.loading.set(true);
    this.submitting.set(true);
    this.error.set(null);
    this.api.getInterestRate(password).subscribe({
      next: (overview) => {
        this.adminPassword.set(password);
        this.passwordInput.set('');
        this.rate.set(overview);
        this.draftRate.set(this.initialDraft(overview));
        this.loading.set(false);
        this.submitting.set(false);
      },
      error: (error: unknown) => {
        this.error.set(this.errorMessage(error, 'Admin sign-in failed.'));
        this.loading.set(false);
        this.submitting.set(false);
      },
    });
  }

  saveRate(event: Event): void {
    event.preventDefault();
    const form = event.target as HTMLFormElement;
    if (!form.reportValidity() || this.submitting()) return;
    const password = this.adminPassword();
    const rate = Number(this.draftRate());
    if (!password || !Number.isFinite(rate)) return;

    this.submitting.set(true);
    this.error.set(null);
    this.success.set(null);
    this.api.setInterestRate(password, rate).subscribe({
      next: (overview) => {
        this.rate.set(overview);
        this.draftRate.set(this.initialDraft(overview));
        this.success.set(`${this.percent(rate)} scheduled to take effect ${this.dateLabel(overview.scheduledEffectiveFrom ?? '')}.`);
        this.submitting.set(false);
      },
      error: (error: unknown) => {
        this.handleProtectedError(error);
        this.submitting.set(false);
      },
    });
  }

  updateDraftRate(event: Event): void {
    this.draftRate.set(this.inputValue(event));
    this.success.set(null);
  }

  resetDraft(current: AdminInterestRate): void {
    this.draftRate.set(this.initialDraft(current));
    this.error.set(null);
    this.success.set(null);
  }

  percent(rate: number): string {
    return `${rate.toFixed(2)}%`;
  }

  draftPercent(): string {
    const rate = Number(this.draftRate());
    return this.draftRate() !== '' && Number.isFinite(rate) ? this.percent(rate) : '—';
  }

  dateLabel(value: string): string {
    if (!value) return '—';
    const date = new Date(`${value}T00:00:00+07:00`);
    return date.toLocaleDateString('en-GB', {
      day: 'numeric',
      month: 'short',
      year: 'numeric',
      timeZone: 'Asia/Bangkok',
    });
  }

  statusLabel(item: InterestRateHistoryItem): string {
    return item.status.charAt(0).toUpperCase() + item.status.slice(1);
  }

  private initialDraft(overview: AdminInterestRate): string {
    return overview.scheduledRate?.toFixed(2) ?? '';
  }

  private handleProtectedError(error: unknown): void {
    const apiError = toApiError(error);
    if (apiError.status === 401) {
      this.adminPassword.set(null);
      this.rate.set(null);
      this.passwordInput.set('');
    }
    this.error.set(this.errorMessage(apiError, 'The rate could not be saved.'));
  }

  private errorMessage(error: unknown, fallback: string): string {
    const apiError: ApiError = toApiError(error);
    if (apiError.code === 'admin_auth_not_configured') return 'Admin password is not configured on the server.';
    if (apiError.status === 401) return 'The admin password is incorrect. Try again.';
    if (apiError.code === 'invalid_interest_rate') return apiError.message;
    return apiError.message || fallback;
  }
}
