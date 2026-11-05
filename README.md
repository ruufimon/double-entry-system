# Double-Entry Banking API

Scala 3 / Scalatra HTTP API for customer accounts. Balances are the sum of immutable double-entry ledger rows in H2 or PostgreSQL. The process also runs bill payments, admin balance adjustments, and monthly interest.

Stack from `build.sbt` and `project/build.properties`: Scala 3.3.8, Scalatra 3.2.1, Jetty 12.1.13, Cats 2.13.0, json4s 4.1.1, sbt 2.0.9. CI uses JDK 21.

On startup the API opens `acc123` and `acc456` if they are missing and posts a THB 100.00 deposit for each. The deposit id is a name-based UUID of `demo-account-opening:<accountId>`. A repeat of that id is ignored, so a restart does not credit the THB 100.00 again.

## Ledger rules

Every posted transaction is append-only. `LedgerTransaction.create` rejects a transaction unless all of the following hold:

- At least two entries, and debits equal credits.
- Every entry is THB.
- A transfer has exactly two entries, both customer accounts, and the accounts differ.
- Any other operation has exactly one customer entry.
- An interest credit is a customer credit paired with an `InterestExpense` debit.
- An adjustment includes the `Adjustments` account. Only an adjustment may set `adjustsTransactionId`.
- A bill-payment reversal references the original transaction. Only a reversal may set that reference. `reverses_transaction_id` is unique.

Customer balance is `SUM(credits) - SUM(debits)` on that account's ledger entries. The `accounts` table stores the id and open time only. A debit that would make the balance negative is rejected and nothing is inserted.

| Operation | Customer entry | Other entry |
| --- | --- | --- |
| Deposit | Credit | Debit `Cash` |
| Withdrawal | Debit | Credit `Cash` |
| Transfer | Debit source, credit destination | none |
| Bill payment | Debit | Credit `BillerClearing` |
| Bill-payment reversal | Opposite of the original entries | Opposite of the original entries |
| Interest credit | Credit | Debit `InterestExpense` |
| Adjustment credit | Credit | Debit `Adjustments` |
| Adjustment debit | Debit | Credit `Adjustments` |

Activity is ordered by `occurred_at`, then `sequence_no`. `balanceAfter` is the running customer balance. `GET /accounts/:accountId/overview` reads the balance and that activity list on one connection.

An activity's `status` is `reversed` when some transaction's `reverses_transaction_id` points at it, and `posted` otherwise. An adjustment does not change that status. `originalTransactionId` is that activity's `reverses_transaction_id`, or its `adjusts_transaction_id` when it is not a reversal. The field is omitted when both are absent.

### Idempotency

`PUT /accounts/:accountId` inserts the account row. A second call for the same id returns HTTP 200 and does not insert another row. The response `balance` is always `0` on this route, including when the account already has ledger activity. Read `/balance` or `/overview` for the ledger balance.

Deposits, withdrawals, and transfers each allocate a new random UUID and post a new transaction. Repeating the HTTP call posts again. Those routes do not read an idempotency key.

The ledger rejects a second insert of the same `transaction_id` with `duplicate_ledger_transaction`.

Admin adjustments require `Idempotency-Key` to be a canonical lowercase UUID. That UUID is the ledger transaction id. Sending the same key again returns HTTP 409 `duplicate_ledger_transaction` and does not return the original 201 body.

A bill-payment charge uses the payment id as its ledger transaction id. The in-process handler keeps the first outcome for that id and does not post it twice in the same process. A reversal id is a name-based UUID of `bill-payment-reversal:<paymentId>`. A second reversal of the same original returns `ledger_transaction_already_reversed`.

Monthly interest uses a name-based UUID of `monthly-interest:<accountId>:<yyyy-MM>`. `interest_credit_periods` has one row per account and month, so a completed month is credited at most once.

### Transfers

Both accounts must already exist and must differ. The debit and credit are one ledger transaction in one database transaction. The two account rows are locked in account-id order. If the source balance is too small, the destination is unchanged.

Customer activity uses the same transaction id on both accounts: `transfer_out` on the source and `transfer_in` on the destination. `counterpartyAccountId` is the other account.

### Bill payments and reversals

There is no public reverse endpoint. Reversals are posted only when bill-payment settlement fails.

Inquiry does not move money and does not require the account to exist. The quote expires five minutes after it is stored. Confirm is accepted only for the account that inquired, and only while the inquiry is pending and unexpired. Confirm returns HTTP 202 with `awaiting_account_charge` and publishes a charge on an in-process single-thread bus. Poll `GET .../bill-payments/:paymentId` for the later status.

The charge debits the customer and credits `BillerClearing`. If the account is missing or the balance is too small, the payment becomes `failed`, no ledger row is written, and the inquiry returns to pending so it can be confirmed again until it expires.

After a successful charge the process settles with the biller. Success stores a receipt and completes the inquiry. Settlement failure posts the inverse ledger transaction, marks the original activity `reversed`, sets the payment to `failed`, and releases the inquiry. If that reversal cannot be posted, the status is `manual_review`.

Payment `status` values: `awaiting_account_charge`, `settling`, `reversing`, `completed`, `failed`, `manual_review`.

With no `BILLER_URL`, the API uses an in-process biller with one unpaid bill: biller `demo-biller`, reference 1 `customer-001`, reference 2 `invoice-001`, debt THB 100.00. That bill can be paid once per process. `sbt runBiller` starts a separate HTTP biller on port 8090 (`BILLER_PORT`) with the same `invoice-001` bill plus `invoice-002` (25.00), `invoice-decline` (40.00), `invoice-error` (40.00), `invoice-timeout` (40.00), and `invoice-slow` (60.00, 3 second delay). Point the API at it with `BILLER_URL`.

### Interest and adjustments

The interest clock is `Asia/Bangkok`. If `interest_rate_history` is empty, startup inserts a 0% annual rate effective on the first day of the current Bangkok month, recorded as `System default`.

`PUT /admin/interest-rate` appends a rate that becomes effective on the first day of next month. It does not change the current month. A rate is zero or positive and has at most two decimal places.

A scheduler runs at startup and then once a day at the next Bangkok midnight. It posts interest for each completed month not yet processed. For each account, the amount is the sum of that month's positive daily closing balances, times the annual percent, divided by 100 and by 365 (366 in a leap year), rounded half up to two decimal places. A result of zero or less is not posted.

An adjustment is a new balanced transaction against `Adjustments`. Optional `adjustsTransactionId` must be an existing transaction that includes this customer account. The original transaction stays in place.

Completed deposits, withdrawals, transfers (both accounts), successful bill payments, and adjustments are appended to `audit_log`. There is no HTTP route for that log.

## Build, run, and test

JDK 21 and sbt 2.0.9. From the repository root:

```bash
sbt compile
sbt test
sbt run
```

In sbt 2, `sbt test` runs the ScalaTest tests that have not passed yet or whose dependencies changed. `sbt testFull` runs every test. The suite includes PostgreSQL tests that start a `postgres:17-alpine` Testcontainers container, so those tests need a running Docker daemon.

`sbt run` listens on `http://localhost:8080` unless `PORT` is set. `sbt runBiller` starts the fake HTTP biller.

## Configuration

Local development uses H2. Pre-production and production run on PostgreSQL. The rollout plan and acceptance criteria are in [spec/PostgresMigration.md](spec/PostgresMigration.md).

PostgreSQL is selected when `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, and `PGPASSWORD` are all set and `PGPORT` is an integer. Otherwise a `DATABASE_URL` of `postgresql://` or `postgres://` with a user, a password, a host, and a database name is used. Its query string is copied into the JDBC URL. A missing port becomes 5432. `PG*` wins over `DATABASE_URL`. An incomplete `PG*` set falls through to `DATABASE_URL`.

If neither is usable, the API uses H2. `DB_URL` is the JDBC URL as given, not a filesystem path. User and password default to `banking` / `banking` (`DB_USER`, `DB_PASSWORD`). When `DB_URL` is unset the URL is `jdbc:h2:mem:banking-<uuid>;DB_CLOSE_DELAY=-1`, which is empty after restart.

```bash
# PostgreSQL from discrete variables
PGHOST=localhost PGPORT=5432 PGDATABASE=banking PGUSER=postgres PGPASSWORD=secret sbt run

# PostgreSQL from a URL
DATABASE_URL=postgresql://user:password@localhost:5432/banking sbt run

# H2 file
DB_URL='jdbc:h2:file:/path/to/banking;DB_CLOSE_DELAY=-1' sbt run
```

`docker compose up` runs the API, customer UI, and admin UI on H2. `docker compose --profile postgres up` also starts a `postgres:17-alpine` service, but the API only uses it when `DATABASE_URL` points at it:

```bash
ADMIN_PASSWORD=secret \
DATABASE_URL=postgresql://banking_user:banking_password@postgres:5432/banking \
DB_REQUIRE_POSTGRES=true \
docker compose --profile postgres up --build
```

`banking_password` is the default `POSTGRES_PASSWORD` in `docker-compose.yml`; change both together. `DB_REQUIRE_POSTGRES=true` makes the API fail at startup instead of silently falling back to H2 if the URL is wrong.

Startup prints `Database: PostgreSQL at <host>/<database> (user: ...)` or `Database: H2 in-memory (<name>)` or `Database: H2 file-based (<name>)`.

`RAILWAY_ENVIRONMENT` or `DB_REQUIRE_POSTGRES=true` refuses to start unless PostgreSQL configuration is present. `DB_POOL_SIZE` is the Hikari maximum pool size (default 10, must be a positive integer).

`METRICS_PORT` (default 9090) is a second listener that serves only `GET /metrics`, the Prometheus text exposition. It is not reachable through the UIs, which proxy `/api/*` to `PORT`, and `PORT` has no `/metrics`. See [Monitoring](#monitoring).

`ADMIN_TOKEN` is the bearer token for adjustment routes. If it is unset, those routes return 401 and the rest of the API still starts. `ADMIN_PASSWORD` is the HTTP Basic password for interest routes; the username is `admin`. If it is unset or empty, interest routes return 503.

Neither credential is baked into a UI build. The admin console asks for the admin password on the interest-rate page and keeps it only in memory while that page is open; it is never written to `localStorage` or `sessionStorage`. The adjustment routes are called with the bearer token directly, as in the examples below.

`BILLER_URL` selects the HTTP biller. `BILLER_TIMEOUT_MS` is the HTTP client timeout (default 5000). `BILLER_PORT` (default 8090) and `BILLER_LATENCY_MS` (default 0) apply to `sbt runBiller` only.

Flyway applies `src/main/resources/db/migration/V1__initial_schema.sql` and `V2__interest_credit_periods.sql` before the pool opens.

`V1__initial_schema.sql` was edited when PostgreSQL support and the interest tables were merged. Flyway validates checksums, so an H2 file database created from the earlier V1 fails at startup with a checksum mismatch. Delete the old `.mv.db` file named in `DB_URL` and start again; the schema is recreated from V1 and V2.

## API

JSON routes use `Content-Type: application/json`. Error bodies are `{"error":"<code>","message":"<text>"}`. An inquiry that fails validation also returns `details`, a list of `{error, message}`. Unknown paths return `{"error":"not_found","message":"No such route"}`. Unhandled failures return `{"error":"internal_error","message":"An unexpected error occurred"}`.

Account ids are 1–64 characters from `[A-Za-z0-9_-]`, after trimming. Money amounts are greater than zero and have at most two decimal places. Currency is THB.

| Method | Path | Success | Auth |
| --- | --- | --- | --- |
| `GET` | `/ping` | 200 `text/plain` `pong` | none |
| `PUT` | `/accounts/:accountId` | 201 created, 200 already open | none |
| `GET` | `/accounts/:accountId/overview` | 200 | none |
| `GET` | `/accounts/:accountId/balance` | 200 | none |
| `GET` | `/accounts/:accountId/activities` | 200 | none |
| `POST` | `/accounts/:accountId/deposits` | 200 | none |
| `POST` | `/accounts/:accountId/withdrawals` | 200 | none |
| `POST` | `/accounts/:accountId/transfers` | 200 | none |
| `POST` | `/accounts/:accountId/bill-payments/inquiries` | 200 | none |
| `POST` | `/accounts/:accountId/bill-payments/:inquiryId/confirm` | 202 | none |
| `GET` | `/accounts/:accountId/bill-payments/:paymentId` | 200 | none |
| `GET` | `/admin/accounts` | 200 | none |
| `GET` | `/admin/accounts/:accountId` | 200 | none |
| `POST` | `/admin/accounts/:accountId/adjustments` | 201 | bearer `ADMIN_TOKEN` |
| `GET` | `/admin/accounts/:accountId/adjustments` | 200 | bearer `ADMIN_TOKEN` |
| `GET` | `/admin/interest-rate` | 200 | Basic `admin` / `ADMIN_PASSWORD` |
| `PUT` | `/admin/interest-rate` | 200 | Basic `admin` / `ADMIN_PASSWORD` |

Frequent error statuses: 400 invalid input, 401 admin auth, 404 missing account, bill, inquiry, payment, or adjusted transaction, 409 insufficient funds, duplicate ledger id, transaction already reversed, bill already paid, or inquiry in progress or completed, 410 expired inquiry, 503 interest admin password not configured. A bill-payment charge failure is reported on the payment resource as `failed`, not as the confirm response.

### Accounts, deposits, withdrawals

```bash
curl -X PUT http://localhost:8080/accounts/account-123 \
  -H 'Content-Type: application/json' \
  -d '{}'
```

```json
{"accountId":"account-123","currency":"THB","balance":0,"status":"active"}
```

```bash
curl -X POST http://localhost:8080/accounts/account-123/deposits \
  -H 'Content-Type: application/json' \
  -d '{"amount":200.00}'
```

```json
{"accountId":"account-123","balance":200.00}
```

```bash
curl -X POST http://localhost:8080/accounts/account-123/withdrawals \
  -H 'Content-Type: application/json' \
  -d '{"amount":25.00}'
```

```json
{"accountId":"account-123","balance":175.00}
```

```bash
curl http://localhost:8080/accounts/account-123/balance
```

```json
{"accountId":"account-123","currency":"THB","balance":175.00}
```

```bash
curl http://localhost:8080/accounts/account-123/activities
```

```json
[
  {
    "transactionId":"0f0c6b2e-7c2a-4e0a-9a1e-6b5d0a9c1f10",
    "operation":"deposit",
    "effect":"increase",
    "amount":200.00,
    "currency":"THB",
    "balanceAfter":200.00,
    "occurredAt":"2026-11-03T13:00:00Z",
    "status":"posted"
  },
  {
    "transactionId":"6a1d0c44-1b2e-4f7a-8c90-2d5e7b1a3c11",
    "operation":"withdrawal",
    "effect":"decrease",
    "amount":25.00,
    "currency":"THB",
    "balanceAfter":175.00,
    "occurredAt":"2026-11-03T13:01:00Z",
    "status":"posted"
  }
]
```

`GET /accounts/:accountId/overview` returns `accountId`, `currency`, `balance`, and the same `activities` array. Transaction ids and timestamps above are examples; the server generates them.

A zero or negative amount, or more than two decimal places, returns 400, for example `{"error":"invalid_deposit_amount","message":"Amount must be greater than zero"}`. An unknown account returns 404 `account_not_found`. A withdrawal larger than the balance returns 409:

```json
{"error":"insufficient_funds","message":"Available balance is 10.00; requested amount is 10.01"}
```

`operation` in activity is `deposit`, `withdrawal`, `transfer_out`, `transfer_in`, `bill_payment`, `bill_payment_reversal`, `interest_credit`, or `adjustment`. `effect` is `increase` or `decrease`. Absent optional fields are omitted: `originalTransactionId` is set on a reversal or an adjustment that names a transaction, and `counterpartyAccountId` is set on transfers.

### Transfers

```bash
curl -X PUT http://localhost:8080/accounts/recipient-123 \
  -H 'Content-Type: application/json' -d '{}'

curl -X POST http://localhost:8080/accounts/account-123/transfers \
  -H 'Content-Type: application/json' \
  -d '{"destinationAccountId":"recipient-123","amount":25.50}'
```

```json
{
  "transferId":"b7e1c0a2-4d55-4a1e-9f20-1c2b3d4e5f60",
  "sourceAccountId":"account-123",
  "destinationAccountId":"recipient-123",
  "amount":25.50,
  "currency":"THB",
  "sourceBalance":149.50,
  "occurredAt":"2026-11-03T13:02:00Z"
}
```

The same account on both sides returns 400 `same_account_transfer`. A missing destination returns 404 `account_not_found`.

### Bill payments

The completed example below continues from the transfer: the source balance is 149.50, and paying 100.00 leaves 49.50.

```bash
curl -X POST http://localhost:8080/accounts/account-123/bill-payments/inquiries \
  -H 'Content-Type: application/json' \
  -d '{"billerCode":"demo-biller","referenceCode1":"customer-001","referenceCode2":"invoice-001"}'
```

```json
{
  "inquiryId":"3d1c2b4a-5e6f-4708-91ab-0c1d2e3f4a5b",
  "billerCode":"demo-biller",
  "referenceCode1":"customer-001",
  "referenceCode2":"invoice-001",
  "currentDebt":100.00,
  "expiresAt":"2026-11-03T13:07:00Z"
}
```

```bash
curl -X POST \
  http://localhost:8080/accounts/account-123/bill-payments/3d1c2b4a-5e6f-4708-91ab-0c1d2e3f4a5b/confirm \
  -H 'Content-Type: application/json' \
  -d '{}'
```

```json
{
  "paymentId":"9a8b7c6d-5e4f-4321-80ab-cdef12345678",
  "inquiryId":"3d1c2b4a-5e6f-4708-91ab-0c1d2e3f4a5b",
  "accountId":"account-123",
  "status":"awaiting_account_charge"
}
```

```bash
curl http://localhost:8080/accounts/account-123/bill-payments/9a8b7c6d-5e4f-4321-80ab-cdef12345678
```

```json
{
  "paymentId":"9a8b7c6d-5e4f-4321-80ab-cdef12345678",
  "inquiryId":"3d1c2b4a-5e6f-4708-91ab-0c1d2e3f4a5b",
  "accountId":"account-123",
  "billerCode":"demo-biller",
  "amount":100.00,
  "currency":"THB",
  "status":"completed",
  "resultingBalance":49.50,
  "billerReceiptCode":"demo-biller-9a8b7c6d-5e4f-4321-80ab-cdef12345678",
  "paidAt":"2026-11-03T13:02:01Z"
}
```

`resultingBalance`, `billerReceiptCode`, `paidAt`, and `failure` are omitted until the process sets them. On failure, `failure` is `{"error":"<code>","message":"<text>"}`. The in-process receipt code is `<billerCode>-<paymentId>`.

Unknown bill references return 404 `bill_not_found`. An unknown biller returns 404 `biller_not_found`. A paid bill returns 409 `bill_not_payable` from inquiry. Confirming a missing, expired, in-progress, or completed inquiry returns 404 `bill_payment_inquiry_not_found`, 410 `bill_payment_inquiry_expired`, 409 `bill_payment_in_progress`, or 409 `bill_payment_already_completed`. Invalid inquiry fields return 400 `invalid_bill_payment_inquiry` with one `details` entry per field.

### Admin

`GET /admin/accounts` returns every account, ordered by id:

```json
{
  "accountId":"acc123",
  "currency":"THB",
  "status":"active",
  "balance":100.00,
  "activityCount":1,
  "lastActivityAt":"2026-11-03T13:00:00Z"
}
```

`GET /admin/accounts/:accountId` adds `activities` in the customer activity shape. `status` in this payload is the string `active`.

Adjustments need `Authorization: Bearer <ADMIN_TOKEN>`, `X-Admin-Actor` (1–128 characters), and `Idempotency-Key` (canonical lowercase UUID). `reason` is required and at most 500 characters. `direction` is `credit` or `debit`. The body below is a debit of 2.50 after the bill payment, so the balance goes from 49.50 to 47.00.

```bash
curl -X POST http://localhost:8080/admin/accounts/account-123/adjustments \
  -H 'Authorization: Bearer <ADMIN_TOKEN>' \
  -H 'X-Admin-Actor: ops-alice' \
  -H 'Idempotency-Key: 11111111-1111-4111-8111-111111111111' \
  -H 'Content-Type: application/json' \
  -d '{"direction":"debit","amount":2.50,"reason":"Fee correction","adjustsTransactionId":"0f0c6b2e-7c2a-4e0a-9a1e-6b5d0a9c1f10"}'
```

```json
{
  "adjustmentId":"11111111-1111-4111-8111-111111111111",
  "accountId":"account-123",
  "direction":"debit",
  "amount":2.50,
  "currency":"THB",
  "reason":"Fee correction",
  "actor":"ops-alice",
  "adjustsTransactionId":"0f0c6b2e-7c2a-4e0a-9a1e-6b5d0a9c1f10",
  "balanceAfter":47.00,
  "occurredAt":"2026-11-03T13:03:00Z"
}
```

`GET /admin/accounts/:accountId/adjustments` returns the same objects. `adjustsTransactionId` is omitted when the request omits it. A missing bearer token returns 401 `unauthorized`. A missing actor returns 400 `missing_admin_actor`. A non-UUID key returns 400 `invalid_idempotency_key`.

Interest routes use Basic auth. A missing password configuration returns 503 `admin_auth_not_configured`. A wrong password returns 401 `admin_auth_required` and `WWW-Authenticate: Basic realm="Ledger Admin"`.

```bash
curl -u admin:<ADMIN_PASSWORD> http://localhost:8080/admin/interest-rate

curl -X PUT http://localhost:8080/admin/interest-rate \
  -u admin:<ADMIN_PASSWORD> \
  -H 'Content-Type: application/json' \
  -d '{"rate":3.65}'
```

```json
{
  "currentRate":0.00,
  "currentEffectiveFrom":"2026-11-01",
  "nextEffectiveFrom":"2026-12-01",
  "scheduledRate":3.65,
  "scheduledEffectiveFrom":"2026-12-01",
  "history":[
    {
      "rate":3.65,
      "effectiveFrom":"2026-12-01",
      "createdAt":"2026-11-03T13:04:00Z",
      "updatedBy":"admin",
      "status":"scheduled"
    },
    {
      "rate":0.00,
      "effectiveFrom":"2026-11-01",
      "createdAt":"2026-11-03T13:00:00Z",
      "updatedBy":"System default",
      "status":"current"
    }
  ]
}
```

`history` is newest first. `status` is `current`, `scheduled`, or `superseded`. Before any `PUT`, `scheduledRate` and `scheduledEffectiveFrom` are omitted and history contains only the default row. The dates above are for a change made in November 2026: `currentEffectiveFrom` is the first of the current Bangkok month, and a `PUT` schedules the first of the next month.

## Monitoring

The API records every request on `PORT` into `http_server_requests_seconds`, a Prometheus histogram with buckets at 25, 50, 100, 200, 500 ms and 1, 2, 5 s. Labels are bounded by design: `route` is the documented template from the API table (`/accounts/:accountId/deposits`), never the real path; `class` is `read`, `money`, `bill`, `probe` or `other`; `outcome` is `success`, `rejected`, `dependency`, `misconfigured` or `error`, decided from status and class as in [spec/MonitoringSLO.md](spec/MonitoringSLO.md). Unknown paths are `route="unmatched"`. Identifiers never become label values.

Also exposed: `hikaricp_connections_*{pool="banking"}` from the connection pool, and `banking_ledger_unbalanced_transactions`, recomputed every 60 seconds by a query that groups `ledger_entries` by transaction and counts those whose credits and debits do not cancel. It is `-1` until the first check and must otherwise be `0`; the check has a 2-second query timeout and reports `banking_ledger_check_duration_seconds{result="ok"|"skipped"|"failed"}`.

```bash
sbt run &
curl -s -X POST -H 'Content-Type: application/json' -d '{"amount":1.00}' localhost:8080/accounts/acc123/deposits
curl -s localhost:9090/metrics | grep -E 'class="money"|ledger_unbalanced'
```

The first SLO built on these metrics, its alert rules and dashboard are tracked in #9 (issues #16 to #23). In Kubernetes the metrics port is opened only to the `monitoring` namespace (`k8s/base/networkpolicy.yaml`).

### Native image metadata

`Dockerfile.native` compiles with the reflection and resource metadata in `src/main/resources/META-INF/native-image/com.example/scalatra-ping-api/reachability-metadata.json`. Regenerate it when routes, JSON models or libraries change. There is no GraalVM on the development machines, so run the tracing agent in the build image against the integration tests:

```bash
sbt -batch stage
docker run --rm -v "$PWD:/app" -w /app ghcr.io/graalvm/native-image-community:25 \
  java -agentlib:native-image-agent=config-merge-dir=src/main/resources/META-INF/native-image/com.example/scalatra-ping-api \
       -cp 'target/out/jvm/scala-*/scalatra-ping-api/universal/stage/lib/*' com.example.Server &
# exercise every route (the ServerIntegrationSpec requests are a good script), then stop the process
```

`config-merge-dir` adds to the existing file instead of replacing it. Libraries that ship their own metadata, such as Micrometer, need nothing here. The CI job `Native Image Smoke Test` boots the image against PostgreSQL, restarts it, and checks `/metrics`, so a missing entry fails there before it reaches a deploy.

## Project layout

```text
build.sbt
project/build.properties                         sbt 2.0.9
src/main/scala/com/example/Server.scala          Jetty entry point
src/main/scala/com/example/PingServlet.scala
src/main/scala/com/example/banking/               accounts, ledger, interest, admin HTTP
src/main/scala/com/example/billpayment/          inquiry, confirm, settlement, reversal
src/main/scala/com/example/biller/               standalone fake biller
src/main/scala/com/example/infrastructure/       JDBC URL selection, pool, Flyway
src/main/scala/com/example/observability/        request metrics, /metrics, ledger balance check
src/main/resources/db/migration/                 V1 and V2 SQL
src/test/scala/                                  ScalaTest, including Testcontainers
spec/                                            extra design notes
ui/  admin-ui/  ui-elm/                          client applications
k8s/                                             cluster manifests; see k8s/README.md
```

## License

[GNU General Public License v3.0](LICENSE).
