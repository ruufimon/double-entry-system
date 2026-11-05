#!/bin/bash
# Installs the cluster-level pieces on a fresh DOKS cluster: namespaces, registry pull
# credentials, Traefik, cert-manager, the Let's Encrypt issuers and the monitoring stack
# (kube-prometheus-stack, the banking ServiceMonitor, SLO rules and dashboard). Safe to re-run.
# Needs kubectl, doctl, helm 3 and openssl.
# usage: KUBECONFIG=<file> k8s/scripts/bootstrap-cluster.sh
set -euo pipefail
CERT_MANAGER_VERSION="v1.21.3"
KUBE_PROMETHEUS_STACK_VERSION="92.18.0"
HERE="$(cd "$(dirname "$0")/.." && pwd)"

kubectl apply -f "$HERE/cluster/namespaces.yaml"
# Read-only registry pull credentials, one secret per app namespace. (`doctl kubernetes
# cluster registry add` returned success here without creating anything.)
for namespace in preprod prod; do
  doctl registry kubernetes-manifest --name registry-double-entry-banking --namespace "$namespace" | kubectl apply -f -
done
kubectl apply -f "$HERE/cluster/traefik.yaml"
kubectl apply -f "https://github.com/cert-manager/cert-manager/releases/download/${CERT_MANAGER_VERSION}/cert-manager.yaml"
kubectl -n cert-manager rollout status deploy/cert-manager-webhook --timeout=180s
kubectl apply -f "$HERE/cluster/cluster-issuers.yaml"
kubectl apply -f "$HERE/cluster/deployer-rbac.yaml"
kubectl -n traefik rollout status deploy/traefik --timeout=180s

# Monitoring (#20-#22). Secrets are created once with generated or placeholder values and
# never overwritten, so re-running keeps the Grafana password and the real webhook URL.
if ! kubectl -n monitoring get secret grafana-admin > /dev/null 2>&1; then
  kubectl -n monitoring create secret generic grafana-admin \
    --from-literal=admin-user=admin \
    --from-literal=admin-password="$(openssl rand -base64 24)"
  echo "Grafana password: kubectl -n monitoring get secret grafana-admin -o jsonpath='{.data.admin-password}' | base64 -d"
fi
if ! kubectl -n monitoring get secret alertmanager-webhook > /dev/null 2>&1; then
  # Replace with a real receiver (Slack, Discord, ntfy ...) to get pages:
  #   kubectl -n monitoring create secret generic alertmanager-webhook --from-literal=url=<url> \
  #     --dry-run=client -o yaml | kubectl apply -f -
  kubectl -n monitoring create secret generic alertmanager-webhook \
    --from-literal=url=http://127.0.0.1:9/unconfigured
fi
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts --force-update > /dev/null
helm upgrade --install kube-prometheus-stack prometheus-community/kube-prometheus-stack \
  --version "$KUBE_PROMETHEUS_STACK_VERSION" --namespace monitoring \
  --values "$HERE/cluster/monitoring/values.yaml" --wait --timeout 10m
kubectl apply -f "$HERE/cluster/monitoring/api-servicemonitor.yaml"
kubectl apply -f "$HERE/cluster/monitoring/rules/"
kubectl apply -f "$HERE/cluster/monitoring/dashboards/"
echo "Load balancer address:"
kubectl -n traefik get svc traefik -o jsonpath='{.status.loadBalancer.ingress[0].ip}{"\n"}'
