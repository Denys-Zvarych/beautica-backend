#!/usr/bin/env bash
# Phase 567 TC-2 (L1 lookup, Q4/Q5) + TC-4 (promotion PR, Q1) + L3 draft routes: table test of
# scripts/ci/plan-decide.sh against a throw-away git repo and a fake `gh` serving fixture API JSON.
#   scripts/ci/tests/plan-decide-test.sh [path/to/plan-decide.sh]
# Falsify: break any rule in plan-decide.sh and at least one row goes red.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$(cd "$(dirname "${1:-$HERE/../plan-decide.sh}")" && pwd)/$(basename "${1:-$HERE/../plan-decide.sh}")"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
REPO=o/r
INFRA_RE='^(\.github/|scripts/ci/|build\.gradle\.kts$|src/test/resources/)'

# ---- fixture repo: base -> before -> after (app change); infra commit on a side branch ----
git init -q "$work/repo"
cd "$work/repo"
git config user.email t@example.invalid; git config user.name t; git config commit.gpgsign false
mkdir -p src/main/java/x; echo 'class A {}' >src/main/java/x/A.java; git add -A; git commit -qm base
echo 'class B {}' >src/main/java/x/B.java; git add -A; git commit -qm before
BEFORE=$(git rev-parse HEAD)
echo 'class A { int a; }' >src/main/java/x/A.java; git add -A; git commit -qm after
SHA=$(git rev-parse HEAD); TREE=$(git rev-parse 'HEAD^{tree}')
git checkout -q -b infra "$BEFORE"
mkdir -p .github/workflows; echo 'name: x' >.github/workflows/x.yml; git add -A; git commit -qm infra
INFRA_SHA=$(git rev-parse HEAD)
git checkout -q -
PR_HEAD=1111111111111111111111111111111111111111

# ---- fake gh ----
mkdir -p "$work/bin" "$work/fix"
cat >"$work/bin/gh" <<'EOF'
#!/usr/bin/env bash
serve() { [[ "$1" == error ]] && { echo "HTTP 500" >&2; exit 1; }; cat "$1"; }
# Only the push route may call gh (the L1 lookup); any other call is logged for row() and fails.
if [[ "${EVENT_NAME:-}" != push ]]; then
  printf '%s\n' "$*" >>"$GH_CALL_LOG"; echo "fake gh: call outside the push route: $*" >&2; exit 2
fi
case "$1 $2" in
  "api repos/"*"/pulls") serve "$GH_FIX_PULLS" ;;
  "api repos/"*"/actions/runs?"*) serve "$GH_FIX_RUNS" ;;
  "api repos/"*"/check-runs?"*)
    # GH_FIX_CHECKS = space-separated pages. Like GitHub: without --paginate only page 1; without
    # filter=all each page keeps only the newest run per name (the default filter=latest).
    pages=($GH_FIX_CHECKS); [[ " $* " == *" --paginate "* ]] || pages=("${pages[0]}")
    for p in "${pages[@]}"; do
      if [[ "$2" == *filter=all* || "$p" == error ]]; then serve "$p"
      else jq -c '.check_runs |= (group_by(.name) | map(max_by(.id)))' "$p" 2>/dev/null || cat "$p"; fi
    done ;;
  "run download")
    shift 2; dir=""
    while (($#)); do [[ "$1" == -D ]] && dir=$2; shift; done
    [[ "$GH_FIX_TREE" == missing ]] && { echo "no artifact" >&2; exit 1; }
    mkdir -p "$dir"; [[ "$GH_FIX_TREE" == notree ]] || printf '%s\n' "$GH_FIX_TREE" >"$dir/tree.txt" ;;
  *) echo "fake gh: unexpected call: $*" >&2; exit 2 ;;
esac
EOF
chmod +x "$work/bin/gh"

# pulls_json <count> [head-repo]
pulls_json() {
  local n=$1 repo=${2:-$REPO}
  local f="$work/fix/pulls-$n-${repo//\//_}.json"
  jq -n --argjson n "$n" --arg repo "$repo" --arg head "$PR_HEAD" \
    '[range(0; $n) | {number: (42 + .), merged_at: "2026-11-27T10:00:00Z", base: {ref: "dev"}, head: {sha: $head, repo: {full_name: $repo}}}]
     + [{number: 9, merged_at: null, base: {ref: "dev"}, head: {sha: $head, repo: {full_name: $repo}}}]' >"$f"
  echo "$f"
}
# runs_json <conclusion>: pr-validate (old failed + latest + label-noop mirror), gitleaks, migration-immutability,
# and a gitleaks run on ANOTHER head whose suite must never count.
runs_json() {
  local f="$work/fix/runs-$1.json"
  jq -n --arg c "$1" --arg head "$PR_HEAD" '{workflow_runs: [
      {id: 100, check_suite_id: 500, conclusion: "failure", created_at: "2026-11-27T08:00:00Z", head_sha: $head, path: ".github/workflows/pr-validate.yml", display_title: ("pr-42 " + $head)},
      {id: 101, check_suite_id: 501, conclusion: $c, created_at: "2026-11-27T09:00:00Z", head_sha: $head, path: ".github/workflows/pr-validate.yml", display_title: ("pr-42 " + $head)},
      {id: 102, check_suite_id: 502, conclusion: "success", created_at: "2026-11-27T09:30:00Z", head_sha: $head, path: ".github/workflows/pr-validate.yml", display_title: "label-noop pr-42"},
      {id: 103, check_suite_id: 503, conclusion: "success", created_at: "2026-11-27T09:40:00Z", head_sha: $head, path: ".github/workflows/gitleaks.yml", display_title: ("pr-42 " + $head)},
      {id: 104, check_suite_id: 504, conclusion: "success", created_at: "2026-11-27T09:41:00Z", head_sha: $head, path: ".github/workflows/migration-immutability.yml", display_title: "x"},
      {id: 105, check_suite_id: 505, conclusion: "success", created_at: "2026-11-27T09:42:00Z", head_sha: "2222222222222222222222222222222222222222", path: ".github/workflows/gitleaks.yml", display_title: "y"}]}' >"$f"
  echo "$f"
}
# checks_mut <jq filter over the check_runs array>: the genuine green set (app github-actions, each from its
# own workflow's suite: CI Gate 501, gitleaks 503, migration-immutability 504) plus an old red CI Gate, then
# the filter. checks_json [name-to-break] [conclusion] [ci-gate-suite] is the common shorthand.
checks_mut() {
  local f="$work/fix/checks-$RANDOM$RANDOM.json"
  jq -n '{check_runs: (
      [{id: 1, name: "CI Gate", conclusion: "failure", check_suite: {id: 500}, app: {slug: "github-actions"}}]
      + ([ "CI Gate", "Check Migration Immutability", "Check Migration Version Monotonicity",
           "Verify Gate <-> Mirror Parity", "Scan for secrets" ] | to_entries
         | map({id: (10 + .key), name: .value, conclusion: "success", app: {slug: "github-actions"},
                check_suite: {id: ({"CI Gate": 501, "Scan for secrets": 503}[.value] // 504)}}))
      | '"$1"')}' >"$f"
  echo "$f"
}
checks_json() {
  local bad=${1:-} c=${2:-failure} suite=${3:-501}
  checks_mut "map(if .name == \"CI Gate\" and .id != 1 then .check_suite.id = $suite else . end
                  | if .name == \"$bad\" then .conclusion = $(jq -n --arg c "$c" 'if $c == "null" then null else $c end') else . end)"
}
pass=0; failures=0
# row <name> <want-route> <want-reason-substring> KEY=VALUE...
row() {
  local name=$1 want=$2 text=$3; shift 3
  local out route
  rm -rf "$work/out"; : >"$work/gh-calls"
  out=$(cd "$work/repo" && env -i PATH="$work/bin:$PATH" HOME="$HOME" REPO="$REPO" CI_OUT_DIR="$work/out" GH_CALL_LOG="$work/gh-calls" "$@" bash "$SCRIPT" 2>&1) || {
    echo "FAIL $name: script exited non-zero"; echo "$out" | sed 's/^/    /'; failures=$((failures + 1)); return; }
  if [[ -s "$work/gh-calls" ]]; then
    echo "FAIL $name: gh called outside the push route: $(head -1 "$work/gh-calls")"; failures=$((failures + 1)); return
  fi
  route=$(sed -n 's/^ROUTE=//p' <<<"$out")
  if [[ "$route" != "$want" ]] || ! grep -qF -- "$text" <<<"$out"; then
    echo "FAIL $name: want ROUTE=$want with '$text'"; echo "$out" | sed 's/^/    /'; failures=$((failures + 1)); return
  fi
  if [[ "$want" == verified || "$want" == deferred ]] && ! grep -qx "MODE=$want" "$work/out/selection.env" 2>/dev/null; then
    echo "FAIL $name: no selection.env with MODE=$want"; failures=$((failures + 1)); return
  fi
  if [[ "$want" == selective && "$(sed -n 's/^BASE_SHA=//p' <<<"$out")" == "" ]]; then
    echo "FAIL $name: selective route without BASE_SHA"; failures=$((failures + 1)); return
  fi
  echo "ok   $name"; pass=$((pass + 1))
}

OK_PULLS=$(pulls_json 1); OK_RUNS=$(runs_json success); OK_CHECKS=$(checks_json)
PUSH=(EVENT_NAME=push REF_NAME=dev SHA="$SHA" BEFORE="$BEFORE" INFRA_RE="$INFRA_RE")
GOOD=(GH_FIX_PULLS="$OK_PULLS" GH_FIX_RUNS="$OK_RUNS" GH_FIX_CHECKS="$OK_CHECKS" GH_FIX_TREE="$TREE")

# ---- TC-2: L1 push lookup ----
row should_verify_when_treeMatchesAndAllRequiredGreen verified "already passed" "${PUSH[@]}" "${GOOD[@]}"
row should_selective_when_treeDiffers selective "base moved" "${PUSH[@]}" "${GOOD[@]}" GH_FIX_TREE="$(git -C "$work/repo" rev-parse "$BEFORE^{tree}")"
for check in "CI Gate" "Check Migration Immutability" "Check Migration Version Monotonicity" "Verify Gate <-> Mirror Parity" "Scan for secrets"; do
  slug=${check//[^A-Za-z]/}
  row "should_selective_when_${slug}NotSuccess" selective "'$check' is not success" "${PUSH[@]}" "${GOOD[@]}" GH_FIX_CHECKS="$(checks_json "$check" failure)"
  row "should_selective_when_${slug}Pending" selective "'$check' is not success" "${PUSH[@]}" "${GOOD[@]}" GH_FIX_CHECKS="$(checks_json "$check" null)"
done
row should_selective_when_ciGateFromAnotherRun selective "'CI Gate' has no github-actions run" "${PUSH[@]}" "${GOOD[@]}" GH_FIX_CHECKS="$(checks_json "" failure 777)"
# L5 (fix 1): only the parity check may be `skipped`; neutral / cancelled / missing parity and any other skipped check -> selective.
row should_verify_when_paritySkippedAndOthersGreen verified "already passed" "${PUSH[@]}" "${GOOD[@]}" GH_FIX_CHECKS="$(checks_json "Verify Gate <-> Mirror Parity" skipped)"
row should_selective_when_parityNeutral selective "'Verify Gate <-> Mirror Parity' is not success" "${PUSH[@]}" "${GOOD[@]}" GH_FIX_CHECKS="$(checks_json "Verify Gate <-> Mirror Parity" neutral)"
row should_selective_when_parityCancelled selective "'Verify Gate <-> Mirror Parity' is not success" "${PUSH[@]}" "${GOOD[@]}" GH_FIX_CHECKS="$(checks_json "Verify Gate <-> Mirror Parity" cancelled)"
row should_selective_when_parityMissing selective "'Verify Gate <-> Mirror Parity' has no github-actions run" "${PUSH[@]}" "${GOOD[@]}" \
  GH_FIX_CHECKS="$(checks_mut 'map(select(.name != "Verify Gate <-> Mirror Parity"))')"
for check in "CI Gate" "Check Migration Immutability" "Check Migration Version Monotonicity" "Scan for secrets"; do
  slug=${check//[^A-Za-z]/}
  row "should_selective_when_${slug}Skipped" selective "'$check' is not success" "${PUSH[@]}" "${GOOD[@]}" GH_FIX_CHECKS="$(checks_json "$check" skipped)"
done
# fix 2: every required check pinned to app github-actions + its own workflow's suite on the PR head.
declare -A OTHER_SUITE=(["CI Gate"]=503 ["Check Migration Immutability"]=501 ["Check Migration Version Monotonicity"]=503
  ["Verify Gate <-> Mirror Parity"]=502 ["Scan for secrets"]=504)
for check in "CI Gate" "Check Migration Immutability" "Check Migration Version Monotonicity" "Verify Gate <-> Mirror Parity" "Scan for secrets"; do
  slug=${check//[^A-Za-z]/}
  row "should_selective_when_${slug}FromForeignApp" selective "'$check' has no github-actions run" "${PUSH[@]}" "${GOOD[@]}" \
    GH_FIX_CHECKS="$(checks_mut "map(if .name == \"$check\" then .app.slug = \"evil-app\" else . end)")"
  row "should_selective_when_${slug}FromWrongWorkflow" selective "'$check' has no github-actions run" "${PUSH[@]}" "${GOOD[@]}" \
    GH_FIX_CHECKS="$(checks_mut "map(if .name == \"$check\" then .check_suite.id = ${OTHER_SUITE[$check]} else . end)")"
done
row should_selective_when_forgedGreenFromForeignAppIsLatest selective "'Scan for secrets' is not success" "${PUSH[@]}" "${GOOD[@]}" \
  GH_FIX_CHECKS="$(checks_mut 'map(if .name == "Scan for secrets" then .conclusion = "failure" else . end)
    + [{id: 99, name: "Scan for secrets", conclusion: "success", check_suite: {id: 503}, app: {slug: "evil-app"}}]')"
row should_selective_when_scanFromGitleaksRunOnOtherHead selective "'Scan for secrets' has no github-actions run" "${PUSH[@]}" "${GOOD[@]}" \
  GH_FIX_CHECKS="$(checks_mut 'map(if .name == "Scan for secrets" then .check_suite.id = 505 else . end)')"
# filter=all: the PR run's own (older) CI Gate is found although a newer mirror CI Gate shares its name.
row should_verify_when_olderRealCiGateHiddenByNewerMirror verified "already passed" "${PUSH[@]}" "${GOOD[@]}" \
  GH_FIX_CHECKS="$(checks_mut '. + [{id: 98, name: "CI Gate", conclusion: "success", check_suite: {id: 502}, app: {slug: "github-actions"}}]')"
row should_selective_when_mirrorGreenButPrCiGateRed selective "'CI Gate' is not success" "${PUSH[@]}" "${GOOD[@]}" \
  GH_FIX_CHECKS="$(checks_mut 'map(if .name == "CI Gate" and .id != 1 then .conclusion = "failure" else . end)
    + [{id: 98, name: "CI Gate", conclusion: "success", check_suite: {id: 502}, app: {slug: "github-actions"}}]')"
row should_selective_when_latestPrRunFailed selective "concluded 'failure'" "${PUSH[@]}" "${GOOD[@]}" GH_FIX_RUNS="$(runs_json failure)"
row should_full_when_forkHeadRepo full "fork" "${PUSH[@]}" "${GOOD[@]}" GH_FIX_PULLS="$(pulls_json 1 fork/r)"
row should_full_when_noMergedPr full "0 merged PRs" "${PUSH[@]}" "${GOOD[@]}" GH_FIX_PULLS="$(pulls_json 0)"
row should_full_when_twoMergedPrs full "2 merged PRs" "${PUSH[@]}" "${GOOD[@]}" GH_FIX_PULLS="$(pulls_json 2)"
row should_full_when_pullsApiError full "API error" "${PUSH[@]}" "${GOOD[@]}" GH_FIX_PULLS=error
row should_full_when_runsApiError full "API error" "${PUSH[@]}" "${GOOD[@]}" GH_FIX_RUNS=error
row should_full_when_checksApiError full "API error" "${PUSH[@]}" "${GOOD[@]}" GH_FIX_CHECKS=error
echo '{"workflow_runs": []}' >"$work/fix/no-runs.json"
row should_full_when_noPrRun full "no pr-validate run" "${PUSH[@]}" "${GOOD[@]}" GH_FIX_RUNS="$work/fix/no-runs.json"
echo 'not json' >"$work/fix/garbage.json"
row should_full_when_pullsJsonMalformed full "malformed" "${PUSH[@]}" "${GOOD[@]}" GH_FIX_PULLS="$work/fix/garbage.json"
: >"$work/fix/empty.json"
row should_full_when_checksBodyEmpty full "malformed" "${PUSH[@]}" "${GOOD[@]}" GH_FIX_CHECKS="$work/fix/empty.json"
row should_full_when_pullsBodyEmpty full "malformed" "${PUSH[@]}" "${GOOD[@]}" GH_FIX_PULLS="$work/fix/empty.json"
row should_full_when_runsBodyEmpty full "no pr-validate run" "${PUSH[@]}" "${GOOD[@]}" GH_FIX_RUNS="$work/fix/empty.json"
row should_full_when_checksJsonMalformed full "malformed" "${PUSH[@]}" "${GOOD[@]}" GH_FIX_CHECKS="$work/fix/garbage.json"
# --paginate: 100+ check runs on the head, the real CI Gate only on page 2.
P1="$work/fix/checks-page1.json"; P2="$work/fix/checks-page2.json"
jq '.check_runs |= (map(select(.name != "CI Gate" or .id == 1)) + [range(0; 100) | {id: (1000 + .), name: ("lint-" + tostring), conclusion: "success", check_suite: {id: 900}, app: {slug: "github-actions"}}])' \
  "$(checks_mut '.')" >"$P1"
jq '.check_runs |= map(select(.name == "CI Gate" and .id != 1))' "$(checks_mut '.')" >"$P2"
row should_verify_when_realCiGateOnSecondPage verified "already passed" "${PUSH[@]}" "${GOOD[@]}" GH_FIX_CHECKS="$P1 $P2"
echo '"page"' >"$work/fix/not-object.json"
row should_full_when_secondPageMalformed full "malformed check-runs" "${PUSH[@]}" "${GOOD[@]}" GH_FIX_CHECKS="$P1 $work/fix/not-object.json"
row should_full_when_checkRunElementNotObject full "malformed check-runs" "${PUSH[@]}" "${GOOD[@]}" GH_FIX_CHECKS="$(checks_mut '. + ["oops"]')"
row should_full_when_checkSuiteFieldMalformed full "malformed check-runs" "${PUSH[@]}" "${GOOD[@]}" \
  GH_FIX_CHECKS="$(checks_mut 'map(if .name == "Scan for secrets" then .check_suite = "x" else . end)')"
row should_full_when_artifactMissing full "unavailable" "${PUSH[@]}" "${GOOD[@]}" GH_FIX_TREE=missing
row should_full_when_treeTxtMissing full "no tree.txt" "${PUSH[@]}" "${GOOD[@]}" GH_FIX_TREE=notree
row should_full_when_treeTxtMalformed full "malformed tree.txt" "${PUSH[@]}" "${GOOD[@]}" GH_FIX_TREE="not-a-tree"
row should_full_when_beforeZero full "zero or unreachable" EVENT_NAME=push REF_NAME=dev SHA="$SHA" BEFORE=0000000000000000000000000000000000000000 INFRA_RE="$INFRA_RE" "${GOOD[@]}"
row should_full_when_beforeEmpty full "zero or unreachable" EVENT_NAME=push REF_NAME=dev SHA="$SHA" BEFORE= INFRA_RE="$INFRA_RE" "${GOOD[@]}"
row should_full_when_beforeUnreachable full "zero or unreachable" EVENT_NAME=push REF_NAME=dev SHA="$SHA" BEFORE=2222222222222222222222222222222222222222 INFRA_RE="$INFRA_RE" "${GOOD[@]}"
row should_full_when_ciInfraChangedInPushRange full "CI infrastructure changed: .github/workflows/x.yml" EVENT_NAME=push REF_NAME=dev SHA="$INFRA_SHA" BEFORE="$BEFORE" INFRA_RE="$INFRA_RE" "${GOOD[@]}" GH_FIX_TREE="$(git -C "$work/repo" rev-parse "$INFRA_SHA^{tree}")"
row should_full_when_noInfraRegex full "no infra regex" EVENT_NAME=push REF_NAME=dev SHA="$SHA" BEFORE="$BEFORE" "${GOOD[@]}"
row should_full_when_prMergedIntoOtherBranch full "0 merged PRs" EVENT_NAME=push REF_NAME=main SHA="$SHA" BEFORE="$BEFORE" INFRA_RE="$INFRA_RE" "${GOOD[@]}"
row should_full_when_dispatch full "full run: workflow_dispatch" EVENT_NAME=workflow_dispatch "${GOOD[@]}"
row should_full_when_unknownEvent full "full run: schedule" EVENT_NAME=schedule
row should_full_when_noEvent full "unknown event"

# ---- TC-4 (Q1): promotion PR main <- dev is full for draft, ready and with every skip input set ----
PRB=(EVENT_NAME=pull_request BASE_SHA="$BEFORE" HEAD_SHA="$SHA")
PROMO=(PR_BASE_REF=main PR_HEAD_REF=dev PR_HEAD_REPO="$REPO")
row should_full_when_promotionReady full "promotion PR" "${PRB[@]}" "${PROMO[@]}" PR_DRAFT=false
row should_full_when_promotionDraft full "promotion PR" "${PRB[@]}" "${PROMO[@]}" PR_DRAFT=true
row should_full_when_promotionDraftWithEverySkipInput full "promotion PR" "${PRB[@]}" "${PROMO[@]}" PR_DRAFT=true FULL_CI_LABEL=false \
  SHA="$SHA" BEFORE="$BEFORE" REF_NAME=main INFRA_RE="$INFRA_RE" "${GOOD[@]}"
row should_infraFull_when_promotionTouchesCiInfra infra "promotion PR" "${PRB[@]}" "${PROMO[@]}" PR_DRAFT=true INFRA=.github/workflows/x.yml
row should_notPromote_when_forkBranchNamedDev deferred "draft PR" "${PRB[@]}" PR_BASE_REF=main PR_HEAD_REF=dev PR_HEAD_REPO=fork/r PR_DRAFT=true

# ---- L3: draft PRs ----
FEAT=(PR_BASE_REF=dev PR_HEAD_REF=feat/x PR_HEAD_REPO="$REPO")
row should_defer_when_draft deferred "draft PR" "${PRB[@]}" "${FEAT[@]}" PR_DRAFT=true
row should_defer_when_draftTouchesCiInfra deferred "draft PR" "${PRB[@]}" "${FEAT[@]}" PR_DRAFT=true INFRA=scripts/ci/x.sh
row should_full_when_draftLabelledFullCi full "label full-ci" "${PRB[@]}" "${FEAT[@]}" PR_DRAFT=true FULL_CI_LABEL=true
row should_infra_when_fullCiAndInfra infra "CI infrastructure changed" "${PRB[@]}" "${FEAT[@]}" PR_DRAFT=false FULL_CI_LABEL=true INFRA=build.gradle.kts
row should_selective_when_readyPr selective "pull_request diff" "${PRB[@]}" "${FEAT[@]}" PR_DRAFT=false "${GOOD[@]}"
row should_selective_when_draftFlagUnknown selective "pull_request diff" "${PRB[@]}" "${FEAT[@]}" PR_DRAFT=
row should_infra_when_readyPrTouchesCiInfra infra "CI infrastructure changed: scripts/ci/x.sh" "${PRB[@]}" "${FEAT[@]}" PR_DRAFT=false INFRA=scripts/ci/x.sh
row should_full_when_prWithoutShas full "without base/head" EVENT_NAME=pull_request "${FEAT[@]}" PR_DRAFT=false

echo "plan-decide-test: $pass passed, $failures failed"
[[ "$failures" -eq 0 ]]
