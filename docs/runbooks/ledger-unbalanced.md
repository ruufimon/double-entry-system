# Ledger unbalanced

## Alerts

| Alert | Condition | Severity |
|---|---|---|
| `LedgerUnbalancedTransactions` | `banking_ledger_unbalanced_transactions > 0`, single evaluation | page, immediate |
| `LedgerCheckFailing` | `banking_ledger_check_duration_seconds{result=~"skipped\|failed"}` recorded more than 5 times in 10 min | ticket |

Every 60 s the API counts ledger transactions whose entries do not sum to zero
(credits positive, debits negative). The gauge is `-1` until the first check and must
otherwise be `0`. Any positive value means money was created or destroyed: a
correctness failure, not an availability one. The SLO is zero unbalanced
transactions, ever.

`LedgerCheckFailing` means the check itself is not running: the query hit its 2 s
timeout (`skipped`) or errored (`failed`), and the gauge is stuck at its last good
value. You are blind to imbalance until it is fixed.

## Impact

Balances shown to customers may be wrong, and every further posting may build on a
wrong balance. Treat as a financial incident.

## First 5 minutes (LedgerUnbalancedTransactions)

Set `ENV=prod` (or `preprod`) and the database ID:
prod `banking-prod-db` `b07ee325-822c-4f7b-8e5b-328493b34449`,
preprod `banking-preprod-db` `beeeace6-b957-40a9-9245-a9e2c9038b81`.

1. **Stop money movement.** There is no read-only mode or feature flag; scaling the
   API to zero is the only lever today. It also stops reads and the UIs' API calls.

   ```bash
   kubectl -n $ENV get deploy api -o jsonpath='{.spec.replicas}{"\n"}'   # note it (normally 1)
   kubectl -n $ENV scale deployment/api --replicas=0
   ```

   Announce the outage in the incident channel. Do not wait for the investigation to
   finish before doing this.

2. **Confirm the number** (before scaling, or from history after):

   ```bash
   kubectl -n monitoring port-forward svc/kube-prometheus-stack-prometheus 9090
   ```

   ```promql
   banking_ledger_unbalanced_transactions{env="prod"}
   ```

   Note when it went from 0 to positive; that bounds when the bad posting happened.

3. **Find the transaction(s).** Read-only, against the managed database:

   ```bash
   doctl databases connection b07ee325-822c-4f7b-8e5b-328493b34449 --format URI --no-header
   psql "<uri>"     # your IP must be in: doctl databases firewalls list <db-id>
   ```

   ```sql
   BEGIN TRANSACTION READ ONLY;

   SELECT e.transaction_id,
          t.operation,
          t.occurred_at,
          t.reverses_transaction_id,
          t.adjusts_transaction_id,
          SUM(CASE WHEN e.direction = 'Credit' THEN e.amount ELSE -e.amount END) AS imbalance,
          COUNT(*) AS entries
   FROM ledger_entries e
   JOIN ledger_transactions t ON t.transaction_id = e.transaction_id
   GROUP BY e.transaction_id, t.operation, t.occurred_at,
            t.reverses_transaction_id, t.adjusts_transaction_id
   HAVING SUM(CASE WHEN e.direction = 'Credit' THEN e.amount ELSE -e.amount END) <> 0
   ORDER BY t.occurred_at;

   -- entries of one transaction
   SELECT * FROM ledger_entries WHERE transaction_id = '<id>' ORDER BY entry_index;

   ROLLBACK;
   ```

   This is the query in `LedgerBalanceCheck.scala`, returning IDs instead of a count.
   Save the output in the incident notes.

4. **Last deploy:** `kubectl -n $ENV rollout history deployment/api`. A code change to
   posting logic shortly before the first bad transaction is the likely cause.

## Mitigation

- **Never edit, update or delete ledger rows.** The ledger is append-only. Corrections
  are made only with new reversal or adjustment transactions that balance, posted
  through the application, after the backend lead agrees what the correct entries are.
- If a deploy caused it, roll back before bringing the API back (k8s/README.md,
  "Rollback"):

  ```bash
  kubectl -n $ENV rollout undo deployment/api
  ```

  Then deploy the known-good commit normally.
- Bring the API back only once the cause is understood or rolled back:
  `kubectl -n $ENV scale deployment/api --replicas=1`.

## First 5 minutes (LedgerCheckFailing)

```bash
kubectl -n $ENV logs deploy/api --since=30m | grep -i 'ledger balance check'
```

```promql
sum by (result) (increase(banking_ledger_check_duration_seconds_count{env="prod"}[10m]))
hikaricp_connections_pending{pool="banking",env="prod"}
```

- `skipped` (timeout): the database is slow or the pool (`DB_POOL_SIZE=4`) is
  saturated. Check pending connections and the database's CPU in the DigitalOcean
  control panel. Growth of `ledger_entries` can also push the query past 2 s; raise
  it with the backend lead, do not lengthen the timeout without reviewing pool impact.
- `failed`: connection errors or SQL errors. Check the firewall rule
  (`doctl databases firewalls list <db-id>` must show `k8s` with
  `bc80ba3a-e1df-428a-aadc-d87c4e8650d2`) and the log stack trace. If customer money
  routes also fail, follow [money-availability-burn.md](money-availability-burn.md).
- While it is failing, run the SQL above by hand once an hour.

## Recovery check

```bash
kubectl -n monitoring port-forward svc/kube-prometheus-stack-grafana 3000:80
```

On the **Money SLO** dashboard at http://localhost:3000, check that the ledger
panel does not rise above the count found in step 3 after the API is back and has
run a check (60 s after start), and that check results are `ok`.

The check counts unbalanced transactions one by one, so a correcting transaction
does not bring the gauge back to 0: the original transaction stays unbalanced in the
append-only ledger. Once the incident is handled, silence `LedgerUnbalancedTransactions`
for that `env` in Alertmanager
(`kubectl -n monitoring port-forward svc/kube-prometheus-stack-alertmanager 9093`,
http://localhost:9093), linking the incident, and have the backend lead decide how
the known transaction is excluded from the check. A silence hides new imbalances
too, so keep it short and run the SQL above daily while it is in place.

## Escalation

Page the backend lead (owner of the correctness SLOs) immediately for
`LedgerUnbalancedTransactions`, in any environment. Production also needs whoever is
accountable for customer funds informed before the API is brought back. Open an
incident review within two working days.
