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
  cluster/     cluster-wide pieces: namespaces, Traefik, Let's Encrypt issuers, CI access,
               and the monitoring stack (monitoring/)
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

You need `doctl` (logged in), `kubectl`, `helm` 3 and `openssl`. Neither `kubectl` nor
`helm` needs root; the release binaries go in `~/.local/bin`:

```bash
mkdir -p ~/.local/bin && export PATH="$HOME/.local/bin:$PATH"   # add the export to ~/.bashrc too
KV=$(curl -fsSL https://dl.k8s.io/release/stable-1.36.txt)
curl -fsSLO "https://dl.k8s.io/release/$KV/bin/linux/amd64/kubectl"
echo "$(curl -fsSL "https://dl.k8s.io/release/$KV/bin/linux/amd64/kubectl.sha256")  kubectl" | sha256sum -c -
install -m 755 kubectl ~/.local/bin/
curl -fsSLO https://get.helm.sh/helm-v3.17.3-linux-amd64.tar.gz
curl -fsSL https://get.helm.sh/helm-v3.17.3-linux-amd64.tar.gz.sha256sum | sha256sum -c -
tar xzf helm-v3.17.3-linux-amd64.tar.gz linux-amd64/helm && install -m 755 linux-amd64/helm ~/.local/bin/
```

The kubeconfig that `doctl` saves gives full admin access to the cluster. Keep it at
mode 600 and never commit or share it; CI uses the namespace-scoped `deployer` instead.

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
kube-state-metrics and node-exporter. It has been live on `banking-k8s` since 2026-11-06 and
scrapes the API in both `preprod` and `prod`. `bootstrap-cluster.sh` installs it from
`cluster/monitoring/values.yaml` (chart version pinned in the script) and applies the banking
pieces:

| File | What it does |
| --- | --- |
| `cluster/monitoring/api-servicemonitor.yaml` | Scrapes the API's metrics port (9090) in `preprod` and `prod`, labelled `env` |
| `cluster/monitoring/rules/money-availability.yaml` | Money-class SLO recording rules, burn-rate and ledger alerts ([spec](../spec/MonitoringSLO.md)) |
| `cluster/monitoring/dashboards/money-slo.yaml` | The "Money SLO" Grafana dashboard |

Rule changes are checked by `monitoring/check-rules.sh` (promtool lint plus unit tests in
`cluster/monitoring/rules/tests/`), which CI also runs. Runbooks for every alert are in
`docs/runbooks/`.

### Changing the stack

After editing `values.yaml`, the rules or the dashboard, apply just the monitoring part
instead of re-running the whole bootstrap (which also re-applies Traefik and cert-manager):

```bash
export KUBECONFIG=~/.kube/banking-k8s.yaml
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts --force-update
helm upgrade --install kube-prometheus-stack prometheus-community/kube-prometheus-stack \
  --version 92.18.0 --namespace monitoring --values k8s/cluster/monitoring/values.yaml --wait --timeout 10m
kubectl apply -f k8s/cluster/monitoring/api-servicemonitor.yaml \
  -f k8s/cluster/monitoring/rules/ -f k8s/cluster/monitoring/dashboards/
```

Keep `--version` in step with `KUBE_PROMETHEUS_STACK_VERSION` in the bootstrap script. The
install prints a `would violate PodSecurity "baseline"` warning for node-exporter; that is
expected. The namespace enforces `privileged` because node-exporter needs host access, and
warns at `baseline` so any other pod asking for it stands out.

### Opening Grafana, Prometheus and Alertmanager

None of them has an Ingress. Reach them through a `kubectl port-forward` tunnel, which goes over
the authenticated Kubernetes API and listens only on your own machine:

```bash
export KUBECONFIG=~/.kube/banking-k8s.yaml
kubectl -n monitoring port-forward svc/kube-prometheus-stack-grafana 3000:80          # http://localhost:3000
kubectl -n monitoring port-forward svc/kube-prometheus-stack-prometheus 9090          # http://localhost:9090
kubectl -n monitoring port-forward svc/kube-prometheus-stack-alertmanager 9093        # http://localhost:9093
```

Grafana's user is `admin`; the password was generated by the bootstrap:

```bash
kubectl -n monitoring get secret grafana-admin -o jsonpath='{.data.admin-password}' | base64 -d; echo
```

The SLO dashboard is at http://localhost:3000/d/money-slo/money-slo; pick the environment with
the `env` selector. In Prometheus, **Status → Targets** should show `api` UP in both
environments; if it does not, no other panel has data.

Do not add `--address 0.0.0.0` to a port-forward, and do not expose these services through the
load balancer. Prometheus and Alertmanager have no login (anyone could read every metric or
silence every alert), and Grafana has only the one admin password. A public Grafana needs an
authenticating proxy (OAuth or Cloudflare Access) in front of it first.

### Alerts

Alerts go to one webhook, the `url` key of the `alertmanager-webhook` secret. The bootstrap
creates it with a placeholder, so **nothing is delivered until it is replaced**:

```bash
kubectl -n monitoring create secret generic alertmanager-webhook --from-literal=url='<webhook-url>' \
  --dry-run=client -o yaml | kubectl apply -f -
```

Production `page` alerts and default `critical` rules go to the page route; everything from
`preprod` (by `env` or by `namespace`), `ticket` alerts and `warning` rules go to the ticket
route. Both are muted 22:00 to 06:10 Asia/Bangkok: overnight the stack is off with the rest of
the cluster, and the mute covers the restart.

Expected with two nodes: `KubeCPUOvercommit` and `KubeMemoryOvercommit` fire as warnings,
because the pods' requests would not fit on one node if the other failed. `Watchdog` always
fires; it proves the alert pipeline works and is routed nowhere.

To test the money SLO alert end to end, run `monitoring/synthetic/money-fast-burn.sh` against
pre-production (it refuses production).

### Sizing and labels

Prometheus keeps 35 days (the SLO window is 30) on a 10 GiB volume that survives the nightly
node deletion. The stack requests about 1.2 GiB of memory across the two nodes. Grafana needs
512Mi of headroom: at 256Mi it was OOMKilled on first start, so its limit is 512Mi and its
bundled extra plugins (Jaeger, Pyroscope, Loki and traces apps) are not downloaded. The
autoscaler may add the third node under load.

The API tags its metrics `service="banking-api"`, which collides with the `service` label the
ServiceMonitor adds, so in Prometheus it appears as `exported_service="banking-api"`. Select
the API with `job="api"` and an environment with `env`.

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
