# Money availability burn

## Alerts

| Alert | Condition | Severity |
|---|---|---|
| `MoneyAvailabilityFastBurn` | error budget burning at 14.4x over 1 h and over 5 min | page |
| `MoneyAvailabilitySlowBurn` | burning at 6x over 6 h and over 30 min | page |
| `MoneyAvailabilityBudgetBurn` | burning at 1x over 3 d and over 6 h | ticket |

SLO ([spec/MonitoringSLO.md](../../spec/MonitoringSLO.md)): 99.9% of `money`
requests end in `success` or `rejected`. Money requests are account open
(`PUT /accounts/:id`), deposit, withdraw, transfer, admin adjustment and
interest-rate update. A request is bad when its `outcome` is `dependency`,
`misconfigured` or `error` (500s, timeouts, responses without a JSON error body).

At 14.4x the 30-day budget is gone in about two days; at 6x in five. Pre-production
carries the same rules at `ticket` severity.

## Impact

Customers cannot move money: deposits, withdrawals and transfers fail with 500 or
time out. Reads may still work. Readiness probes hit `/ping`, which does not touch
the database, so pods stay Ready and Kubernetes will not restart or reroute anything
on its own.

## First 5 minutes

Set the environment: `ENV=prod` (or `preprod`).

1. Is it still happening, and which route?

   ```bash
   kubectl -n monitoring port-forward svc/kube-prometheus-stack-prometheus 9090
   ```

   In http://localhost:9090:

   ```promql
   # bad ratio, last 5 min
   sum(rate(http_server_requests_seconds_count{class="money",outcome=~"dependency|misconfigured|error",env="prod"}[5m]))
     / sum(rate(http_server_requests_seconds_count{class="money",env="prod"}[5m]))

   # where it fails
   sum by (route, status, outcome) (rate(http_server_requests_seconds_count{class="money",outcome!~"success|rejected",env="prod"}[5m]))
   ```


2. Pod state and logs:

   ```bash
   kubectl -n $ENV get pods -l app=api -o wide
   kubectl -n $ENV logs deploy/api --since=15m | grep -iE 'error|exception|hikari|timeout' | tail -50
   kubectl -n $ENV get events --sort-by=.lastTimestamp | tail -20
   ```

3. Connection pool. The pool has only `DB_POOL_SIZE=4` connections:

   ```promql
   hikaricp_connections_pending{pool="banking"}
   hikaricp_connections_active{pool="banking"}
   ```

   Pending above 0 for minutes means the pool is starved (slow queries, or the
   database refusing new connections). `Connection is not available, request timed
   out` in the logs says the same.

4. Database reachability. The cluster reaches the managed database only through a
   firewall rule of type `k8s` with the cluster ID
   `bc80ba3a-e1df-428a-aadc-d87c4e8650d2`:

   ```bash
   # prod:    banking-prod-db    b07ee325-822c-4f7b-8e5b-328493b34449
   # preprod: banking-preprod-db beeeace6-b957-40a9-9245-a9e2c9038b81
   doctl databases firewalls list b07ee325-822c-4f7b-8e5b-328493b34449
   doctl databases get b07ee325-822c-4f7b-8e5b-328493b34449     # status should be online
   ```

   If the rule is missing, existing pooled connections keep working for a while,
   then new connections fail; failures can start a minute or more after the rule
   went away. The database plan allows about 22 connections in total
   (k8s/README.md, "Connection limit"); another process holding connections
   produces the same symptoms.

5. Last deploy:

   ```bash
   kubectl -n $ENV rollout history deployment/api
   kubectl -n $ENV get deploy api -o jsonpath='{.spec.template.spec.containers[0].image}{"\n"}'
   gh run list --workflow scala.yml --branch main --limit 3    # deploys are the 'Deploy to production' / 'Deploy to Kubernetes pre-prod' jobs
   ```

   A deploy shortly before the burn started is the first suspect.

## Mitigation

- **Bad deploy:** roll back (k8s/README.md, "Rollback"):

  ```bash
  kubectl -n $ENV rollout undo deployment/api
  kubectl -n $ENV rollout status deployment/api
  ```

  Then deploy the known-good commit normally, because `rollout undo` leaves the
  last-applied record pointing at the newer version.
- **Firewall rule missing:**

  ```bash
  doctl databases firewalls append <db-id> --rule k8s:bc80ba3a-e1df-428a-aadc-d87c4e8650d2
  ```

  If pending connections do not drain within a couple of minutes, restart the API
  (`kubectl -n $ENV rollout restart deployment/api`; one replica, about 8 s of
  downtime).
- **Connection limit hit:** find who else holds connections
  (`SELECT usename, application_name, client_addr, count(*) FROM pg_stat_activity GROUP BY 1,2,3;`)
  and stop the extra consumer. Do not raise `DB_POOL_SIZE`.
- **Database down or degraded:** check the DigitalOcean status page and the database
  page in the control panel; open a DigitalOcean support ticket.

## Recovery check

```bash
kubectl -n monitoring port-forward svc/kube-prometheus-stack-grafana 3000:80
```

Open the **Money SLO** dashboard at http://localhost:3000. Recovered when the money
error ratio is back at 0, `hikaricp_connections_pending{pool="banking"}` is 0, and
the short-window burn rate drops below the threshold (the fast-burn alert resolves
within minutes; the long window keeps the burn-rate panel elevated for up to an hour).
Make one real deposit through the UI to confirm.

## Escalation

1. Backend lead (owner of the `money` SLO) if not resolved in 15 minutes or if the
   cause is not a deploy or the firewall rule.
2. DigitalOcean support for database outages.
3. After resolution, record budget spent; follow the error budget policy in
   spec/MonitoringSLO.md (below 25% freezes feature deploys).

## Testing this alert

`monitoring/synthetic/money-fast-burn.sh` reproduces the fast burn in
pre-production only by removing the cluster's firewall rule from
`banking-preprod-db` under load, then restoring it. Expect a delay of about a minute
after the cut before money routes fail, while pooled connections survive.
