#!/usr/bin/env bash
# Phase 567: the Plan job's route decision, taken BEFORE select-tests.sh (which stays untouched).
#
#   plan-decide.sh            env-driven; prints ROUTE / REASON / BASE_SHA / HEAD_SHA (KEY=VALUE, one per line)
#
# Routes (the Plan YAML executes them; the selector keeps its own modes full|selective|none):
#   full       select-tests.sh --force-full "$REASON"
#   infra      select-tests.sh --force-full with ci_changed=true (pull_request: CI infrastructure changed)
#   selective  BASE_SHA=.. HEAD_SHA=.. select-tests.sh (a pull_request diff, or a push's before..after diff)
#   deferred   draft PR: no selector; this script writes selection.env MODE=deferred (CI Gate FAILS on it)
#   verified   push whose exact tree already passed the whole required set (Q5); writes MODE=verified
#
# FAIL-SAFE (Q4): every doubt (unknown event, zero/unreachable `before`, CI-infra change, API or artifact
# error, fork PR, missing or malformed tree.txt, 0 or >1 merged PRs) routes to `full`, never less.
#
# TRUST: on pull_request this file is the BASE copy (extracted with git archive like select-tests.sh); on
# push it is the merged tip of a protected branch. Only same-repo PRs are trusted for `verified`: a fork
# PR's own workflow could forge its tree.txt.
#
# Env:
#   EVENT_NAME                    pull_request | push | workflow_dispatch | ...
#   REPO                          owner/name (github.repository)
#   pull_request: PR_DRAFT PR_BASE_REF PR_HEAD_REF PR_HEAD_REPO FULL_CI_LABEL INFRA (non-empty = CI infra
#                 changed, computed by the workflow) BASE_SHA HEAD_SHA
#   push:         REF_NAME SHA BEFORE INFRA_RE (the workflow's infra regex), GH_TOKEN for `gh`
#   CI_OUT_DIR    where deferred/verified write selection.env (must not be a symlink)
set -euo pipefail

ZERO_SHA=0000000000000000000000000000000000000000
# Q5: the whole required set, byte-identical to the rulesets' required check names (Promotion source is
# a pull_request_target check on the PR only, not part of the tested tree).
REQUIRED_CHECKS=("CI Gate" "Check Migration Immutability" "Check Migration Version Monotonicity" \
  "Verify Gate <-> Mirror Parity" "Scan for secrets")
# Each required check counts only when GitHub Actions itself posted it (app slug `github-actions`, so no
# other app or token can forge a green one) from the workflow that owns the name, matched through the check
# suite of a pull_request run of that workflow on the PR head. CI Gate is pinned further to the PR's own
# pr-validate run (a `label-noop` mirror run posts a CI Gate too and is ignored, never mistaken for it).
declare -A CHECK_WORKFLOW=(
  ["CI Gate"]=.github/workflows/pr-validate.yml
  ["Check Migration Immutability"]=.github/workflows/migration-immutability.yml
  ["Check Migration Version Monotonicity"]=.github/workflows/migration-immutability.yml
  ["Verify Gate <-> Mirror Parity"]=.github/workflows/migration-immutability.yml
  ["Scan for secrets"]=.github/workflows/gitleaks.yml
)
# L5: the parity job is skipped by `if:` when migration-immutability.yml did not change (GitHub reports that
# as passing for the ruleset). Only this check may be `skipped`; every other one must be `success`.
SKIPPABLE_CHECK="Verify Gate <-> Mirror Parity"

emit() { # route reason [base head]
  printf 'ROUTE=%s\n' "$1"
  printf 'REASON=%s\n' "$(printf '%s' "$2" | tr -d '\n' | cut -c1-300)"
  printf 'BASE_SHA=%s\nHEAD_SHA=%s\n' "${3:-}" "${4:-}"
}

# write_skip_env <mode> <reason>: the selection files a skipped-selector run publishes (empty lists).
write_skip_env() {
  local out="${CI_OUT_DIR:?CI_OUT_DIR is required}"
  [[ ! -L "$out" ]] || { echo "plan-decide: refusing symlinked out dir $out" >&2; return 1; }
  rm -rf "$out"; mkdir -p "$out"
  : >"$out/selected-tests.txt"
  : >"$out/report-aliases.txt"
  {
    printf 'MODE=%s\n' "$1"
    printf 'REASON=%q\n' "${2//$'\n'/ }"
    printf 'COUNT=0\nTOTAL=0\nSHARDS=0\nSHARD_MATRIX=[]\nCI_CHANGED=false\nSHARD_WEIGHTS=\n'
  } >"$out/selection.env"
}

decide_pr() {
  local promotion=false
  if [[ "${PR_BASE_REF:-}" == main && "${PR_HEAD_REF:-}" == dev && -n "${REPO:-}" && "${PR_HEAD_REPO:-}" == "$REPO" ]]; then
    promotion=true
  fi
  # Q1: the promotion PR is always full, whatever the draft flag or any skip input says.
  if [[ "$promotion" == true ]]; then
    if [[ -n "${INFRA:-}" ]]; then emit infra "promotion PR dev -> main; CI infrastructure changed: ${INFRA%%$'\n'*}"
    else emit full "full run: promotion PR dev -> main"; fi
    return
  fi
  # `full-ci` forces a normal (full) run, also on a draft.
  if [[ "${FULL_CI_LABEL:-}" == true ]]; then
    if [[ -n "${INFRA:-}" ]]; then emit infra "CI infrastructure changed: ${INFRA%%$'\n'*}"
    else emit full "full run: label full-ci"; fi
    return
  fi
  # L3: only an explicit draft defers; an unknown flag runs (more work, never less).
  if [[ "${PR_DRAFT:-}" == true ]]; then
    write_skip_env deferred "draft PR: Test deferred, mark ready to run tests"
    emit deferred "draft PR: Test deferred, mark ready to run tests"
    return
  fi
  if [[ -n "${INFRA:-}" ]]; then emit infra "CI infrastructure changed: ${INFRA%%$'\n'*}"; return; fi
  if [[ -z "${BASE_SHA:-}" || -z "${HEAD_SHA:-}" ]]; then emit full "full run: pull_request without base/head sha"; return; fi
  emit selective "pull_request diff" "$BASE_SHA" "$HEAD_SHA"
}

# lookup_verified: 0 + prints "verified|<reason>" or "selective|<reason>"; 1 + prints "<reason>" = full.
lookup_verified() {
  local branch=$REF_NAME sha=$SHA prs merged n pr head_sha head_repo runs run run_id run_suite conclusion checks name latest suites state tmp tree head_tree
  prs=$(gh api "repos/$REPO/commits/$sha/pulls" 2>/dev/null) || { echo "API error: commits/$sha/pulls"; return 1; }
  [[ "$(jq -r 'type == "array"' <<<"$prs" 2>/dev/null)" == true ]] || { echo "API error: malformed pulls JSON"; return 1; }
  merged=$(jq -c --arg b "$branch" '[.[] | select(.merged_at != null and .base.ref == $b)]' <<<"$prs" 2>/dev/null) \
    || { echo "API error: malformed pulls JSON"; return 1; }
  n=$(jq 'length' <<<"$merged")
  [[ "$n" == 1 ]] || { echo "doubt: $n merged PRs into $branch for $sha"; return 1; }
  pr=$(jq -r '.[0].number' <<<"$merged")
  head_sha=$(jq -r '.[0].head.sha // ""' <<<"$merged")
  head_repo=$(jq -r '.[0].head.repo.full_name // ""' <<<"$merged")
  [[ "$head_repo" == "$REPO" ]] || { echo "fork or deleted head repo for PR #$pr"; return 1; }
  [[ "$pr" =~ ^[1-9][0-9]*$ && "$head_sha" =~ ^[0-9a-f]{40}$ ]] || { echo "malformed PR data"; return 1; }

  runs=$(gh api "repos/$REPO/actions/runs?event=pull_request&head_sha=$head_sha&per_page=100" 2>/dev/null) \
    || { echo "API error: actions/runs"; return 1; }
  # shellcheck disable=SC2016  # $pr is a jq variable
  run=$(jq -c --arg pr "$pr" '[.workflow_runs[]? | select(((.path // "") as $p | $p == ".github/workflows/pr-validate.yml" or ($p | startswith(".github/workflows/pr-validate.yml@")))
          and (.display_title // "" | startswith("pr-" + $pr + " ")))] | sort_by(.created_at) | last // empty' <<<"$runs" 2>/dev/null) \
    || { echo "API error: malformed runs JSON"; return 1; }
  [[ -n "$run" ]] || { echo "no pr-validate run found for PR #$pr"; return 1; }
  run_id=$(jq -r '.id' <<<"$run"); run_suite=$(jq -r '.check_suite_id // ""' <<<"$run")
  conclusion=$(jq -r '.conclusion // ""' <<<"$run")
  [[ "$run_id" =~ ^[0-9]+$ && "$run_suite" =~ ^[0-9]+$ ]] || { echo "malformed run id"; return 1; }
  if [[ "$conclusion" != success ]]; then echo "selective|latest PR #$pr run $run_id concluded '${conclusion:-pending}'"; return 0; fi

  # filter=all: the default `latest` keeps one run per name, so a newer label-noop mirror / re-run CI Gate
  # would hide the PR run's own. --paginate: a head can carry more than 100 check runs. Every page must be
  # an object with a check_runs array; the pages merge into one (an empty body = no page = malformed).
  checks=$(gh api "repos/$REPO/commits/$head_sha/check-runs?filter=all&per_page=100" --paginate 2>/dev/null) || { echo "API error: check-runs"; return 1; }
  checks=$(jq -cs 'if length > 0 and all(.[]; type == "object" and (.check_runs | type) == "array")
                   then {check_runs: [.[].check_runs[]]} else error("malformed") end' <<<"$checks" 2>/dev/null) \
    || { echo "API error: malformed check-runs JSON"; return 1; }
  for name in "${REQUIRED_CHECKS[@]}"; do
    if [[ "$name" == "CI Gate" ]]; then
      suites="[$run_suite]"
    else
      # shellcheck disable=SC2016  # $p/$h are jq variables
      suites=$(jq -c --arg wf "${CHECK_WORKFLOW[$name]}" --arg h "$head_sha" '[.workflow_runs[]?
          | select(((.path // "") as $p | $p == $wf or ($p | startswith($wf + "@"))) and .head_sha == $h)
          | .check_suite_id | numbers]' <<<"$runs" 2>/dev/null) || { echo "API error: malformed runs JSON"; return 1; }
    fi
    # Q4: a jq error on any element (non-object, odd field types) is doubt -> full, never selective.
    # shellcheck disable=SC2016  # $n/$s are jq variables
    latest=$(jq -c --arg n "$name" --argjson s "$suites" '[.check_runs[]
        | select(.name == $n and (.app.slug // "") == "github-actions" and (.check_suite.id as $id | any($s[]; . == $id)))]
        | sort_by(.id) | last // empty' <<<"$checks" 2>/dev/null) || { echo "API error: malformed check-runs JSON"; return 1; }
    if [[ -z "$latest" ]]; then
      echo "selective|required check '$name' has no github-actions run from ${CHECK_WORKFLOW[$name]} on PR #$pr head"; return 0
    fi
    state=$(jq -r '.conclusion // ""' <<<"$latest" 2>/dev/null) || { echo "API error: malformed check-runs JSON"; return 1; }
    if [[ "$state" != success && ! ( "$name" == "$SKIPPABLE_CHECK" && "$state" == skipped ) ]]; then
      echo "selective|required check '$name' is not success on PR #$pr head (${state:-pending})"; return 0
    fi
  done

  tmp=$(mktemp -d)
  # shellcheck disable=SC2064
  trap "rm -rf '$tmp'" RETURN
  gh run download "$run_id" -R "$REPO" -n selection -D "$tmp" >/dev/null 2>&1 || { echo "selection artifact of run $run_id unavailable"; return 1; }
  [[ -f "$tmp/tree.txt" && ! -L "$tmp/tree.txt" ]] || { echo "run $run_id recorded no tree.txt"; return 1; }
  tree=$(head -c 200 "$tmp/tree.txt" | tr -d '[:space:]')
  [[ "$tree" =~ ^[0-9a-f]{40}$ ]] || { echo "malformed tree.txt in run $run_id"; return 1; }
  head_tree=$(git rev-parse "$sha^{tree}" 2>/dev/null) || { echo "cannot resolve $sha^{tree}"; return 1; }
  if [[ "$tree" == "$head_tree" ]]; then
    echo "verified|tree $head_tree already passed the required set in PR #$pr run $run_id"
  else
    echo "selective|tree differs from PR #$pr run $run_id (base moved)"
  fi
}

decide_push() {
  local before=${BEFORE:-} sha=${SHA:-} infra out
  [[ "$sha" =~ ^[0-9a-f]{40}$ ]] || { emit full "full run: push (no head sha)"; return; }
  if [[ -z "$before" || "$before" == "$ZERO_SHA" ]] || ! git cat-file -e "$before^{commit}" 2>/dev/null; then
    emit full "full run: push (before '${before:-}' zero or unreachable)"; return
  fi
  [[ -n "${INFRA_RE:-}" ]] || { emit full "full run: push (no infra regex)"; return; }
  if ! out=$(git -c core.quotepath=false diff -z --no-renames --name-only "$before" "$sha" 2>/dev/null | tr '\0' '\n'; exit "${PIPESTATUS[0]}"); then
    emit full "full run: push (diff $before..$sha failed)"; return
  fi
  infra=$(grep -E "$INFRA_RE" <<<"$out" | head -1 || true)
  if [[ -n "$infra" ]]; then emit full "full run: push (CI infrastructure changed: $infra)"; return; fi
  if ! out=$(lookup_verified); then emit full "full run: push ($out)"; return; fi
  case "${out%%|*}" in
    verified)
      write_skip_env verified "${out#*|}"
      emit verified "${out#*|}" ;;
    selective) emit selective "push diff: ${out#*|}" "$before" "$sha" ;;
    *) emit full "full run: push (lookup gave '$out')" ;;
  esac
}

main() {
  case "${EVENT_NAME:-}" in
    pull_request) decide_pr ;;
    push) decide_push ;;
    *) emit full "full run: ${EVENT_NAME:-unknown event}" ;;
  esac
}

main "$@"
