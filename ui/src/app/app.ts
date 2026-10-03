import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink, RouterOutlet } from '@angular/router';

@Component({
  selector: 'app-root',
  imports: [RouterLink, RouterOutlet],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="site-header">
      <a class="brand" routerLink="/" aria-label="Ledger Bank home">
        <span class="brand-mark" aria-hidden="true">L</span>
        <span>
          <strong>Ledger</strong>
          <small>Simple banking, clearly recorded</small>
        </span>
      </a>
      <span class="environment-pill">In-memory demo</span>
    </header>

    <main class="page-shell">
      <router-outlet />
    </main>

    <footer class="site-footer">
      <span>Every movement is backed by a balanced ledger entry.</span>
      <a routerLink="/">Switch account</a>
    </footer>
  `,
})
export class App {}
