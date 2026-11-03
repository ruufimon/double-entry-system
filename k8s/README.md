# Kubernetes (DigitalOcean DOKS)

The banking apps on one DOKS cluster, `banking-k8s` in `sgp1`: a 2-node pool of
`s-2vcpu-4gb` that autoscales to 3. Each environment is a namespace. The managed
PostgreSQL databases and the container registry are the same ones App Platform uses.

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
- `overlays/prod/transition-api.yaml` exposes the prod API publicly until
  2026-11-10, for the old App Platform UIs. Remove it with them.
