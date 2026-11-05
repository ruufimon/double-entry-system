# API metrics absent

## Alert

| Alert | Condition | Severity |
|---|---|---|
| `ApiMetricsAbsent` | `absent(up{job="api",env="<env>"} == 1)` for 5 min | page |

Prometheus has had no successful scrape of the API's metrics port (9090, `/metrics`)
for 5 minutes. Every SLO alert depends on these metrics, so while this fires the
burn-rate and ledger alerts are blind.

Alertmanager mutes it 22:00 to 06:10 Asia/Bangkok: the `Cluster schedule` workflow
scales the node pool to zero overnight (k8s/README.md, "Nightly shutdown"), and
Prometheus and Alertmanager are off with it.

## Impact

Either the API is down (customers affected), or only monitoring is broken
(customers fine, but no alert will fire for real failures). Find out which first.

## First 5 minutes

Set `ENV=prod` (or `preprod`).

1. **Is it the nightly shutdown?** Outside 22:00 to 06:10 Bangkok time it should not
   be. Check whether the cluster was turned off or has not come back:

   ```bash
   kubectl get nodes
   gh run list --workflow 'Cluster schedule' --limit 3
   ```

   No nodes, or a recent `off` run (scheduled or by hand), explains it. Turn the
   cluster on from the Actions tab if it should be up. A late `on` run after 06:00
   can make this fire briefly after 06:10.

2. **Is the API pod running?**

   ```bash
   kubectl -n $ENV get deploy api
   kubectl -n $ENV get pods -l app=api -o wide
   kubectl -n $ENV describe pod -l app=api | tail -30
   kubectl -n $ENV logs deploy/api --since=15m | tail -50
   curl -s -o /dev/null -w '%{http_code}\n' https://app.139-59-219-31.sslip.io/   # prod UI
   ```

   Replicas 0 (someone scaled it down, for example for the ledger runbook),
   `CrashLoopBackOff`, `ImagePullBackOff` or `Pending` means the pod is down: this is
   a customer outage. Go to Mitigation.

3. **Pod is up: can Prometheus reach it?**

   ```bash
   kubectl -n monitoring port-forward svc/kube-prometheus-stack-prometheus 9090
   ```

   In http://localhost:9090, Status > Targets, look for `serviceMonitor/monitoring/banking-api/0`:

   ```promql
   up{job="api"}
   ```

   - **Target present, `up == 0`, error "context deadline exceeded" or "connection
     refused":** the network policy. Check that `allow-monitoring-to-api-metrics`
     exists and admits port 9090 from namespace `monitoring`:

     ```bash
     kubectl -n $ENV get networkpolicy allow-monitoring-to-api-metrics -o yaml
     kubectl -n $ENV get endpoints api        # must list port 9090 (metrics)
     ```

     Also check that `METRICS_PORT` on the container is still 9090.
   - **No target at all, or none for this `env`:** the ServiceMonitor.

     ```bash
     kubectl -n monitoring get servicemonitor banking-api -o yaml
     kubectl -n $ENV get svc api --show-labels      # needs label app=api and a port named metrics
     ```

     It selects services labelled `app: api` in namespaces `preprod` and `prod`,
     port `metrics`, and sets `env` from the namespace. A renamed port, a changed
     label, or a missing `env` relabeling all make the target vanish.
   - **Target `up == 1` but the alert still fires:** the `env` or `job` label does
     not match the rule. Compare `up{job="api"}` labels with the alert expression.

4. **Last change:** `kubectl -n $ENV rollout history deployment/api`, and
   `git log -5 -- k8s/base k8s/cluster/monitoring` for policy or ServiceMonitor edits.

## Mitigation

- **Pod down after a deploy:** roll back (k8s/README.md, "Rollback"):

  ```bash
  kubectl -n $ENV rollout undo deployment/api
  kubectl -n $ENV rollout status deployment/api
  ```

  Then deploy the known-good commit normally.
- **Scaled to 0 by hand:** confirm with whoever did it (a ledger incident may be in
  progress) before `kubectl -n $ENV scale deployment/api --replicas=1`.
- **Pending pod / no nodes:** check `kubectl get nodes` and the node pool in
  DigitalOcean; run `Cluster schedule` with action `on`.
- **Network policy missing or edited:** re-apply the base:
  `kubectl apply -k k8s/overlays/$ENV` (or re-run the environment's deploy job).
- **ServiceMonitor missing or edited:**
  `kubectl apply -f k8s/cluster/monitoring/api-servicemonitor.yaml`.

## Recovery check

`up{job="api",env="prod"}` is 1 in Prometheus and the alert resolves. Then:

```bash
kubectl -n monitoring port-forward svc/kube-prometheus-stack-grafana 3000:80
```

The **Money SLO** dashboard at http://localhost:3000 shows fresh request data and a
ledger value of `0`. Metrics from the gap are lost; burn-rate alerts may need several
minutes of data before they are meaningful again.

## Escalation

- API down and not fixed by rollback within 15 minutes: backend lead.
- Monitoring-only failure: fix within the working day; until then, check the UI by
  hand every hour and tell the backend lead that alerting is blind.
- Cluster or node problems: DigitalOcean support.
