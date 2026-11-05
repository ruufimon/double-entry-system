# Service Level Objectives

Status: proposed. First delivery slice of issue #9.

This document defines what "the API is healthy" means in numbers, which requests
count against those numbers, and what happens when the budget runs out. The
metric names at the end are the contract that slice 2 of #9 implements; the
dashboards and alerts in slice 4 are derived from this file.

## Principles

1. Measure from the customer's point of view. A request that returned a correct
   business rejection is a success for the SLO; a request that timed out is a
   failure even if the server would eventually have answered.
2. Correct money movement is a separate SLO from availability. A fast, available
   API that double-posts is broken.
3. Targets below are starting points. They are confirmed or changed after two
   weeks of production data and the load test in slice 4, not before.
4. Every SLO has an owner, a window, an error budget, and an alert that fires
   before the budget is gone.

## Service window

The cluster is turned off from 22:00 to 06:00 Asia/Bangkok
(`.github/workflows/cluster-schedule.yml`). Until it runs around the clock,
SLOs are evaluated over **operating hours only, 06:00 to 22:00**, so the nightly
shutdown is neither an outage nor a free pass. The alert rules carry a matching
mute window. When the schedule is removed, this section is deleted and the
budgets below scale to 24 hours.

Rolling window: **30 days** for availability and latency; **calendar month** for
the interest SLO.

## Endpoint classes

| Class | Routes | Why grouped |
|---|---|---|
| `read` | `GET /accounts/:id/overview`, `/balance`, `/activities`, `/bill-payments/:paymentId`; `GET /admin/accounts`, `/admin/accounts/:id`, `/admin/accounts/:id/adjustments`, `GET /admin/interest-rate` | Single database read, no side effects |
| `money` | `PUT /accounts/:id`; `POST .../deposits`, `.../withdrawals`, `.../transfers`; `POST /admin/accounts/:id/adjustments`; `PUT /admin/interest-rate` | One database transaction, no external call |
| `bill` | `POST .../bill-payments/inquiries`, `POST .../bill-payments/:inquiryId/confirm` | Depends on the external biller |
| `probe` | `/ping`, `/health/*`, `/metrics` | Excluded from every SLO |

Route labels use the template (`/accounts/:accountId/balance`), never the
resolved path.

## Error classification

The classification is by HTTP status first and by the `error` code in the body
second. It is the single source for the `outcome` label.

| Outcome | Status and code | Counts against availability |
|---|---|---|
| `success` | 2xx | no |
| `rejected` | 400 any, 404 any, 409 `insufficient_funds`, `bill_payment_in_progress`, `bill_payment_already_completed`, `duplicate_transaction`, `transaction_already_reversed`, 410 `bill_payment_inquiry_expired`, 401 `admin_auth_required` | no, tracked separately |
| `dependency` | 503 `biller_unavailable`, 502 `biller_settlement_failed` | **yes** for `bill`, and feeds the biller dependency SLI |
| `misconfigured` | 503 `admin_auth_not_configured` | yes |
| `error` | 500 any (`internal_error`, `bill_payment_compensation_failed`), any response without a JSON error body, connection reset, server-side timeout | yes |

Rules:

- A new error code is `error` until this table assigns it. Adding a code without
  updating this table fails code review.
- `rejected` responses still count toward the latency SLI. A slow rejection is
  still slow.
- 401 and 403 move to `rejected` for customer routes once #5 lands; a burst of
  them gets its own security alert, not an availability page.

## Service level indicators

Each SLI is a ratio of good events to total events, or a latency quantile,
computed from the metrics in the contract section.

| SLI | Definition |
|---|---|
| Availability per class | `success + rejected` divided by all requests in the class, excluding `probe` |
| Latency per class | fraction of requests in the class, all outcomes, answered within the threshold |
| Bill settlement time | time from the 202 on `confirm` to the process reaching `completed` or a terminal failure, measured by the process manager |
| Biller dependency | fraction of biller calls that return a parseable response within `BILLER_TIMEOUT_MS` |
| Ledger balance | per-transaction sum of debits minus credits, recomputed by a scheduled query; good when every transaction sums to zero |
| Handler health | internal message handlers that completed without throwing, divided by all dispatched messages |
| Stuck payments | bill-payment processes in `awaiting_account_charge` or `awaiting_biller_settlement` for longer than the stuck threshold |
| Interest credit | interest for month M posted to every eligible account by the deadline on day 1 of M+1 |

## Objectives

### Availability and latency

| Class | Availability | Latency threshold, fraction | Monthly budget at 16 h/day |
|---|---|---|---|
| `read` | 99.9% | 200 ms at p95, 500 ms at p99 | 28.8 min of failed time |
| `money` | 99.9% | 500 ms at p95, 1 s at p99 | 28.8 min |
| `bill` | 99.5% | inquiry 2 s at p95; confirm acceptance 500 ms at p95 | 2.4 h |

The `bill` target is looser because the biller is outside our control and
`dependency` counts against it. If the real biller later publishes its own
SLA, this target becomes "biller SLA minus 0.1 points".

Low-traffic routes (`/admin/interest-rate`, `PUT /accounts/:id`) are inside
their class so the ratio is meaningful. No SLO is evaluated on fewer than 100
requests in the window.

### Correctness

| SLO | Target | Measured how |
|---|---|---|
| Ledger balance | zero unbalanced transactions, ever | gauge from a `SELECT` over ledger entries grouped by transaction, every 60 s, both pods |
| Handler health | 100% of messages handled without failure, per operating day | `banking_bus_handler_failures_total` stays at zero |
| Stuck payments | zero processes stuck longer than 5 min; 99% of accepted payments settle within 10 s, 99.9% within 60 s | process manager timers plus a stuck-process gauge |
| Duplicate suppression | tracked, no target yet | adjustment replays counted by `outcome=replayed`; becomes an SLO with #4 and #6 |
| Interest credit | month M credited by 07:00 Asia/Bangkok on day 1 of M+1, 12 of 12 months | scheduler catches up on start, so the 06:00 cluster start is the real run time |

The ledger gauge borrows one pooled connection per run. With `DB_POOL_SIZE=4`
the query must finish inside 2 s or be skipped and counted, never queued behind
customer traffic.

### Biller dependency

Target 99.5% over 30 days. This is an indicator we report, not one we are
accountable for; it exists so a `bill` budget burn can be attributed to the
biller or to us in one glance.

## Error budget policy

The budget is the allowed failure time per 30-day window from the table above.

| Budget remaining | Action |
|---|---|
| above 50% | normal delivery |
| 25% to 50% | the next deploy to production needs a passing pre-production synthetic run and a named reviewer |
| below 25% | feature deploys to production stop for the class; only fixes and reverts ship until the budget is above 50% |
| exhausted | incident review within two working days; its action items are the next sprint's first work |

Owner: the backend lead for `read`, `money`, and correctness; the integration
owner for `bill` and the biller indicator. The owner decides on re-tiering at
the monthly review.

## Alerting on the budget

Alerts page on burn rate, not on raw error rate, so one slow minute does not
page and a slow bleed does.

| Severity | Condition | Windows |
|---|---|---|
| page | burning 14.4x the budget | 1 h long, 5 min short, both over threshold |
| page | burning 6x | 6 h long, 30 min short |
| ticket | burning 1x | 3 d long, 6 h short |
| page, immediate | ledger gauge not zero; handler failures increased; any stuck payment | single evaluation, no window |
| ticket | interest not credited by 07:00 on day 1 | daily check on day 1 |

All alerts are muted 22:00 to 06:00 and for 10 minutes after the 06:00 start
while pods pass their startup probe. Each alert annotation links to a runbook
under `docs/runbooks/`.

## Explicit exclusions

- Requests to `probe` routes.
- Pre-production. It has the same dashboards and alert rules at `ticket`
  severity so synthetic failure tests can be run there without paging.
- Planned maintenance announced in the deploy log at least one operating day
  ahead, capped at 30 minutes a month.
- Fake biller latency injected through `BILLER_LATENCY_MS` in pre-production.

## Metric contract

Names are Prometheus style, units in the name, labels bounded. These are the
only labels allowed on SLO metrics. Account, transfer, request, inquiry and
user identifiers never appear as label values.

| Metric | Type | Labels |
|---|---|---|
| `http_server_requests_seconds` | histogram | `route`, `method`, `status`, `outcome`, `class` |
| `banking_operations_total` | counter | `operation` (`open`, `deposit`, `withdraw`, `transfer`, `adjust`, `bill_inquiry`, `bill_confirm`, `interest_credit`), `outcome`, `error_code` |
| `banking_bill_settlement_seconds` | histogram | `result` (`completed`, `failed`, `compensated`) |
| `banking_bill_processes_stuck` | gauge | `state` |
| `banking_biller_calls_seconds` | histogram | `operation` (`inquire`, `settle`), `outcome` |
| `banking_ledger_unbalanced_transactions` | gauge | none |
| `banking_ledger_check_duration_seconds` | histogram | `result` (`ok`, `skipped`, `failed`) |
| `banking_bus_messages_total` | counter | `message_type`, `outcome` |
| `banking_bus_handler_failures_total` | counter | `handler` |
| `banking_bus_queue_depth` | gauge | none |
| `banking_interest_run_timestamp_seconds` | gauge | `result` |
| `hikaricp_connections_*` | from HikariCP's Micrometer tracker | `pool` |

Histogram buckets for request latency: 25, 50, 100, 200, 500 ms, 1, 2, 5 s.
Bill settlement buckets: 1, 2, 5, 10, 30, 60, 300 s.

Reserved for later issues, so dashboards can be prepared without renames:
`banking_idempotency_outcomes_total{operation,outcome}` (#4, #6),
`banking_transfer_limit_decisions_total{tier,decision,reason}` (#7),
`banking_auth_decisions_total{outcome,role}` (#5),
`banking_outbox_lag_seconds`, `banking_consumer_lag_messages` (#8).

## Example queries

Availability for `money`, 30 days:

```promql
sum(increase(http_server_requests_seconds_count{class="money",outcome=~"success|rejected"}[30d]))
/
sum(increase(http_server_requests_seconds_count{class="money"}[30d]))
```

Fast burn page for `read` (14.4x of a 99.9% budget is a 1.44% bad-request rate):

```promql
(
  1 - sum(rate(http_server_requests_seconds_count{class="read",outcome=~"success|rejected"}[1h]))
    / sum(rate(http_server_requests_seconds_count{class="read"}[1h]))
) > 0.0144
and
(
  1 - sum(rate(http_server_requests_seconds_count{class="read",outcome=~"success|rejected"}[5m]))
    / sum(rate(http_server_requests_seconds_count{class="read"}[5m]))
) > 0.0144
```

Latency SLI for `money`, fraction under 500 ms:

```promql
sum(rate(http_server_requests_seconds_bucket{class="money",le="0.5"}[30d]))
/
sum(rate(http_server_requests_seconds_count{class="money"}[30d]))
```

## Review

- First review two weeks after slice 2 ships, using real traffic, to set the
  latency thresholds from observed p95 plus headroom rather than the guesses
  above.
- Monthly thereafter: budget consumption, false pages, alerts that never fired.
- Any change to a target, a class, or the error table is a pull request to this
  file with the owner's review.

## Open questions

1. Confirm the monitoring backend (Grafana Cloud with Alloy is the proposal in
   #9) so the burn-rate rules can be written in its rule format.
2. Who owns the `bill` class once a real biller replaces the fake one.
3. Whether the overnight shutdown is temporary. If it stays past Q1 2027, the
   operating-hours window becomes permanent rather than an interim rule.
