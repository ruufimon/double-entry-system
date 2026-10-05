# Double-Entry Banking System

A small full-stack banking application built with Scala, Scalatra, and Angular.
It demonstrates account deposits, withdrawals, and event-driven bill payments
backed by an immutable double-entry ledger.

This project is an educational implementation. All accounts, ledger entries,
payment processes, and audit records are currently held in memory and are reset
when the backend restarts.

## Features

- Idempotent account creation with THB balances
- Deposits and withdrawals with amount and balance validation
- Immutable, balanced debit and credit ledger entries
- Account balances derived from ledger transactions
- Customer-facing account activity history
- Two-step bill payment inquiry and confirmation
- Internal message bus for account charging, settlement, reversal, and auditing
- Angular account dashboard and operation flows
- Scala unit and HTTP tests, Angular unit tests, and Playwright E2E coverage

## Architecture

Business operations use intent-oriented names such as `deposit`, `withdraw`, and
`bill payment`. The ledger translates those operations into balanced debit and
credit entries. It is the source of truth for account balances; completed
transactions are never edited, and corrections are represented by reversal
transactions.

Bill payment is asynchronous after confirmation. The bill-payment domain emits
an account-charge request to the internal message bus, the banking domain posts
the ledger transaction, and the payment process then settles with the biller.
Failed settlement creates an inverse ledger transaction.

See [spec/LedgerFlow.md](spec/LedgerFlow.md) for the detailed flow diagram.

## Technology

- Scala 3.3.8
- Scalatra 3.2.1 and Jetty 12
- sbt 2.0.9
- Angular 22
- Vitest and ScalaTest
- Playwright with Chromium

## Prerequisites

- JDK 17 or newer
- sbt
- Node.js and npm

## Run Locally

Start the backend from the repository root:

```bash
sbt run
```

The API listens on `http://localhost:8080`. Set the `PORT` environment variable
to use a different backend port.

In another terminal, install and start the Angular application:

```bash
cd ui
npm install
npm start
```

Open `http://localhost:4200`. During development, Angular proxies requests under
`/api` to the backend on port 8080.

## API

| Method | Endpoint | Description |
| --- | --- | --- |
| `GET` | `/ping` | Backend health check |
| `PUT` | `/accounts/:accountId` | Create an account, or return the existing account |
| `GET` | `/accounts/:accountId/balance` | Get the ledger-derived balance |
| `GET` | `/accounts/:accountId/activities` | List customer-facing ledger activity |
| `POST` | `/accounts/:accountId/deposits` | Deposit funds |
| `POST` | `/accounts/:accountId/withdrawals` | Withdraw funds |
| `POST` | `/accounts/:accountId/bill-payments/inquiries` | Retrieve the current bill debt |
| `POST` | `/accounts/:accountId/bill-payments/:inquiryId/confirm` | Start payment processing |
| `GET` | `/accounts/:accountId/bill-payments/:paymentId` | Read payment status |

Account IDs may contain 1–64 letters, numbers, underscores, or hyphens. Monetary
amounts must be positive and have no more than two decimal places.

### Account example

```bash
curl -X PUT http://localhost:8080/accounts/account-123 \
  -H 'Content-Type: application/json' \
  -d '{}'

curl -X POST http://localhost:8080/accounts/account-123/deposits \
  -H 'Content-Type: application/json' \
  -d '{"amount":200.00}'

curl -X POST http://localhost:8080/accounts/account-123/withdrawals \
  -H 'Content-Type: application/json' \
  -d '{"amount":25.00}'

curl http://localhost:8080/accounts/account-123/balance
curl http://localhost:8080/accounts/account-123/activities
```

### Bill-payment example

The in-memory demo biller exposes one bill:

- Biller code: `demo-biller`
- Reference code 1: `customer-001`
- Reference code 2: `invoice-001`
- Debt: THB 100.00

Fund the account with at least THB 100, then inquire:

```bash
curl -X POST \
  http://localhost:8080/accounts/account-123/bill-payments/inquiries \
  -H 'Content-Type: application/json' \
  -d '{
    "billerCode":"demo-biller",
    "referenceCode1":"customer-001",
    "referenceCode2":"invoice-001"
  }'
```

Use the returned `inquiryId` to confirm the payment. Confirmation returns HTTP
202 and a `paymentId`:

```bash
curl -X POST \
  http://localhost:8080/accounts/account-123/bill-payments/INQUIRY_ID/confirm \
  -H 'Content-Type: application/json' \
  -d '{}'

curl \
  http://localhost:8080/accounts/account-123/bill-payments/PAYMENT_ID
```

## Tests

Run the backend test suite:

```bash
sbt test
```

Run Angular unit tests and a production build:

```bash
cd ui
npm test
npm run build
```

Install Playwright's Chromium binary once, then run the full-stack E2E test:

```bash
cd ui
npx playwright install chromium
npm run e2e
```

Playwright starts the backend and frontend automatically when they are not
already running.

## Deploy

The repository includes a [Render Blueprint](render.yaml) with two services:

- `banking-api`: the Scalatra API, built from the [`Dockerfile`](Dockerfile)
- `banking-ui`: the Angular build, served as a static site. It rewrites `/api/*`
  to the API and all other paths to `index.html`.

To deploy, open the Render dashboard, choose **New → Blueprint**, and select
this repository. Both services redeploy on every push to `main`. If Render
assigns the API a URL other than `https://banking-api.onrender.com`, update the
`/api/*` rewrite in `render.yaml`.

Because all data is held in memory, run the API as a single instance and expect
every deploy or restart to reset accounts and ledger entries.

To build and run the API image locally:

```bash
docker build -t banking-api .
docker run --rm -p 8080:8080 banking-api
```

## Project Layout

```text
src/main/scala/com/example/banking/      Account, ledger, messaging, and HTTP code
src/main/scala/com/example/billpayment/  Bill-payment workflow and biller adapter
src/test/scala/                          Backend tests
ui/                                     Angular application and Playwright tests
spec/                                   Architecture documentation
```

## License

Licensed under the [GNU General Public License v3.0](LICENSE).
