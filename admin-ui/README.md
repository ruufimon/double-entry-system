# Ledger Admin UI

Standalone Angular 22 administration console for the Scalatra banking API.
Account browsing is part of the demo console. Interest-rate settings require
HTTP Basic authentication using the fixed `admin` username and the API's
`ADMIN_PASSWORD` environment variable.

Operators can browse and filter accounts, inspect account activity, and configure
the global annual interest rate. A new rate takes effect at the beginning of
the next Bangkok calendar month. The browser keeps the password in memory only
for the current rate-settings page session; serve the admin UI and API over
HTTPS outside local development.

## Development

Start the API from the repository root with an admin password:

```bash
ADMIN_PASSWORD=choose-a-local-password sbt run
```

Then run:

```bash
cd admin-ui
bun install
bun run start --host localhost --port 4300
```

Open `http://localhost:4300`. The development proxy forwards `/api/*` to the
API at `http://localhost:8080`.

## Verification

```bash
bun run test
bun run build
bunx playwright install chromium
bun run e2e
```

The Playwright configuration starts both the API and admin development server
when they are not already running.
