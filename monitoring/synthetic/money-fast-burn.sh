#!/usr/bin/env bash
# Synthetic failure test for MoneyAvailabilityFastBurn (#23). PRE-PRODUCTION ONLY.
#
# Sends ~2 deposits/s to the pre-prod UI, removes the cluster's firewall rule from
# banking-preprod-db so new database connections fail (readiness still passes /ping, which
# is the real failure mode), waits for the alert, then restores the rule and checks recovery.
# The rule is restored on any exit, including Ctrl-C.
#
# Usage: monitoring/synthetic/money-fast-burn.sh [--dry-run]
# Env:   LOAD_SECONDS (default 360)   load duration, the cut starts after CUT_AFTER
#        CUT_AFTER (default 60)       seconds of healthy load before the firewall cut
#        RECOVERY_SECONDS (default 60) load after the restore, to confirm recovery
# Runbook: docs/runbooks/money-availability-burn.md
set -euo pipefail

# Captured before the constants below shadow variables such as DB_ID or URL.
STARTUP_ENV="$(env)"
readonly STARTUP_ENV

# Hard-coded target. There is deliberately no way to point this script anywhere else.
readonly TARGET_ENV="preprod"
readonly DB_NAME="banking-preprod-db"
readonly DB_ID="beeeace6-b957-40a9-9245-a9e2c9038b81"
readonly CLUSTER_ID="bc80ba3a-e1df-428a-aadc-d87c4e8650d2"
readonly URL="https://preprod-ui.139-59-219-31.sslip.io/api/accounts/acc123/deposits"
readonly BODY='{"amount":0.01}'
readonly PROD_DB_ID="b07ee325-822c-4f7b-8e5b-328493b34449"

LOAD_SECONDS="${LOAD_SECONDS:-360}"
CUT_AFTER="${CUT_AFTER:-60}"
RECOVERY_SECONDS="${RECOVERY_SECONDS:-60}"
DRY_RUN=0
RULE_REMOVED=0
LOAD_PID=""
WORK_DIR=""

log() { printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S %Z')" "$*"; }
die() { log "ERROR: $*" >&2; exit 1; }

# Prints a command, and runs it unless --dry-run.
run() {
  printf '    + %s\n' "$*"
  if [[ "$DRY_RUN" -eq 0 ]]; then
    "$@"
  fi
}

pause() {
  if [[ "$DRY_RUN" -eq 0 ]]; then
    sleep "$1" &   # in the background so INT/TERM traps run at once, not after the sleep
    wait "$!"
  else
    log "(dry run: would wait $1 s)"
  fi
}

# True if the text names production: the prod database, or "prod" other than inside "preprod".
mentions_prod() {
  local text="${1,,}"
  [[ "$text" == *"$PROD_DB_ID"* || "$text" == *banking-prod-db* ]] && return 0
  text="${text//preprod/}"
  [[ "$text" == *prod* ]]
}

refuse_prod() {
  local arg line
  for arg in "$@"; do
    if mentions_prod "$arg"; then
      die "refusing: argument '$arg' mentions production. This script only ever targets $TARGET_ENV."
    fi
  done
  while IFS= read -r line; do
    if mentions_prod "${line#*=}"; then
      die "refusing: environment variable '$line' mentions production."
    fi
  done < <(grep -E '^(ENV|TARGET|TARGET_ENV|NAMESPACE|DB|DB_ID|DB_NAME|DATABASE_ID|URL|BASE_URL|HOST|CLUSTER|CLUSTER_ID|KUBE_CONTEXT|DO_CONTEXT|DIGITALOCEAN_CONTEXT)=' <<<"$STARTUP_ENV" || true)
  # The prod database ID or name anywhere in the environment is never legitimate here.
  if grep -qiE "$PROD_DB_ID|banking-prod-db" <<<"$STARTUP_ENV"; then
    die "refusing: the environment mentions the production database."
  fi
  # Belt and braces: the constants above must still be the pre-prod ones.
  [[ "$DB_ID" != "$PROD_DB_ID" && "$DB_NAME" == "banking-preprod-db" && "$URL" == https://preprod-* ]] ||
    die "refusing: the hard-coded target is not pre-production."
}

usage() {
  sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'
}

for arg in "$@"; do
  case "$arg" in
    --dry-run) ;;
    -h|--help) usage; exit 0 ;;
    *) refuse_prod "$arg"; die "unknown argument '$arg' (only --dry-run and --help)" ;;
  esac
done
refuse_prod "$@"
for arg in "$@"; do
  [[ "$arg" == --dry-run ]] && DRY_RUN=1
done

for name in LOAD_SECONDS CUT_AFTER RECOVERY_SECONDS; do
  [[ "${!name}" =~ ^[0-9]+$ ]] || die "$name must be a whole number of seconds"
done
(( CUT_AFTER < LOAD_SECONDS )) || die "CUT_AFTER must be less than LOAD_SECONDS"

# Background load: one request every 0.5 s, each in its own curl so a slow response does not
# lower the rate. Each line in the tally is "<phase> <status>"; the phase comes from a file the
# main script updates, so the summary can tell healthy, cut and restored traffic apart.
load_loop() {
  local duration="$1" end
  end=$(( SECONDS + duration ))
  trap 'kill $(jobs -p) 2>/dev/null; exit 0' TERM
  while (( SECONDS < end )); do
    (
      phase="$(cat "$WORK_DIR/phase")"
      code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 -X POST \
        -H 'Content-Type: application/json' -d "$BODY" "$URL" || true)"
      printf '%s %s\n' "$phase" "${code:-000}" >> "$WORK_DIR/codes"
    ) &
    sleep 0.5
  done
  wait
}

start_load() {
  local duration="$1"
  log "Load: ~2 req/s for ${duration} s:"
  printf "    + curl -s -o /dev/null -w '%%{http_code}\\\\n' -X POST -H 'Content-Type: application/json' -d '%s' %s\n" "$BODY" "$URL"
  if [[ "$DRY_RUN" -eq 0 ]]; then
    load_loop "$duration" &
    LOAD_PID=$!
  fi
}

stop_load() {
  if [[ -n "$LOAD_PID" ]]; then
    kill "$LOAD_PID" 2>/dev/null || true
    wait "$LOAD_PID" 2>/dev/null || true
    LOAD_PID=""
  fi
}

set_phase() {
  if [[ -n "$WORK_DIR" ]]; then
    printf '%s\n' "$1" > "$WORK_DIR/phase"
  fi
}

restore_rule() {
  local attempt
  log "Restoring the cluster's firewall rule on $DB_NAME:"
  for attempt in 1 2 3; do
    if run doctl databases firewalls append "$DB_ID" --rule "k8s:$CLUSTER_ID"; then
      RULE_REMOVED=0
      log "Firewall rule restored."
      return 0
    fi
    log "Restore attempt $attempt failed; retrying in 5 s."
    sleep 5
  done
  log "!!! COULD NOT RESTORE THE FIREWALL RULE. Run this now:" >&2
  log "!!!   doctl databases firewalls append $DB_ID --rule k8s:$CLUSTER_ID" >&2
  return 1
}

summary() {
  [[ -n "$WORK_DIR" && -s "$WORK_DIR/codes" ]] || return 0
  log "Status codes by phase (phase status count):"
  sort "$WORK_DIR/codes" | uniq -c | awk '{printf "    %-9s %s  %6d\n", $2, $3, $1}'
}

cleanup() {
  local status=$?
  trap - EXIT INT TERM
  stop_load
  jobs -p | xargs -r kill 2>/dev/null || true   # an interrupted pause leaves its sleep behind
  if [[ "$RULE_REMOVED" -eq 1 ]]; then
    restore_rule || status=1
  fi
  summary
  [[ -n "$WORK_DIR" ]] && rm -rf "$WORK_DIR"
  log "Done (exit $status)."
  exit "$status"
}
trap cleanup EXIT
trap 'log "Interrupted."; exit 130' INT
trap 'log "Terminated."; exit 143' TERM

# --- Confirmation -----------------------------------------------------------------------------

log "Synthetic fast-burn test against $TARGET_ENV ($DB_NAME $DB_ID)."
[[ "$DRY_RUN" -eq 1 ]] && log "DRY RUN: doctl and curl will not be called."
log "This cuts pre-prod's database access for about $(( LOAD_SECONDS - CUT_AFTER )) s."
answer=""
read -r -p "Type preprod to proceed: " answer || true
[[ "$answer" == "preprod" ]] || die "confirmation not given; nothing was changed."

# --- Preflight --------------------------------------------------------------------------------

if [[ "$DRY_RUN" -eq 0 ]]; then
  for tool in doctl curl; do
    command -v "$tool" >/dev/null || die "$tool not found"
  done
  name="$(doctl databases get "$DB_ID" --format Name --no-header)" ||
    die "cannot read $DB_ID with doctl (auth context?)"
  [[ "$name" == "$DB_NAME" ]] || die "database $DB_ID is '$name', expected $DB_NAME"
fi
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/money-fast-burn.XXXXXX")"
set_phase healthy

# --- Step 1: load -----------------------------------------------------------------------------

log "Step 1/4: start load, healthy for ${CUT_AFTER} s before the cut."
start_load "$LOAD_SECONDS"
pause "$CUT_AFTER"

# --- Step 2: cut the firewall rule ------------------------------------------------------------

log "Step 2/4: remove the cluster's firewall rule (type k8s, value $CLUSTER_ID) from $DB_NAME."
printf '    + doctl databases firewalls list %s --format UUID,Type,Value --no-header\n' "$DB_ID"
if [[ "$DRY_RUN" -eq 0 ]]; then
  rules="$(doctl databases firewalls list "$DB_ID" --format UUID,Type,Value --no-header)"
  uuids="$(awk -v cluster="$CLUSTER_ID" '$2 == "k8s" && $3 == cluster {print $1}' <<<"$rules")"
  count="$(grep -c . <<<"$uuids" || true)"
  [[ "$count" -eq 1 ]] || die "expected exactly one k8s rule for the cluster, found $count:
$rules"
  uuid="$uuids"
else
  uuid="<uuid>"
fi
# Mark the rule as removed before the call, so an interrupt mid-call still restores it
# (appending a rule that already exists is harmless).
RULE_REMOVED=1
set_phase cut
run doctl databases firewalls remove "$DB_ID" --uuid "$uuid"
log "Rule removed. New database connections now fail; connections already in the pool may"
log "survive for a while, so money routes can take a minute or more to start returning 500."

# --- Step 3: wait for the alert ---------------------------------------------------------------

remaining=$(( LOAD_SECONDS - CUT_AFTER ))
log "Step 3/4: wait ${remaining} s for MoneyAvailabilityFastBurn. In another terminal:"
log "    kubectl -n monitoring port-forward svc/kube-prometheus-stack-alertmanager 9093"
log "    then open http://localhost:9093 and check the alert's runbook_url annotation."
log "Expect it within 5 minutes of the cut (pre-prod alerts are ticket severity, never pages)."
pause "$remaining"
stop_load

# --- Step 4: restore and confirm recovery -----------------------------------------------------

log "Step 4/4: restore and confirm recovery."
restore_rule
set_phase restored
start_load "$RECOVERY_SECONDS"
pause "$RECOVERY_SECONDS"
if [[ -n "$LOAD_PID" ]]; then
  wait "$LOAD_PID" 2>/dev/null || true
  LOAD_PID=""
fi
log "Check that 'restored' requests are 2xx and that the alert resolves in Alertmanager;"
log "the Money SLO dashboard is at kubectl -n monitoring port-forward svc/kube-prometheus-stack-grafana 3000:80."
