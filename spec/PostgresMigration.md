# Postgres for Pre-production and Production

Status: planned

## Scope

| Environment | Database |
|-------------|----------|
| Local dev (`sbt`, `docker compose up`) | H2 (default, no config needed) |
| CI | H2 and Postgres (Testcontainers) |
| Railway `pre-production` | Postgres |
| Railway `production` | Postgres |

The app uses Postgres when the database env vars are set and falls back to H2
when they are not. Existing H2 data does not carry over; Postgres starts empty.

Out of scope: API changes, holds, value dates, and other items from the core
banking standards study. Retiring `render.yaml` is a later decision.

## Work

- **Backend**: Postgres driver, config-driven `Database.scala`, Flyway
  migrations that run on both H2 and Postgres, repository SQL that works on
  both. Ledger rules unchanged: append-only journal, posting and funds check in
  one DB transaction, idempotency by unique transaction id.
- **DevOps**: Postgres in CI, Railway env vars per environment, native image
  metadata for both drivers, optional Postgres profile in `docker-compose.yml`.
- **QA**: full Scala suite on H2 and Postgres; concurrency tests on Postgres;
  Playwright on both customer UIs and the admin console against pre-production.
- **Frontend**: no changes expected; smoke-test UIs on pre-production.

## Acceptance Criteria

1. With no database env vars, the app starts on H2 exactly as today.
2. With the env vars set, the app starts on Postgres and Flyway creates the schema.
3. The full Scala suite passes on both H2 and Postgres in CI.
4. Every ledger transaction balances, a replayed idempotency key posts once,
   and parallel transfers or debits on one account never overdraw it or leave
   a partial posting (Postgres).
5. Reversals and bill-payment compensation behave as in [LedgerFlow.md](LedgerFlow.md).
6. The API contract is unchanged: amounts, ids and timestamps keep their JSON format.
7. The native image starts against Postgres on Railway `pre-production`.
8. Data survives a service restart in `pre-production`.
   In `pre-production` and `production` (`RAILWAY_ENVIRONMENT` or `DB_REQUIRE_POSTGRES=true` set), the app
   refuses to start without Postgres config instead of falling back to H2.
9. Playwright passes on the Angular and Elm UIs against `pre-production`.

## Release Order

1. Backend branch, built on the balance-adjustment work.
2. QA go or no-go on the branch.
3. DevOps deploys to `pre-production`, smoke test, criteria 7 to 9.
4. Production only after Twin's go.
