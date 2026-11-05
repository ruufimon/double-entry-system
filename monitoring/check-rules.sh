#!/usr/bin/env bash
# Lints and unit-tests the banking PrometheusRules with promtool (#21). Needs python3 with
# PyYAML and Docker; uses the Prometheus version that kube-prometheus-stack 92.18.0 ships.
#   monitoring/check-rules.sh
set -euo pipefail
PROMETHEUS_IMAGE="${PROMETHEUS_IMAGE:-prom/prometheus:v3.15.1}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RULES_DIR="$ROOT/k8s/cluster/monitoring/rules"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# promtool reads plain rule files, so strip the PrometheusRule wrapper.
for rule in "$RULES_DIR"/*.yaml; do
  name="$(basename "$rule" .yaml)"
  python3 -I -c '
import sys, yaml
doc = yaml.safe_load(open(sys.argv[1]))
assert doc["kind"] == "PrometheusRule", sys.argv[1]
yaml.safe_dump({"groups": doc["spec"]["groups"]}, open(sys.argv[2], "w"), sort_keys=False)
' "$rule" "$WORK/$name.rules.yaml"
done
cp "$RULES_DIR"/tests/*.test.yaml "$WORK/"

run() { docker run --rm --user "$(id -u):$(id -g)" -v "$WORK:/rules" -w /rules --entrypoint promtool "$PROMETHEUS_IMAGE" "$@"; }
cd "$WORK"
run check rules ./*.rules.yaml
run test rules ./*.test.yaml
