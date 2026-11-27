#!/usr/bin/env bash
# Phase 567 Q1/Q2/Q4 + L3 behaviour test of the Plan job's "Select tests" step as WRITTEN in
# .github/workflows/pr-validate.yml (the YAML around plan-decide.sh, which plan-decide-test.sh cannot see).
#   scripts/ci/tests/plan-select-parity-test.sh [repo-root]
# Runs the step's `run:` block in a fixture repo (merge commit checkout, base-trusted archive) with a stub
# select-tests.sh that records argv + the env it reads, and a fake `gh` that records every call.
#  * Q2: for every READY non-promotion PR shape, and for workflow_dispatch / nightly, the selector call and
#        every step output are BYTE-IDENTICAL to the frozen pre-567 step (PRE567 below, from 1cb3020d).
#  * L3: a draft defers only the selector (no call, mode=deferred); `full-ci` on a draft = the pre-567 run.
#  * Q1: the promotion PR (main <- dev) calls the selector --force-full, draft or ready.
#  * Q4: plan-decide failing / empty / unknown route -> --force-full; a push diff selecting nothing -> full.
#  * no `gh` call on any pull_request / dispatch route, and the step's GH_TOKEN (evaluated from the YAML `env:`
#    expression, never passed by hand) is empty there; push still gets it.
# Falsify: change the routing in the step or plan-decide.sh for a ready PR and a parity row goes red.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "${1:-$HERE/../../..}" && pwd)"
python3 -c 'import yaml' 2>/dev/null || { echo "plan-select-parity-test: python3 + PyYAML required" >&2; exit 1; }
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# ---- the frozen pre-567 step (pr-validate.yml @ 1cb3020d, jobs.plan.steps[id=select].run) ----
PRE567_SHA256=50128f8cfc948229e57f287c1273de8c7c65938b227c503d589042a357afc8d6
cat >"$work/old.sh" <<'PRE567_SELECT_STEP'
set -euo pipefail
# Selector outputs live OUTSIDE the PR tree (a committed build/ci* symlink must not redirect a write).
export CI_OUT_DIR="$RUNNER_TEMP/ci-out" PROJECT_ROOT="$GITHUB_WORKSPACE"
trusted="$GITHUB_WORKSPACE"      # tree whose script + selector decide; the PR tree itself except on PRs
trusted_sha="$HEAD_COMMIT"
infra=""
forbid_script="$GITHUB_WORKSPACE/scripts/forbid_th_utext_in_email.sh"   # non-PR events: the pushed/dispatched ref itself
if [[ "$EVENT_NAME" == "pull_request" ]]; then
  read -r -a parents <<<"$(git rev-list --parents -n 1 HEAD)"
  [[ ${#parents[@]} -eq 3 ]] || { echo "::error::pull_request checkout is not a merge commit"; exit 1; }
  base_sha=${parents[1]}
  # CI-infrastructure guard, deliberately NOT part of the selector: computed from the git
  # diff in plain shell so no edit to selector/rules/script can switch it off.
  # Everything SelectorRules treats as ALWAYS_FULL that can alter HOW tests run (build wiring,
  # test resources, shared test bases/support/config, docker). Kept in step with
  # SelectorRules + .github/CODEOWNERS by CiInfraCoverageTest. -z + quotepath=false: NUL-separated
  # raw paths, so non-ASCII file names are matched instead of arriving as "\320\260..." octal.
  infra_re='^(\.github/|scripts/ci/|scripts/forbid_th_utext_in_email\.sh$|src/test/java/com/beautica/ci/|build\.gradle\.kts$|settings\.gradle\.kts$|gradle\.properties$|gradle/|gradlew|docker/|Dockerfile|src/test/resources/|src/test/java/com/beautica/([^/]+\.java$|(.*/)?Abstract[^/]*\.java$|(.*/)?support/|(.*/)?[^/]*Support\.java$|(.*/)?Test[^/]*Config[^/]*\.java$))'
  infra=$(git -c core.quotepath=false diff -z --no-renames --name-only "$base_sha" HEAD \
    | grep -zE "$infra_re" | tr '\0' '\n' || true)
  if git cat-file -e "$base_sha:scripts/ci/select-tests.sh" 2>/dev/null; then
    trusted="$RUNNER_TEMP/trusted"; trusted_sha="$base_sha"
    mkdir -p "$trusted"
    extract=(scripts/ci src/test/java/com/beautica/ci)
    forbid_script=""
    if git cat-file -e "$base_sha:scripts/forbid_th_utext_in_email.sh" 2>/dev/null; then
      extract+=(scripts/forbid_th_utext_in_email.sh)
      forbid_script="$trusted/scripts/forbid_th_utext_in_email.sh"
    fi
    git archive "$base_sha" "${extract[@]}" | tar -x -C "$trusted"
    if [[ -n "$forbid_script" ]]; then
      # The guard resolves the templates relative to ITS OWN location: point that at the PR's
      # templates (data only; grep never executes them).
      mkdir -p "$trusted/src/main/resources/templates"
      ln -s "$GITHUB_WORKSPACE/src/main/resources/templates/email" "$trusted/src/main/resources/templates/email"
    fi
  else
    forbid_script=""
    # Bootstrap: the base has no selector yet (the PR introducing it). Nothing to trust, so
    # use the PR tree and force a full run.
    infra="${infra:-bootstrap: base has no scripts/ci}"
  fi
fi
if [[ "$EVENT_NAME" != "pull_request" ]]; then
  "$trusted/scripts/ci/select-tests.sh" --force-full "full run: $EVENT_NAME"
elif [[ -n "$infra" ]]; then
  # The self-test job runs com.beautica.ci.* on this path; do not run it a second time in the shards.
  EXCLUDE_PREFIX=com.beautica.ci. "$trusted/scripts/ci/select-tests.sh" \
    --force-full "CI infrastructure changed: $(head -1 <<<"$infra")" true
elif [[ "$FULL_CI_LABEL" == "true" ]]; then
  "$trusted/scripts/ci/select-tests.sh" --force-full "full run: label full-ci"
else
  # BASE_SHA..HEAD is exactly what the test jobs check out and run (the merge commit).
  BASE_SHA="$base_sha" HEAD_SHA="$(git rev-parse HEAD)" "$trusted/scripts/ci/select-tests.sh"
fi
# Phase 366: ops/db-backup has its own jobs (shellcheck + DbBackupRoundTripIT). They run on a PR only when
# that directory or the IT's package (src/test/java/com/beautica/ops/) changed (plain git diff, like `infra`
# above) and always on the nightly dispatch. `ops/**`
# is a NO_TEST path for the selector, so it never forces a backend run, and the IT's tag keeps it out of the shards.
db_backup=false
if [[ "$EVENT_NAME" == "workflow_dispatch" && "$RUN_REASON" == "nightly" ]]; then
  db_backup=true
elif [[ "$EVENT_NAME" == "pull_request" ]]; then
  ops_changed=$(git -c core.quotepath=false diff -z --no-renames --name-only "$base_sha" HEAD \
    | grep -zE '^(ops/db-backup/|src/test/java/com/beautica/ops/)' | tr '\0' '\n' || true)
  if [[ -n "$ops_changed" ]]; then db_backup=true; fi
fi
echo "db_backup=$db_backup" >> "$GITHUB_OUTPUT"
# selection.env is KEY=VALUE per line (REASON is single-line); publish as lower-case outputs.
while IFS='=' read -r key value; do
  echo "${key,,}=$value" >> "$GITHUB_OUTPUT"
done < "$RUNNER_TEMP/ci-out/selection.env"
echo "trusted_sha=$trusted_sha" >> "$GITHUB_OUTPUT"
echo "forbid_script=$forbid_script" >> "$GITHUB_OUTPUT"
if [[ -n "$forbid_script" ]]; then echo "forbid_in_plan=true" >> "$GITHUB_OUTPUT"; else echo "forbid_in_plan=false" >> "$GITHUB_OUTPUT"; fi
PRE567_SELECT_STEP
got=$(sha256sum "$work/old.sh" | cut -d' ' -f1)
[[ "$got" == "$PRE567_SHA256" ]] || { echo "FAIL frozen pre-567 step altered ($got)"; exit 1; }
if git -C "$ROOT" cat-file -e 1cb3020d:.github/workflows/pr-validate.yml 2>/dev/null; then
  git -C "$ROOT" show 1cb3020d:.github/workflows/pr-validate.yml >"$work/old.yml"
  OLD_YML="$work/old.yml" OLD_SH="$work/old.sh" python3 -I - <<'PY' || { echo "FAIL frozen step differs from 1cb3020d"; exit 1; }
import os, sys, yaml
d = yaml.safe_load(open(os.environ["OLD_YML"], encoding="utf-8"))
s = next(s for s in d["jobs"]["plan"]["steps"] if s.get("id") == "select")["run"]
sys.exit(0 if s == open(os.environ["OLD_SH"], encoding="utf-8").read() else 1)
PY
fi
NEW_SH="$work/new.sh" TOKEN_EXPR="$work/token.expr" ROOT="$ROOT" python3 -I - <<'PY'
import os, yaml
d = yaml.safe_load(open(os.path.join(os.environ["ROOT"], ".github/workflows/pr-validate.yml"), encoding="utf-8"))
step = next(s for s in d["jobs"]["plan"]["steps"] if s.get("id") == "select")
open(os.environ["NEW_SH"], "w", encoding="utf-8").write(step["run"])
open(os.environ["TOKEN_EXPR"], "w", encoding="utf-8").write(str((step.get("env") or {}).get("GH_TOKEN", "")))
PY
# The step's GH_TOKEN as GitHub would evaluate it; only these two shapes are understood (anything else fails).
# shellcheck disable=SC2016  # literal workflow expressions
case "$(cat "$work/token.expr")" in
  '${{ github.event_name == '"'push'"' && github.token || '"''"' }}') TOKEN_SCOPE=push ;;
  '${{ github.token }}') TOKEN_SCOPE=always ;;
  *) echo "FAIL Select tests GH_TOKEN expression not understood: $(cat "$work/token.expr")"; exit 1 ;;
esac

# ---- stubs ----
mkdir -p "$work/stub" "$work/bin"
cat >"$work/stub/select-tests.sh" <<'EOF'
#!/usr/bin/env bash
# stub selector: record the call, write a selection.env like the real one.
{
  printf 'argv:'; printf ' [%s]' "$@"; printf '\n'
  for v in BASE_SHA HEAD_SHA EXCLUDE_PREFIX FULL_SHARDS SELECTIVE_SHARD_THRESHOLD SELECTOR_CLASSES REPLAY_COMMITS PROJECT_ROOT CI_OUT_DIR; do
    printf '  %s=%s\n' "$v" "${!v-<unset>}"
  done
  printf '  pwd=%s\n' "$PWD"
} >>"$SEL_LOG"
printf '%s\n' "${GH_TOKEN-<unset>}" >>"$SEL_LOG.token"
mkdir -p "$CI_OUT_DIR"
# SEL_STUB_MODE fakes a diff result only; --force-full is always full (as in the real selector).
mode=${SEL_STUB_MODE:-selective}; [[ "${1:-}" == --force-full ]] && mode=full
: >"$CI_OUT_DIR/selected-tests.txt"; : >"$CI_OUT_DIR/report-aliases.txt"
printf 'MODE=%s\nREASON=%q\nCOUNT=1\nTOTAL=9\nSHARDS=1\nSHARD_MATRIX=[1]\nCI_CHANGED=%s\nSHARD_WEIGHTS=1\n' \
  "$mode" "${2:-diff}" "${3:-false}" >"$CI_OUT_DIR/selection.env"
EOF
cat >"$work/bin/gh" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"$GH_LOG"; printf '%s\n' "${GH_TOKEN-<unset>}" >>"$GH_LOG.token"; echo "fake gh: offline" >&2; exit 1
EOF
chmod +x "$work/stub/select-tests.sh" "$work/bin/gh"

# ---- fixture repo ----
R="$work/repo"
git init -q "$R"; cd "$R"
git config user.email t@example.invalid; git config user.name t; git config commit.gpgsign false
commit() { git add -A; git commit -qm "$1"; git rev-parse HEAD; }
mkdir -p scripts/ci src/main/java/x src/test/java/com/beautica/ci
echo 'class S {}' >src/test/java/com/beautica/ci/S.java
cp "$work/stub/select-tests.sh" scripts/ci/select-tests.sh
cp "$ROOT/scripts/ci/plan-decide.sh" scripts/ci/plan-decide.sh
echo 'class A {}' >src/main/java/x/A.java
BASE=$(commit base)
# merge <name> <base> <change-cmd>: a PR head off <base> and its merge commit (parents: base, head)
merge() {
  git checkout -q --detach "$2"; eval "$3"; local head; head=$(commit "$1")
  git checkout -q --detach "$2"; git merge -q --no-ff -m "merge $1" "$head"; git rev-parse HEAD
}
M_APP=$(merge app "$BASE" 'echo "class A { int a; }" >src/main/java/x/A.java')
M_INFRA=$(merge infra "$BASE" 'echo "// x" >>build.gradle.kts')
M_TWOINFRA=$(merge twoinfra "$BASE" 'echo "// x" >>build.gradle.kts; mkdir -p docker; echo x >docker/f')
M_OPS=$(merge ops "$BASE" 'mkdir -p ops/db-backup; echo x >ops/db-backup/run.sh')
git checkout -q --detach "$BASE"; git rm -q scripts/ci/plan-decide.sh; BASE_NOPD=$(commit no-plan-decide)
M_NOPD_APP=$(merge nopd-app "$BASE_NOPD" 'echo "class A { int b; }" >src/main/java/x/A.java')
git checkout -q --detach "$BASE"; git rm -q -r scripts/ci; BASE_NOCI=$(commit no-ci)
M_BOOT=$(merge boot "$BASE_NOCI" 'mkdir -p scripts/ci; cp "$work/stub/select-tests.sh" scripts/ci/select-tests.sh; cp "$ROOT/scripts/ci/plan-decide.sh" scripts/ci/plan-decide.sh')
# bases whose (trusted) plan-decide.sh misbehaves
pd_base() { git checkout -q --detach "$BASE"; printf '%s\n' '#!/usr/bin/env bash' "$2" >scripts/ci/plan-decide.sh; commit "$1" >/dev/null; git rev-parse HEAD; }
B_PDFAIL=$(pd_base pd-fail 'echo ROUTE=selective; exit 3')
B_PDEMPTY=$(pd_base pd-empty 'exit 0')
B_PDBOGUS=$(pd_base pd-bogus 'printf "ROUTE=bogus\nREASON=\nBASE_SHA=\nHEAD_SHA=\n"')
B_PDDEFER=$(pd_base pd-defer-noenv 'printf "ROUTE=deferred\nREASON=x\nBASE_SHA=\nHEAD_SHA=\n"')
M_PDFAIL=$(merge pdfail-app "$B_PDFAIL" 'echo "class A { int c; }" >src/main/java/x/A.java')
M_PDEMPTY=$(merge pdempty-app "$B_PDEMPTY" 'echo "class A { int c; }" >src/main/java/x/A.java')
M_PDBOGUS=$(merge pdbogus-app "$B_PDBOGUS" 'echo "class A { int c; }" >src/main/java/x/A.java')
M_PDDEFER=$(merge pddefer-app "$B_PDDEFER" 'echo "class A { int c; }" >src/main/java/x/A.java')
B_PUSHSEL=$(pd_base pd-push-selective 'printf "ROUTE=selective\nREASON=push diff\nBASE_SHA=%s\nHEAD_SHA=%s\n" "$BEFORE" "$SHA"')

# run_step <old|new> <tag> <checkout> KEY=VALUE... ; leaves $work/res/<tag>.{sel,out,gh,rc}
run_step() {
  local which=$1 tag=$2 at=$3; shift 3
  local rt="$work/rt" rc=0 kv event="" token=""
  rm -rf "$rt"; mkdir -p "$rt" "$work/res"
  git -C "$R" checkout -q --detach "$at"
  : >"$work/res/$tag.sel"; : >"$work/res/$tag.out"; : >"$work/res/$tag.gh"; : >"$work/res/$tag.sel.token"; : >"$work/res/$tag.gh.token"
  # GH_TOKEN comes from the step's own env expression (TOKEN_SCOPE), never from the row.
  for kv in "$@"; do [[ "$kv" == EVENT_NAME=* ]] && event=${kv#EVENT_NAME=}; done
  if [[ "$TOKEN_SCOPE" == always || "$event" == push ]]; then token=fake-token; fi
  (cd "$R" && env -i PATH="$work/bin:$PATH" HOME="$HOME" GITHUB_WORKSPACE="$R" RUNNER_TEMP="$rt" \
      GITHUB_OUTPUT="$work/res/$tag.out" SEL_LOG="$work/res/$tag.sel" GH_LOG="$work/res/$tag.gh" \
      HEAD_COMMIT="$(git -C "$R" rev-parse HEAD)" GH_TOKEN="$token" "$@" \
      bash --noprofile --norc -eo pipefail "$work/$which.sh" >"$work/res/$tag.log" 2>&1) || rc=$?
  echo "$rc" >"$work/res/$tag.rc"
}

pass=0; failures=0
fail() { echo "FAIL $1: $2"; failures=$((failures + 1)); }
okk() { echo "ok   $1"; pass=$((pass + 1)); }
show() { sed 's/^/    /' "$work/res/$1.$2"; }

# parity <name> <checkout> KEY=VALUE...: old and new step give the identical selector call + outputs, no gh call.
parity() {
  local name=$1 at=$2; shift 2
  run_step old "$name.old" "$at" "$@"; run_step new "$name.new" "$at" "$@"
  local r=$work/res/$name
  if [[ "$(cat "$r.old.rc")" != 0 || "$(cat "$r.new.rc")" != 0 ]]; then
    fail "$name" "step failed (old rc $(cat "$r.old.rc"), new rc $(cat "$r.new.rc"))"; show "$name.new" log; return; fi
  if [[ ! -s "$r.old.sel" ]]; then fail "$name" "vacuous: pre-567 step did not call the selector"; return; fi
  if ! cmp -s "$r.old.sel" "$r.new.sel"; then
    fail "$name" "selector call differs from pre-567"; diff "$r.old.sel" "$r.new.sel" | sed 's/^/    /'; return; fi
  if ! cmp -s "$r.old.out" "$r.new.out"; then
    fail "$name" "step outputs differ from pre-567"; diff "$r.old.out" "$r.new.out" | sed 's/^/    /'; return; fi
  if [[ -s "$r.new.gh" ]]; then fail "$name" "gh called: $(head -1 "$r.new.gh")"; return; fi
  if grep -qv '^$' "$r.new.sel.token"; then fail "$name" "GH_TOKEN visible off the push route: $(head -1 "$r.new.sel.token")"; return; fi
  okk "$name"
}

# expect <name> <want-rc> <want-mode|-> <want-sel-line-or-NONE> <checkout> KEY=VALUE...  (new step only)
expect() {
  local name=$1 wrc=$2 wmode=$3 wsel=$4 at=$5; shift 5
  run_step new "$name" "$at" "$@"
  local r=$work/res/$name rc
  rc=$(cat "$r.rc")
  if [[ "$wrc" == 0 && "$rc" != 0 ]] || [[ "$wrc" != 0 && "$rc" == 0 ]]; then fail "$name" "want rc $wrc, got $rc"; show "$name" log; return; fi
  if [[ "$wmode" != - ]] && ! grep -qx "mode=$wmode" "$r.out"; then fail "$name" "want mode=$wmode"; show "$name" out; return; fi
  if [[ "$wsel" == NONE ]]; then
    [[ ! -s "$r.sel" ]] || { fail "$name" "selector must not run"; show "$name" sel; return; }
  elif ! grep -qxF -- "$wsel" "$r.sel"; then fail "$name" "selector call lacks '$wsel'"; show "$name" sel; return; fi
  if [[ "${GH_ALLOWED:-}" != true && -s "$r.gh" ]]; then fail "$name" "gh called: $(head -1 "$r.gh")"; return; fi
  okk "$name"
}

PR=(EVENT_NAME=pull_request REPO=o/r PR_BASE_REF=dev PR_HEAD_REF=feat/x PR_HEAD_REPO=o/r)
READY=("${PR[@]}" PR_DRAFT=false FULL_CI_LABEL=false)

# ---- Q2: ready PRs + dispatch are byte-identical to pre-567 ----
parity should_matchPre567_when_readyPrAppDiff "$M_APP" "${READY[@]}"
parity should_matchPre567_when_readyPrInfraDiff "$M_INFRA" "${READY[@]}"
parity should_matchPre567_when_readyPrTwoInfraPaths "$M_TWOINFRA" "${READY[@]}"
parity should_matchPre567_when_readyPrLabelledFullCi "$M_APP" "${PR[@]}" PR_DRAFT=false FULL_CI_LABEL=true
parity should_matchPre567_when_readyPrLabelAndInfra "$M_INFRA" "${PR[@]}" PR_DRAFT=false FULL_CI_LABEL=true
parity should_matchPre567_when_readyForkPr "$M_APP" "${READY[@]}" PR_HEAD_REPO=fork/r
parity should_matchPre567_when_readyForkPrNamedDevIntoMain "$M_APP" "${READY[@]}" PR_HEAD_REPO=fork/r PR_BASE_REF=main PR_HEAD_REF=dev
parity should_matchPre567_when_readyPrIntoMainFromFeature "$M_APP" "${READY[@]}" PR_BASE_REF=main
parity should_matchPre567_when_readyPrTouchesDbBackup "$M_OPS" "${READY[@]}"
parity should_matchPre567_when_draftFlagUnknown "$M_APP" "${PR[@]}" FULL_CI_LABEL=false
parity should_matchPre567_when_readyPrSelectorSelectsNone "$M_APP" "${READY[@]}" SEL_STUB_MODE=none
parity should_matchPre567_when_baseLacksPlanDecide "$M_NOPD_APP" "${READY[@]}"
parity should_matchPre567_when_bootstrapBaseLacksScriptsCi "$M_BOOT" "${READY[@]}"
parity should_matchPre567_when_draftLabelledFullCi "$M_APP" "${PR[@]}" PR_DRAFT=true FULL_CI_LABEL=true
parity should_matchPre567_when_dispatchNightly "$BASE" EVENT_NAME=workflow_dispatch RUN_REASON=nightly REPO=o/r REF_NAME=main
parity should_matchPre567_when_dispatchManual "$BASE" EVENT_NAME=workflow_dispatch RUN_REASON=manual REPO=o/r REF_NAME=dev
parity should_matchPre567_when_scheduleEvent "$BASE" EVENT_NAME=schedule REPO=o/r

# ---- L3: a draft defers only the selector ----
expect should_deferWithoutSelector_when_draftPr 0 deferred NONE "$M_APP" "${PR[@]}" PR_DRAFT=true FULL_CI_LABEL=false
expect should_deferWithoutSelector_when_draftPrTouchesInfra 0 deferred NONE "$M_INFRA" "${PR[@]}" PR_DRAFT=true FULL_CI_LABEL=false
run_step new draft-trusted "$M_APP" "${PR[@]}" PR_DRAFT=true FULL_CI_LABEL=false
if grep -qx "trusted_sha=$BASE" "$work/res/draft-trusted.out" && grep -qx 'count=0' "$work/res/draft-trusted.out" \
   && grep -qx 'ci_changed=false' "$work/res/draft-trusted.out"; then okk should_keepTrustedBaseAndEmptySelection_when_draftPr
else fail should_keepTrustedBaseAndEmptySelection_when_draftPr "outputs"; show draft-trusted out; fi

# ---- Q1: promotion PR always full ----
PROMO=(EVENT_NAME=pull_request REPO=o/r PR_BASE_REF=main PR_HEAD_REF=dev PR_HEAD_REPO=o/r FULL_CI_LABEL=false)
expect should_forceFull_when_promotionReady 0 full "argv: [--force-full] [full run: promotion PR dev -> main]" "$M_APP" "${PROMO[@]}" PR_DRAFT=false
expect should_forceFull_when_promotionDraft 0 full "argv: [--force-full] [full run: promotion PR dev -> main]" "$M_APP" "${PROMO[@]}" PR_DRAFT=true
expect should_forceFull_when_promotionSelectorWouldSelectNone 0 full "argv: [--force-full] [full run: promotion PR dev -> main]" "$M_APP" "${PROMO[@]}" PR_DRAFT=false SEL_STUB_MODE=none
expect should_forceFullInfra_when_promotionDraftTouchesInfra 0 full "  EXCLUDE_PREFIX=com.beautica.ci." "$M_INFRA" "${PROMO[@]}" PR_DRAFT=true

# ---- Q4: every doubt in the step itself -> full ----
expect should_forceFull_when_planDecideFails 0 full "argv: [--force-full] [full run: plan-decide.sh failed]" "$M_PDFAIL" "${READY[@]}"
expect should_forceFull_when_planDecidePrintsNothing 0 full "argv: [--force-full] [full run: pull_request (no route)]" "$M_PDEMPTY" "${READY[@]}"
expect should_forceFull_when_routeUnknown 0 full "argv: [--force-full] [full run: pull_request (no route)]" "$M_PDBOGUS" "${READY[@]}"
expect should_failPlan_when_deferredRouteWithoutSelectionEnv 1 - NONE "$M_PDDEFER" "${READY[@]}"
grep -qF "route deferred without its selection.env" "$work/res/should_failPlan_when_deferredRouteWithoutSelectionEnv.log" \
  && okk should_nameMissingSelectionEnv_when_deferredRouteFailsPlan \
  || fail should_nameMissingSelectionEnv_when_deferredRouteFailsPlan "plan failed for another reason (vacuous)"
git -C "$R" checkout -q --detach "$B_PUSHSEL"; echo 'class A { int p; }' >"$R/src/main/java/x/A.java"; PUSH_TIP=$(cd "$R" && commit push-tip)
expect should_upgradeToFull_when_pushDiffSelectsNone 0 full "argv: [--force-full] [full run: push diff selected no test class]" "$PUSH_TIP" \
  EVENT_NAME=push REPO=o/r REF_NAME=dev PUSH_BEFORE="$B_PUSHSEL" SEL_STUB_MODE=none
expect should_runSelectiveOnPushRange_when_routeSelective 0 selective "  BASE_SHA=$B_PUSHSEL" "$PUSH_TIP" \
  EVENT_NAME=push REPO=o/r REF_NAME=dev PUSH_BEFORE="$B_PUSHSEL"
git -C "$R" checkout -q --detach "$BASE"; echo 'class A { int q; }' >"$R/src/main/java/x/A.java"; PUSH_REAL=$(cd "$R" && commit push-real)
GH_ALLOWED=true expect should_forceFull_when_pushLookupApiDown 0 full "argv: [--force-full] [full run: push (API error: commits/$PUSH_REAL/pulls)]" "$PUSH_REAL" \
  EVENT_NAME=push REPO=o/r REF_NAME=dev PUSH_BEFORE="$BASE"
if [[ "$(cat "$work/res/should_forceFull_when_pushLookupApiDown.gh.token")" == fake-token ]]; then okk should_passTokenToGh_when_push
else fail should_passTokenToGh_when_push "push lookup gh saw GH_TOKEN '$(head -1 "$work/res/should_forceFull_when_pushLookupApiDown.gh.token")'"; fi
for t in should_forceFull_when_promotionReady should_forceFull_when_planDecideFails; do
  n="should_hideToken_when_${t#should_forceFull_when_}"
  if [[ ! -s "$work/res/$t.sel.token" ]]; then fail "$n" "vacuous: selector did not run"
  elif grep -qv '^$' "$work/res/$t.sel.token"; then fail "$n" "GH_TOKEN visible: $(head -1 "$work/res/$t.sel.token")"
  else okk "$n"; fi
done

# ---- L1: every route records the tested tree ----
run_step new tree "$M_APP" "${READY[@]}"
if [[ "$(cat "$work/rt/ci-out/tree.txt" 2>/dev/null)" == "$(git -C "$R" rev-parse "$M_APP^{tree}")" ]]; then okk should_recordMergeTree_when_readyPr
else fail should_recordMergeTree_when_readyPr "tree.txt != HEAD^{tree}"; fi

echo "plan-select-parity-test: $pass passed, $failures failed"
[[ "$failures" -eq 0 ]]
