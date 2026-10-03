# Ledger Banking UI

Angular 22 web interface for the Scalatra banking API.

## Development

Start the Scalatra API from the repository root:

```bash
sbt run
```

Then start Angular in another terminal:

```bash
cd ui
npm install
npm start
```

Open `http://localhost:4200`. The Angular development server rewrites `/api/*`
and proxies it to `http://localhost:8080`, so no local CORS configuration is needed
and browser routes under `/accounts/*` remain owned by Angular.

Entering a valid account ID calls the idempotent account-creation API before
opening the dashboard. A new account starts at THB 0 with an empty activity list.

The demo bill uses:

- Biller: `demo-biller`
- Reference 1: `customer-001`
- Reference 2: `invoice-001`

Deposit at least THB 100 before confirming that bill.

## Verification

```bash
npm test
npm run build
```
