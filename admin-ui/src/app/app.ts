import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink, RouterOutlet } from '@angular/router';

@Component({
  selector: 'admin-root',
  imports: [RouterLink, RouterOutlet],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="site-header">
      <a class="brand" routerLink="/accounts" aria-label="Ledger Admin accounts">
        <span class="brand-mark" aria-hidden="true">L</span>
        <span><strong>Ledger Admin</strong><small>Operational account console</small></span>
      </a>
      <span class="environment-pill">Read-only · In-memory</span>
    </header>

    <div class="security-banner" role="note">
      <strong>Unauthenticated demo admin</strong>
      <span>Do not expose this console to untrusted networks.</span>
    </div>

    <main class="page-shell"><router-outlet /></main>

    <footer class="site-footer">
      <span>Operational visibility into ledger-derived account data.</span>
      <span>Read-only console</span>
    </footer>
  `,
})
export class App {}
