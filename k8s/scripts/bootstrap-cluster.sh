#!/bin/bash
# Installs the cluster-level pieces on a fresh DOKS cluster: namespaces, registry pull
# credentials, Traefik, cert-manager and the Let's Encrypt issuers. Safe to re-run.
# usage: KUBECONFIG=<file> k8s/scripts/bootstrap-cluster.sh
set -euo pipefail
CERT_MANAGER_VERSION="v1.21.3"
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
echo "Load balancer address:"
kubectl -n traefik get svc traefik -o jsonpath='{.status.loadBalancer.ingress[0].ip}{"\n"}'
