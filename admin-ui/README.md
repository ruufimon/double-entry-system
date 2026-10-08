# Ledger Admin UI

Standalone, read-only Angular 22 administration console for the Scalatra
banking API. This demo intentionally has no authentication and must not be
exposed to untrusted networks.

Operators can browse and filter all accounts or open a specific account
directly by entering its exact account ID.

## Development

Start the API from the repository root with `sbt run`. Then run:

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
