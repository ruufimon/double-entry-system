# Ledger Banking UI

Angular 22 web interface for the Scalatra banking API.

## Development

Start the Scalatra API from the repository root:

```bash
sbt run
```

Then start Angular in another terminal. Dependencies are managed with
[Bun](https://bun.sh); the Angular CLI still runs on Node.js 22 or newer.

```bash
cd ui
bun install
bun run start
```

Open `http://localhost:4200`. The Angular development server rewrites `/api/*`
and proxies it to `http://localhost:8080`, so no local CORS configuration is needed
and browser routes under `/accounts/*` remain owned by Angular.

The home page offers the seeded `acc123` and `acc456` demo accounts. Both start
with THB 100.00 and an opening deposit in their activity history; the customer
UI does not create accounts.

The demo bill uses:

- Biller: `demo-biller`
- Reference 1: `customer-001`
- Reference 2: `invoice-001`

Deposit at least THB 100 before confirming that bill.

## Verification

```bash
bun run test
bun run build
```

Use `bun run test`, not `bun test`: the latter runs Bun's own test runner
instead of the Angular (Vitest) tests.

Install the Chromium browser used by Playwright once:

```bash
bunx playwright install chromium
```

Then run the full-stack end-to-end test. Playwright starts both the Scalatra API
and Angular development server when they are not already running:

```bash
bun run e2e
```
