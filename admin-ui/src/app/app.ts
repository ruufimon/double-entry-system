import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

@Component({
  selector: 'admin-root',
  imports: [RouterLink, RouterLinkActive, RouterOutlet],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="site-header">
      <a class="brand" routerLink="/accounts" aria-label="Ledger Admin accounts">
        <span class="brand-mark" aria-hidden="true"><span></span></span>
        <span><strong>Ledger</strong><small>Admin</small></span>
      </a>
      <nav class="primary-nav" aria-label="Primary navigation">
        <a routerLink="/accounts" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: true }">Accounts</a>
        <span aria-disabled="true">Activity</span>
        <span aria-disabled="true">Reports</span>
        <a routerLink="/interest-rate" routerLinkActive="active">Interest rate</a>
      </nav>
      <div class="header-actions">
        <span class="environment-pill"><i aria-hidden="true"></i> Demo environment</span>
        <span class="operator-avatar" aria-label="Operations user">OP</span>
      </div>
    </header>

    <div class="security-banner" role="note">
      <strong>Demo operations console</strong>
      <span>Interest-rate settings require the configured admin password.</span>
    </div>

    <main class="page-shell"><router-outlet /></main>

    <footer class="site-footer">
      <span>Ledger Operations</span>
      <span>Account operations and interest settings</span>
    </footer>
  `,
})
export class App {}
