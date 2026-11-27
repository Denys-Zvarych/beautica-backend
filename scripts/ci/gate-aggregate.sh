#!/usr/bin/env bash
# Phase 567: the CI Gate aggregation (the single required check of pr-validate.yml), moved out of the
# workflow so the BASE copy decides (taken from plan's trusted-sha, like verify-selected-ran.sh).
#
#   NEEDS='<toJSON(needs)>' EVENT_NAME=<event> [IS_PROMOTION=true] [PR_NUMBER=n GH_REPO=o/r] gate-aggregate.sh
#
# exit 0 = green, 1 = red. `skipped` is fine ONLY where the plan mode says the job was not needed; a failed
# `plan` makes every dependent skip, which must not read as green. Unknown or inconsistent plan output
# fails closed.
#
# Modes (plan.outputs.mode):
#   full | selective  gates, test, verify must succeed; count > 0
#   none              count = 0 (pull_request only... and push never: plan upgrades a push `none` to full)
#   verified          push only (Q3); gates, test, verify must be SKIPPED; count = 0
#   deferred          draft PR: ALWAYS red. The PR's draft flag is re-read live (Q3): true -> "DEFERRED: draft",
#                     false / empty / API error -> "STALE DEFERRED" (fail closed either way)
# Events:
#   pull_request      full | selective | none | deferred; a promotion PR (main <- dev) must be full (Q1)
#   push              full | selective | verified
#   anything else     full (workflow_dispatch / nightly)
set -euo pipefail

: "${NEEDS:?NEEDS (toJSON(needs)) is required}"
EVENT_NAME="${EVENT_NAME:-}"
fail=0

jq -e 'type == "object"' <<<"$NEEDS" >/dev/null 2>&1 || { echo "::error::NEEDS is not a JSON object"; exit 1; }
result() { jq -r --arg k "$1" '.[$k].result // "missing"' <<<"$NEEDS"; }
mode=$(jq -r '.plan.outputs.mode // ""' <<<"$NEEDS")
count=$(jq -r '.plan.outputs.count // ""' <<<"$NEEDS")
ci_changed=$(jq -r '.plan.outputs["ci-changed"] // ""' <<<"$NEEDS")
echo "event=$EVENT_NAME plan=$(result plan) mode=${mode:-?} count=${count:-?} gates=$(result gates) self-test=$(result selector-self-test) test=$(result test) verify=$(result verify)"

require_success() { [[ "$(result "$1")" == "success" ]] || { echo "::error::$1 must succeed (got $(result "$1"))"; fail=1; }; }
require_skipped() { [[ "$(result "$1")" == "skipped" ]] || { echo "::error::mode=$mode needs $1 skipped (got $(result "$1"))"; fail=1; }; }

# Q3 live draft re-check: the deferred verdict is never trusted on its own, and it is always red.
deferred_verdict() {
  local draft=""
  if [[ "${PR_NUMBER:-}" =~ ^[1-9][0-9]*$ && -n "${GH_REPO:-}" ]]; then
    draft=$(gh api "repos/$GH_REPO/pulls/$PR_NUMBER" --jq .draft 2>/dev/null) || draft=""
  fi
  if [[ "$draft" == "true" ]]; then
    echo "::error::DEFERRED: draft — mark ready to run tests"
  else
    echo "::error::STALE DEFERRED: PR is ready, re-run (live draft flag: '${draft:-unavailable}')"
  fi
}

require_success plan
case "$mode" in
  full | selective)
    require_success gates; require_success test; require_success verify
    [[ "$count" =~ ^[1-9][0-9]*$ ]] || { echo "::error::mode=$mode needs a positive class count (got '$count')"; fail=1; } ;;
  none)
    [[ "$count" == "0" ]] || { echo "::error::mode=none needs count=0 (got '$count')"; fail=1; } ;;
  verified)
    require_skipped gates; require_skipped test; require_skipped verify
    [[ "$count" == "0" ]] || { echo "::error::mode=verified needs count=0 (got '$count')"; fail=1; } ;;
  deferred)
    deferred_verdict; fail=1 ;;
  *) echo "::error::unknown mode '$mode'"; fail=1 ;;
esac

case "$EVENT_NAME" in
  pull_request)
    case "$mode" in
      full | selective | none | deferred) ;;
      *) echo "::error::pull_request run cannot be mode=$mode"; fail=1 ;;
    esac
    if [[ "${IS_PROMOTION:-}" == "true" && "$mode" != "full" ]]; then
      echo "::error::promotion PR (main <- dev) must be mode=full (got '$mode')"; fail=1
    fi ;;
  push)
    case "$mode" in
      full | selective | verified) ;;
      *) echo "::error::push run must be mode=full|selective|verified (got '$mode')"; fail=1 ;;
    esac ;;
  *)
    [[ "$mode" == "full" ]] || { echo "::error::${EVENT_NAME:-unknown} run must be mode=full (got '$mode')"; fail=1; } ;;
esac

# CI-infra changes are always full and need the selector self-test.
if [[ "$ci_changed" == "true" ]]; then
  require_success selector-self-test
  [[ "$mode" == "full" ]] || { echo "::error::CI infrastructure changed but mode=$mode"; fail=1; }
fi

# Phase 366: when plan asked for the db-backup jobs they must have passed (never silently skipped).
if [[ "$(jq -r '.plan.outputs["db-backup"] // ""' <<<"$NEEDS")" == "true" ]]; then
  require_success db-backup-shellcheck
  require_success db-backup-it
fi

for job in $(jq -r 'keys[]' <<<"$NEEDS"); do
  case "$(result "$job")" in
    failure | cancelled) echo "::error::$job finished as $(result "$job")"; fail=1 ;;
  esac
done
exit "$fail"
