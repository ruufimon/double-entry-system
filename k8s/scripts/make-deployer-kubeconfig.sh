#!/bin/bash
# Prints a kubeconfig for the namespace-scoped CI service account to stdout.
# Treat the output as a secret: pipe it straight into `gh secret set`, never to a file in the repo.
# usage: KUBECONFIG=<admin kubeconfig> k8s/scripts/make-deployer-kubeconfig.sh <namespace>
set -euo pipefail
NS="${1:?namespace}"
SERVER="$(kubectl config view --minify -o jsonpath='{.clusters[0].cluster.server}')"
CA="$(kubectl -n "$NS" get secret deployer-token -o jsonpath='{.data.ca\.crt}')"
TOKEN="$(kubectl -n "$NS" get secret deployer-token -o jsonpath='{.data.token}' | base64 --decode)"
[ -n "$TOKEN" ] || { echo "deployer-token has no token yet" >&2; exit 1; }
cat <<KUBECONFIG
apiVersion: v1
kind: Config
clusters:
  - name: banking-k8s
    cluster:
      server: ${SERVER}
      certificate-authority-data: ${CA}
users:
  - name: deployer
    user:
      token: ${TOKEN}
contexts:
  - name: ${NS}
    context:
      cluster: banking-k8s
      user: deployer
      namespace: ${NS}
current-context: ${NS}
KUBECONFIG
