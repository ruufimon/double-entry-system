#!/bin/bash
# Creates or updates the banking-secrets Secret in one namespace. Values are never printed.
# DATABASE_URL is banking_app's connection string for the environment's managed database.
# usage: k8s/scripts/create-secrets.sh <namespace> <database-cluster-id> <admin-secrets.env>
#   admin-secrets.env holds ADMIN_TOKEN=... and ADMIN_PASSWORD=... lines.
set -euo pipefail
NS="${1:?namespace}"; DB="${2:?database cluster id}"; ENV_FILE="${3:?admin secrets env file}"
umask 077
OUT="$(mktemp)"; trap 'rm -f "$OUT"' EXIT
python3 - "$DB" "$ENV_FILE" > "$OUT" <<'PY'
import json, subprocess, sys, urllib.parse
cluster, secrets_file = sys.argv[1:3]
def doctl(*args):
    data = json.loads(subprocess.check_output(["doctl", *args, "-o", "json"]))
    return data[0] if isinstance(data, list) else data
conn = doctl("databases", "connection", cluster, "--show")
password = doctl("databases", "user", "get", cluster, "banking_app", "--show")["password"]
assert password and "*" not in password, "password is masked"
# The private host keeps traffic on the VPC; the cluster is in the same region and VPC.
host = conn.get("private_host") or conn["host"]
url = "postgresql://banking_app:%s@%s:%s/defaultdb?sslmode=require" % (urllib.parse.quote(password, safe=""), host, conn["port"])
values = dict(line.strip().split("=", 1) for line in open(secrets_file) if "=" in line)
print("DATABASE_URL=" + url)
for key in ("ADMIN_TOKEN", "ADMIN_PASSWORD"):
    print("%s=%s" % (key, values[key]))
PY
kubectl -n "$NS" create secret generic banking-secrets --from-env-file="$OUT" --dry-run=client -o yaml | kubectl apply -f -
