#!/usr/bin/env bash
# Phase 567 TC-1 / TC-13 (Q3, Q4): table-driven test of scripts/ci/gate-aggregate.sh.
#   scripts/ci/tests/gate-aggregate-test.sh [path/to/gate-aggregate.sh]
# A fake `gh` on PATH answers the live draft re-check (GH_FAKE_DRAFT=true|false|error).
# Falsify: flip any rule in gate-aggregate.sh and at least one row goes red.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="${1:-$HERE/../gate-aggregate.sh}"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

mkdir -p "$work/bin"
cat >"$work/bin/gh" <<'EOF'
#!/usr/bin/env bash
# fake gh: `gh api repos/<r>/pulls/<n> --jq .draft`
[[ "$1" == api && "$2" == repos/*/pulls/* ]] || { echo "fake gh: unexpected call: $*" >&2; exit 2; }
case "${GH_FAKE_DRAFT:-error}" in
  true) echo true ;;
  false) echo false ;;
  *) echo "HTTP 502" >&2; exit 1 ;;
esac
EOF
chmod +x "$work/bin/gh"

pass=0; failures=0

# needs_json <mode> <count> <plan> <gates> <test> <verify> [self-test] [ci-changed] [db-backup] [db-sc] [db-it]
needs_json() {
  jq -n --arg mode "$1" --arg count "$2" --arg plan "$3" --arg gates "$4" --arg test "$5" --arg verify "$6" \
    --arg self "${7:-skipped}" --arg ci "${8:-false}" --arg db "${9:-false}" --arg dbsc "${10:-skipped}" --arg dbit "${11:-skipped}" '{
      plan: {result: $plan, outputs: {mode: $mode, count: $count, "ci-changed": $ci, "db-backup": $db}},
      gates: {result: $gates, outputs: {}}, "selector-self-test": {result: $self, outputs: {}},
      test: {result: $test, outputs: {}}, verify: {result: $verify, outputs: {}},
      "db-backup-shellcheck": {result: $dbsc, outputs: {}}, "db-backup-it": {result: $dbit, outputs: {}}}'
}

# row <name> <want-exit> <event> <needs-json> [KEY=VALUE env...] [--expect-text <text>]
row() {
  local name=$1 want=$2 event=$3 needs=$4; shift 4
  local envs=() text=""
  while (($#)); do
    if [[ "$1" == --expect-text ]]; then text=$2; shift 2; else envs+=("$1"); shift; fi
  done
  local got=0 out
  out=$(env -i PATH="$work/bin:$PATH" HOME="$HOME" NEEDS="$needs" EVENT_NAME="$event" "${envs[@]}" bash "$SCRIPT" 2>&1) || got=$?
  [[ "$got" -ne 0 ]] && got=1
  if [[ "$got" != "$want" ]]; then
    echo "FAIL $name: want exit $want, got $got"; echo "$out" | sed 's/^/    /'; failures=$((failures + 1)); return
  fi
  if [[ -n "$text" ]] && ! grep -qF -- "$text" <<<"$out"; then
    echo "FAIL $name: output lacks '$text'"; echo "$out" | sed 's/^/    /'; failures=$((failures + 1)); return
  fi
  echo "ok   $name"; pass=$((pass + 1))
}

S=success K=skipped F=failure
PR=(PR_NUMBER=7 GH_REPO=o/r)

# ---- baseline behaviour kept from the pre-567 inline gate ----
row should_pass_when_prSelectiveAllGreen 0 pull_request "$(needs_json selective 5 $S $S $S $S)"
row should_pass_when_prFullAllGreen 0 pull_request "$(needs_json full 400 $S $S $S $S)"
row should_pass_when_prNone 0 pull_request "$(needs_json none 0 $S $K $K $K)"
row should_fail_when_planFailed 1 pull_request "$(needs_json selective 5 $F $K $K $K)"
row should_fail_when_testSkippedOnSelective 1 pull_request "$(needs_json selective 5 $S $S $K $S)"
row should_fail_when_verifySkippedOnFull 1 pull_request "$(needs_json full 5 $S $S $S $K)"
row should_fail_when_gatesFailed 1 pull_request "$(needs_json selective 5 $S $F $S $S)"
row should_fail_when_selectiveCountZero 1 pull_request "$(needs_json selective 0 $S $S $S $S)"
row should_fail_when_noneCountPositive 1 pull_request "$(needs_json none 3 $S $K $K $K)"
row should_fail_when_modeEmpty 1 pull_request "$(needs_json "" 5 $S $S $S $S)"
row should_fail_when_modeUnknown 1 pull_request "$(needs_json partial 5 $S $S $S $S)"
row should_fail_when_ciChangedButSelfTestSkipped 1 pull_request "$(needs_json full 5 $S $S $S $S $K true)"
row should_fail_when_ciChangedButSelective 1 pull_request "$(needs_json selective 5 $S $S $S $S $S true)"
row should_pass_when_ciChangedFullSelfTestGreen 0 pull_request "$(needs_json full 5 $S $S $S $S $S true)"
row should_fail_when_dbBackupAskedButSkipped 1 pull_request "$(needs_json full 5 $S $S $S $S $K false true $K $K)"
row should_fail_when_anyNeedCancelled 1 pull_request "$(needs_json none 0 $S $K $K $K cancelled)"
row should_fail_when_needsNotJson 1 pull_request "not json"

# ---- Q1: promotion PR and dispatch/nightly must be full ----
row should_pass_when_promotionPrFull 0 pull_request "$(needs_json full 400 $S $S $S $S)" IS_PROMOTION=true
row should_fail_when_promotionPrSelective 1 pull_request "$(needs_json selective 5 $S $S $S $S)" IS_PROMOTION=true
row should_fail_when_promotionPrNone 1 pull_request "$(needs_json none 0 $S $K $K $K)" IS_PROMOTION=true
row should_fail_when_promotionPrDeferred 1 pull_request "$(needs_json deferred 0 $S $S $K $K)" IS_PROMOTION=true "${PR[@]}" GH_FAKE_DRAFT=true
row should_pass_when_dispatchFull 0 workflow_dispatch "$(needs_json full 400 $S $S $S $S)"
row should_fail_when_dispatchSelective 1 workflow_dispatch "$(needs_json selective 5 $S $S $S $S)"
row should_fail_when_dispatchVerified 1 workflow_dispatch "$(needs_json verified 0 $S $K $K $K)"
row should_fail_when_scheduleNone 1 schedule "$(needs_json none 0 $S $K $K $K)"

# ---- Q3: verified only on push, with test/gates/verify skipped ----
row should_pass_when_pushVerifiedAllSkipped 0 push "$(needs_json verified 0 $S $K $K $K)"
row should_fail_when_verifiedOnPullRequest 1 pull_request "$(needs_json verified 0 $S $K $K $K)"
row should_fail_when_verifiedWithTestSuccess 1 push "$(needs_json verified 0 $S $K $S $K)"
row should_fail_when_verifiedWithGatesSuccess 1 push "$(needs_json verified 0 $S $S $K $K)"
row should_fail_when_verifiedWithVerifySuccess 1 push "$(needs_json verified 0 $S $K $K $S)"
row should_fail_when_verifiedWithPositiveCount 1 push "$(needs_json verified 3 $S $K $K $K)"
row should_fail_when_verifiedPlanFailed 1 push "$(needs_json verified 0 $F $K $K $K)"
row should_pass_when_pushSelectiveGreen 0 push "$(needs_json selective 5 $S $S $S $S)"
row should_pass_when_pushFullGreen 0 push "$(needs_json full 400 $S $S $S $S)"
row should_fail_when_pushNone 1 push "$(needs_json none 0 $S $K $K $K)"
row should_fail_when_pushDeferred 1 push "$(needs_json deferred 0 $S $S $K $K)"

# ---- TC-13: deferred is always red, with a live draft re-check ----
row should_failDeferred_when_liveDraftTrue 1 pull_request "$(needs_json deferred 0 $S $S $K $K)" "${PR[@]}" GH_FAKE_DRAFT=true --expect-text "DEFERRED: draft"
row should_failStale_when_liveDraftFalse 1 pull_request "$(needs_json deferred 0 $S $S $K $K)" "${PR[@]}" GH_FAKE_DRAFT=false --expect-text "STALE DEFERRED"
row should_failStale_when_liveDraftApiError 1 pull_request "$(needs_json deferred 0 $S $S $K $K)" "${PR[@]}" GH_FAKE_DRAFT=error --expect-text "STALE DEFERRED"
row should_failStale_when_noPrNumber 1 pull_request "$(needs_json deferred 0 $S $S $K $K)" GH_FAKE_DRAFT=true --expect-text "STALE DEFERRED"
row should_fail_when_deferredEvenIfEverythingGreen 1 pull_request "$(needs_json deferred 5 $S $S $S $S)" "${PR[@]}" GH_FAKE_DRAFT=true

echo "gate-aggregate-test: $pass passed, $failures failed"
[[ "$failures" -eq 0 ]]
