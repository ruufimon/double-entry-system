# Kubernetes (DigitalOcean DOKS)

The banking apps on one DOKS cluster, `banking-k8s` in `sgp1`: a 2-node pool of
`s-2vcpu-4gb` that autoscales to 3. Each environment is a namespace with its own
managed PostgreSQL database, and images come from the DigitalOcean registry.
(App Platform hosted both environments until 2026-11-03.)

| Environment | Namespace | Status | Customer UI |
| --- | --- | --- | --- |
| Production | `prod` | Live since 2026-11-03 | https://app.139-59-219-31.sslip.io |
| Pre-production | `preprod` | Live | https://preprod-ui.139-59-219-31.sslip.io |

Both environments use [sslip.io](https://sslip.io) hosts, which resolve a name
containing an IP address to that IP, until there is a real domain. To switch, change
the three hosts in each overlay's `ingress.yaml`. Certificates
come from Let's Encrypt, and HTTP redirects to HTTPS.

## Layout

```
k8s/
  cluster/     cluster-wide pieces: namespaces, Traefik, Let's Encrypt issuers, CI access
  base/        the five apps, network policies and disruption budgets, and the Ingress
  overlays/    preprod and prod: namespace, image tags, hosts, the UIs' API address
  scripts/     bootstrap, secrets, and the CI kubeconfig
```

Traffic arrives at one DigitalOcean load balancer, goes through Traefik (2
replicas), then reaches the three UIs. The API and the biller have no Ingress.
The UIs reach the API at `http://api.<namespace>.svc.cluster.local:8080`, and
network policies make that the only way in. Only the UIs accept traffic from
Traefik, only the UIs can reach the API, and only the API can reach the biller.
Pods run as non-root under the `restricted` pod security standard.

## Setting up a cluster

```bash
export KUBECONFIG=~/.kube/banking-k8s.yaml
doctl kubernetes cluster kubeconfig save banking-k8s --set-current-context=false
k8s/scripts/bootstrap-cluster.sh
# Let the cluster reach each database (DigitalOcean firewall rule for the cluster):
doctl databases firewalls append <database-id> --rule k8s:<cluster-id>
k8s/scripts/create-secrets.sh preprod <preprod-database-id> <preprod-admin-secrets.env>
kubectl apply -k k8s/overlays/preprod
```

`create-secrets.sh` builds `banking-secrets` (`DATABASE_URL` for the non-admin
`banking_app` user, plus `ADMIN_TOKEN` and `ADMIN_PASSWORD`) without printing
any values. Secrets never go in git, and CI cannot read them.

## Deploys

The Scala CI workflow publishes each commit's images as `:<sha>`. Then
`Deploy to Kubernetes pre-prod`:

1. pins the overlay to that `:<sha>`, and runs `kubectl apply -k`,
2. waits for every rollout, then smoke-tests the public URL,
3. rolls every deployment back if either step fails.

It deploys as the `deployer` service account, which can only change workloads in
its own namespace. Its kubeconfig is the `KUBE_CONFIG` secret in the `preprod`
GitHub environment, created with:

```bash
k8s/scripts/make-deployer-kubeconfig.sh preprod | gh secret set KUBE_CONFIG --env preprod
```

The job runs when the repository variable `K8S_DEPLOY` is `true`. Production is
deployed by `Deploy to production`, from `main` only, after an approval in the
`production` environment, with its own namespace-scoped `KUBE_CONFIG`. Pushes to the
`k8s` branch deploy only to pre-production.

## Nightly shutdown

The `Cluster schedule` workflow turns the whole cluster off from 22:00 to 06:00
(Asia/Bangkok), production included, by scaling the node pool to zero. Overnight,
every URL fails, and DigitalOcean's managed components in the cluster stop too.
In the morning the same pods start again on their own, and the workflow waits
until production answers. A missed midnight interest run is caught up when the
API starts. A bill payment in progress at 22:00 is lost (#8). It saves about
$16/month of node cost; the load balancer and databases bill all the time.

It needs the `DIGITALOCEAN_K8S_TOKEN` secret, and runs on schedule only while
the repository variable `CLUSTER_SCHEDULE` is `true`. Run it by hand from the
Actions tab (action `off` or `on`) at any time, for example to bring the cluster
up early.

## Monitoring

`kube-prometheus-stack` runs in the `monitoring` namespace: Prometheus, Alertmanager, Grafana,
kube-state-metrics and node-exporter. `bootstrap-cluster.sh` installs it from
`cluster/monitoring/values.yaml` (chart pinned there) and applies the banking pieces:

| File | What it does |
| --- | --- |
| `cluster/monitoring/api-servicemonitor.yaml` | Scrapes the API's metrics port (9090) in `preprod` and `prod`, labelled `env` |
| `cluster/monitoring/rules/money-availability.yaml` | Money-class SLO recording rules, burn-rate and ledger alerts ([spec](../spec/MonitoringSLO.md)) |
| `cluster/monitoring/dashboards/money-slo.yaml` | The "Money SLO" Grafana dashboard |

Rule changes are checked by `monitoring/check-rules.sh` (promtool lint plus unit tests in
`cluster/monitoring/rules/tests/`), which CI also runs. Runbooks for every alert are in
`docs/runbooks/`.

```bash
kubectl -n monitoring port-forward svc/kube-prometheus-stack-grafana 3000:80        # Grafana, user admin
kubectl -n monitoring get secret grafana-admin -o jsonpath='{.data.admin-password}' | base64 -d
kubectl -n monitoring port-forward svc/kube-prometheus-stack-prometheus 9090        # Prometheus
kubectl -n monitoring port-forward svc/kube-prometheus-stack-alertmanager 9093      # Alertmanager
```

Alerts go to one webhook, the `url` key of the `alertmanager-webhook` secret. The bootstrap
creates it with a placeholder, so nothing is delivered until it is replaced. Production
`page` alerts and default `critical` rules go to the page route; everything from `preprod`,
`ticket` alerts and `warning` rules go to the ticket route. Both are muted 22:00 to 06:10
Asia/Bangkok: overnight the stack is off with the rest of the cluster, and the mute covers
the restart. Prometheus keeps 35 days (the SLO window is 30) on a 10 GiB volume that survives
the nightly node deletion.

The stack requests about 1.1 GiB of memory across the two nodes, roughly twice what the apps
request. The autoscaler may add the third node under load.

To test the money SLO alert end to end, run `monitoring/synthetic/money-fast-burn.sh` against
pre-production (it refuses production).

## Rollback

Normally, re-run the deploy job of an earlier commit, or `kubectl apply -k` with
the overlay pinned to that `:<sha>`. In an emergency:

```bash
kubectl -n preprod rollout undo deployment/api   # takes seconds; previous 5 revisions are kept
```

`rollout undo` leaves the "last applied" record pointing at the newer version, so
follow it with a normal deploy of the commit you want.

## Things that bit us, and are handled

- **Connection limit.** The smallest managed Postgres plan allows about 22
  connections. A rollout briefly runs two API pods, so the API's pool is capped
  with `DB_POOL_SIZE=4`. Count every process using a database before raising it:
  the first CI deploy failed and rolled back while App Platform's pre-prod API
  still held 10 connections on the same database.
- **Registry access.** `doctl kubernetes cluster registry add` reported success
  but created nothing. The bootstrap writes a read-only pull secret into each
  namespace instead.
- **Certificates.** The default-deny network policy blocked cert-manager's HTTP-01
  challenge pod until it got its own allow rule. Traefik terminates TLS on its
  `websecure` entrypoint for every router; without that, HTTPS requests found no
  route.
- **Drains.** Pods sleep 5 seconds before stopping, and Traefik keeps accepting
  requests for 10 seconds on shutdown. With that, a node drain under load showed
  no failed requests (125 samples). Without it, about 3 seconds failed.
- **ingress-nginx** was archived in March 2026, which is why this uses Traefik.

## Limits

- One API replica: the in-process message bus (#8) and the interest scheduler
  aren't safe to run twice. Killing the API pod costs about 8 seconds.
