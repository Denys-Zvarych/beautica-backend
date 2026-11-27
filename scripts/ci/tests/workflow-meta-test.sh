#!/usr/bin/env bash
# Phase 567 workflow/build-text meta test: TC-8 (L5 gate_changed, Q6), TC-10 (nightly, Q1), TC-11 (suite not
# reduced, Q2/Q7), TC-12 (fork-guarded runs-on, Q9) plus the wiring the other levers rely on.
#   scripts/ci/tests/workflow-meta-test.sh [repo-root]
# Needs python3 with PyYAML (preinstalled on GitHub's ubuntu images). Falsify: drop the fork guard on one
# job, re-add `gh run watch`, edit select-tests.sh, or widen the gate_changed path -> red.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "${1:-$HERE/../../..}" && pwd)"

# Q7/TC-11: byte pins. Update ONLY in a PR that intends to change the selector/verifier (owner review).
SELECT_TESTS_SHA256=a38edcb320d32e9387badb787beadb2c93a9cacda488394238f6b566b6541750
VERIFY_SELECTED_RAN_SHA256=445242cec43393552b9889e5ec05e9f704e13bd5b8281f6d5100748532c9fb32

python3 -c 'import yaml' 2>/dev/null || { echo "workflow-meta-test: python3 + PyYAML required" >&2; exit 1; }

failures=0
check_sha() { # <file> <want>
  local got
  got=$(sha256sum "$ROOT/$1" | cut -d' ' -f1)
  if [[ "$got" == "$2" ]]; then echo "ok   should_matchPin_when_${1##*/}Hashed"
  else echo "FAIL ${1}: sha256 $got != pinned $2 (Q7: selector/verifier must stay byte-identical)"; failures=$((failures + 1)); fi
}
check_sha scripts/ci/select-tests.sh "$SELECT_TESTS_SHA256"
check_sha scripts/ci/verify-selected-ran.sh "$VERIFY_SELECTED_RAN_SHA256"
# select-tests.sh defaults (also covered by the pin; named here so a red pin says why it matters).
grep_line() { # <name> <file> <fixed line content>
  if grep -qF -- "$3" "$ROOT/$2"; then echo "ok   $1"; else echo "FAIL $1: $2 lacks '$3'"; failures=$((failures + 1)); fi
}
grep_line should_keepThreeFullShards_when_selectorRead scripts/ci/select-tests.sh 'FULL_SHARDS="${FULL_SHARDS:-3}"'
grep_line should_keepShardThreshold120_when_selectorRead scripts/ci/select-tests.sh 'SELECTIVE_SHARD_THRESHOLD="${SELECTIVE_SHARD_THRESHOLD:-120}"'
grep_line should_failOnNoMatchingTests_when_selectionApplied build.gradle.kts 'isFailOnNoMatchingTests = true'
grep_line should_keepExcludeTagSet_when_gradleRead build.gradle.kts 'if (project.hasProperty("dbBackupIt")) includeTags("db-backup-image") else excludeTags("db-backup-image")'
n=$(grep -cE 'excludeTags|includeTags' "$ROOT/build.gradle.kts")
if [[ "$n" == 1 ]]; then echo "ok   should_notAddTagFilters_when_gradleRead"; else echo "FAIL tag filters in build.gradle.kts: $n lines (Q7: no new exclusion)"; failures=$((failures + 1)); fi

ROOT="$ROOT" python3 -I - <<'PY' || failures=$((failures + 1))
import os, re, sys, yaml

root = os.environ["ROOT"]
wf_dir = os.path.join(root, ".github/workflows")
GUARD = ("${{ (github.event_name != 'pull_request' || github.event.pull_request.head.repo.full_name == github.repository)"
         " && vars.CI_RUNNER_LINUX || 'ubuntu-latest' }}")
fails = []
oks = 0

def ok(cond, name, msg):
    global oks
    if cond:
        oks += 1
        print("ok   " + name)
    else:
        fails.append(name + ": " + msg)
        print("FAIL " + name + ": " + msg)

def load(name):
    with open(os.path.join(wf_dir, name), encoding="utf-8") as f:
        text = f.read()
    doc = yaml.safe_load(text)
    on = doc.get("on", doc.get(True))
    if isinstance(on, str):
        on = {on: None}
    elif isinstance(on, list):
        on = {k: None for k in on}
    return text, doc, on or {}

files = sorted(f for f in os.listdir(wf_dir) if f.endswith((".yml", ".yaml")))
ok(len(files) >= 5, "should_scanWorkflows_when_listed", "only %d workflow files found" % len(files))
W = {f: load(f) for f in files}

# ---- TC-12 / Q9: runs-on on every job ----
jobs_seen = 0
for f, (text, doc, on) in W.items():
    prt = "pull_request_target" in on
    for jid, job in (doc.get("jobs") or {}).items():
        jobs_seen += 1
        r = job.get("runs-on")
        if prt:
            # The fork guard tests event_name == 'pull_request'; under pull_request_target a fork PR would pass
            # it and reach vars.CI_RUNNER_LINUX. Such jobs must name a literal GitHub-hosted label.
            ok(isinstance(r, str) and r == "ubuntu-latest" and "${{" not in r and "vars." not in r,
               "should_pinHostedRunner_when_%s:%s_isPullRequestTarget" % (f, jid),
               "pull_request_target job runs-on must be literal 'ubuntu-latest' (got %r)" % (r,))
        else:
            ok(r == GUARD, "should_forkGuardRunsOn_when_%s:%s" % (f, jid),
               "runs-on must be the fork-guarded expression byte-for-byte (got %r)" % (r,))
ok(jobs_seen >= 18, "should_scanJobs_when_workflowsParsed", "only %d jobs found (vacuous scan?)" % jobs_seen)
for f, (text, _, _) in W.items():
    for i, line in enumerate(text.splitlines(), 1):
        s = line.strip()
        if s.startswith("runs-on:") and ("self-hosted" in s or re.search(r"\bubuntu-\d", s)):
            ok(False, "should_notHardcodeRunner_when_%s:%d" % (f, i), "hard-coded runner label: " + s)

# ---- L9 / Q6: no workflow-level path filters (a required check would never report) ----
for f, (_, _, on) in W.items():
    for ev, cfg in on.items():
        bad = isinstance(cfg, dict) and ("paths" in cfg or "paths-ignore" in cfg)
        ok(not bad, "should_notPathFilter_when_%s:%s" % (f, ev), "paths/paths-ignore forbidden on triggers")

# ---- required check names unchanged (Q1; the ruleset side is the TC-9 dry run) ----
names = {f: {j.get("name") for j in (d.get("jobs") or {}).values()} for f, (_, d, _) in W.items()}
for f, n in [("pr-validate.yml", "CI Gate"), ("gitleaks.yml", "Scan for secrets"),
             ("migration-immutability.yml", "Check Migration Immutability"),
             ("migration-immutability.yml", "Check Migration Version Monotonicity"),
             ("migration-immutability.yml", "Verify Gate <-> Mirror Parity"),
             ("promotion-source.yml", "Promotion source (${{ github.event.pull_request.base.ref }})")]:
    ok(n in names.get(f, set()), "should_keepRequiredCheck_when_%s:%s" % (f, n), "required check job name missing")

# ---- pr-validate wiring (L1/L3/L6, Q2/Q3) ----
text, doc, on = W["pr-validate.yml"]
jobs = doc["jobs"]
ok("ready_for_review" in (on["pull_request"].get("types") or []), "should_triggerOnReadyForReview_when_prValidate", "types lack ready_for_review")
ok("report" not in jobs and all(j.get("name") != "Report" for j in jobs.values()), "should_mergeReportIntoVerify_when_prValidate", "a separate Report job still exists")
ok(any(s.get("name") == "Publish test results" and s.get("continue-on-error") is True and str(s.get("if")) == "always()"
       for s in jobs["verify"]["steps"]), "should_keepReportNonGating_when_mergedIntoVerify", "reporter step must be if: always() + continue-on-error")
FS = "needs.plan.outputs.mode == 'full' || needs.plan.outputs.mode == 'selective'"
ok(jobs["test"].get("if") == FS, "should_runTestOnlyFullOrSelective_when_planned", "test if: %r" % jobs["test"].get("if"))
ok(jobs["verify"].get("if") == "always() && (" + FS + ")", "should_runVerifyOnlyFullOrSelective_when_planned", "verify if changed")
ok(jobs["gates"].get("if") == FS + " || needs.plan.outputs.mode == 'deferred'", "should_compileDrafts_when_deferred", "gates if: %r" % jobs["gates"].get("if"))
ok(jobs["ci-gate"].get("needs") == ["plan", "gates", "selector-self-test", "test", "verify", "db-backup-shellcheck", "db-backup-it"],
   "should_keepGateNeeds_when_ciGate", "ci-gate needs changed")
ok(jobs["ci-gate"].get("if") == "always()", "should_alwaysReport_when_ciGate", "ci-gate must stay if: always()")
gate_run = "\n".join(s.get("run", "") for s in jobs["ci-gate"]["steps"])
ok("trusted/scripts/ci/gate-aggregate.sh" in gate_run, "should_aggregateWithTrustedScript_when_ciGate", "gate-aggregate.sh not called")
ok(any(s.get("uses", "").startswith("actions/checkout@") and (s.get("with") or {}).get("ref") == "${{ needs.plan.outputs.trusted-sha }}"
       for s in jobs["ci-gate"]["steps"]), "should_checkoutTrustedSha_when_ciGate", "ci-gate must check out plan's trusted-sha")
sel = next(s for s in jobs["plan"]["steps"] if s.get("id") == "select")["run"]
ok('"$trusted/scripts/ci/plan-decide.sh"' in sel, "should_routeWithTrustedScript_when_plan", "plan-decide.sh not run from $trusted")
ok("HEAD^{tree}' >\"$CI_OUT_DIR/tree.txt\"" in sel, "should_recordTestedTree_when_plan", "tree.txt not written")
up = next(s for s in jobs["plan"]["steps"] if s.get("name") == "Upload selection")
ok(up["with"].get("retention-days") == 30, "should_keepSelection30Days_when_uploaded", "selection retention must be 30 d")
ok(sel.index("select-tests.sh") > sel.index("plan-decide.sh"), "should_decideRouteBeforeSelector_when_plan", "route must precede selector")
sel_env = next(s for s in jobs["plan"]["steps"] if s.get("id") == "select").get("env") or {}
ok(sel_env.get("GH_TOKEN") == "${{ github.event_name == 'push' && github.token || '' }}",
   "should_passTokenOnlyOnPush_when_selectTests", "Select tests GH_TOKEN must be push-only: %r" % sel_env.get("GH_TOKEN"))
st_runs = [s.get("run", "").strip() for s in jobs["selector-self-test"]["steps"]]
for t in ["gate-aggregate-test.sh", "plan-decide-test.sh", "workflow-meta-test.sh", "plan-select-parity-test.sh"]:
    ok(st_runs.count("bash scripts/ci/tests/" + t) == 1, "should_runInSelfTest_when_%s" % t, "selector-self-test must run scripts/ci/tests/%s once" % t)
for j, jb in jobs.items():
    p = jb.get("permissions") or {}
    if isinstance(p, dict) and p.get("issues") == "write":
        ok(j == "nightly-report", "should_confineIssuesWrite_when_%s" % j, "only nightly-report may write issues")

# ---- L3 (Q2/Q3): a draft defers only Test (+ its Verify); nothing else reads the draft flag ----
mode_jobs = sorted(j for j, jb in jobs.items() if "plan.outputs.mode" in str(jb.get("if", "")))
ok(mode_jobs == ["gates", "test", "verify"], "should_gateOnlyGatesTestVerifyOnMode_when_prValidate", "mode-conditioned jobs: %r" % mode_jobs)
ok(text.count("pull_request.draft") == 1 and "PR_DRAFT: ${{ github.event.pull_request.draft }}" in text,
   "should_readDraftOnlyInPlan_when_prValidate", "draft flag read outside plan's route input")
for f, (t, _, _) in W.items():
    if f != "pr-validate.yml":
        ok("draft" not in t and "deferred" not in t, "should_notDeferOnDraft_when_%s" % f, "draft/deferred referenced")
agg = next(s for s in jobs["ci-gate"]["steps"] if s.get("name") == "Aggregate results")
ae = agg.get("env") or {}
ok(ae.get("IS_PROMOTION") == "${{ github.event_name == 'pull_request' && github.event.pull_request.base.ref == 'main' && github.event.pull_request.head.ref == 'dev' && github.event.pull_request.head.repo.full_name == github.repository }}",
   "should_flagPromotionExactly_when_ciGate", "IS_PROMOTION expression changed: %r" % ae.get("IS_PROMOTION"))
ok(ae.get("PR_NUMBER") == "${{ github.event.pull_request.number }}" and ae.get("GH_REPO") == "${{ github.repository }}" and ae.get("GH_TOKEN") == "${{ github.token }}",
   "should_passLiveDraftRecheckInputs_when_ciGate", "PR_NUMBER/GH_REPO/GH_TOKEN wiring changed")
ok(ae.get("NEEDS") == "${{ toJSON(needs) }}" and ae.get("EVENT_NAME") == "${{ github.event_name }}",
   "should_passNeedsAndEvent_when_ciGate", "NEEDS/EVENT_NAME wiring changed")
ok(agg["run"].startswith('set -euo pipefail\nscript=trusted/scripts/ci/gate-aggregate.sh\nif [[ -f "$script" ]]; then\n  bash "$script"\n  exit 0\nfi\n'),
   "should_runTrustedAggregatorFirst_when_ciGate", "trusted gate-aggregate.sh call changed")
ok(jobs["ci-gate"].get("permissions", {}).get("pull-requests") == "read", "should_readPrForDraftRecheck_when_ciGate", "pull-requests: read missing")

# ---- TC-10 / L4 (Q1): nightly dispatches main with reason=nightly, --rerun, fire-and-forget + reporter ----
ntext, ndoc, _ = W["nightly.yml"]
ok("gh workflow run pr-validate.yml --ref main -f reason=nightly" in ntext, "should_dispatchMainNightly_when_nightly", "dispatch line changed")
ok("gh run watch" not in ntext, "should_notIdleWatch_when_nightly", "gh run watch is back (L4)")
ok(all((j.get("timeout-minutes") or 999) <= 5 for j in ndoc["jobs"].values()), "should_finishFast_when_nightlyDispatch", "nightly dispatch timeout > 5")
ok('if [[ "$RUN_REASON" == "nightly" ]]; then args+=(--rerun); fi' in "\n".join(s.get("run", "") for s in jobs["test"]["steps"]),
   "should_rerunEverything_when_nightly", "--rerun on nightly is gone")
nr = jobs.get("nightly-report") or {}
ok(nr.get("if") == "always() && github.event_name == 'workflow_dispatch' && inputs.reason == 'nightly'" and nr.get("needs") == ["ci-gate"],
   "should_reportRedNightly_when_dispatchedNightly", "nightly-report wiring changed")
ok("nightly-red" in "\n".join(s.get("run", "") for s in nr.get("steps", [])) and "exit 1" in "\n".join(s.get("run", "") for s in nr.get("steps", [])),
   "should_openIssueAndFail_when_nightlyRed", "nightly-report must open the nightly-red issue and fail")

# ---- L7: gitleaks without push; weekly + dispatch + PR kept ----
_, _, gon = W["gitleaks.yml"]
ok("push" not in gon and "pull_request" in gon and "schedule" in gon and "workflow_dispatch" in gon,
   "should_scanPrAndWeekly_when_gitleaks", "gitleaks triggers: %s" % sorted(gon))

# ---- TC-8 / L5 (Q6): migration self-tests + parity only when the gate file changed ----
mtext, mdoc, mon = W["migration-immutability.yml"]
mj = mdoc["jobs"]
ok({"pull_request", "push", "schedule", "workflow_dispatch"} <= set(mon) and mon["schedule"] == [{"cron": "30 6 * * 1"}],
   "should_runAllWeekly_when_migrationScheduled", "schedule/dispatch triggers missing")
COND = ("${{ !cancelled() && (needs.check-migration-immutability.outputs.gate_changed != 'false' || "
        "(github.event_name != 'pull_request' && github.event_name != 'push')) }}")
for j in ["self-test-immutability-gate", "self-test-version-monotonicity-gate", "self-test-base-revision-resolution", "verify-gate-mirror-parity"]:
    ok(mj[j].get("needs") == "check-migration-immutability" and mj[j].get("if") == COND,
       "should_gateOnChange_when_%s" % j, "needs/if changed: %r / %r" % (mj[j].get("needs"), mj[j].get("if")))
for j in ["check-migration-immutability", "check-migration-version-monotonicity"]:
    ok("if" not in mj[j] and "needs" not in mj[j], "should_alwaysRunContentCheck_when_%s" % j, "content checks must always run")
ok(mj["check-migration-immutability"].get("outputs", {}).get("gate_changed") == "${{ steps.gate-changed.outputs.gate_changed }}",
   "should_exportGateChanged_when_checkJob", "gate_changed output missing")
steps = mj["check-migration-immutability"]["steps"]
idx = next(i for i, s in enumerate(steps) if s.get("id") == "gate-changed")
ok(idx == 1, "should_detectBeforeGateSteps_when_checkJob", "detection must be the first step after checkout")
det = steps[idx]["run"]
ok("gate_file=.github/workflows/migration-immutability.yml\n" in det and det.count("grep -qFx \"$gate_file\"") == 2 and "changed=true\n" in det,
   "should_matchExactGateFile_when_detecting", "gate_changed must be the exact file, default true")
print("workflow-meta-test (python): %d passed, %d failed" % (oks, len(fails)))
sys.exit(1 if fails else 0)
PY

# ---- TC-8 behaviour: run the gate_changed detection step against a fixture repo ----
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
DETECT_OUT="$work/detect.sh" ROOT="$ROOT" python3 -I - <<'PY' >/dev/null
import os, yaml
d = yaml.safe_load(open(os.path.join(os.environ["ROOT"], ".github/workflows/migration-immutability.yml"), encoding="utf-8"))
step = next(s for s in d["jobs"]["check-migration-immutability"]["steps"] if s.get("id") == "gate-changed")
open(os.environ["DETECT_OUT"], "w").write(step["run"])
PY
git init -q "$work/r"; cd "$work/r"
git config user.email t@example.invalid; git config user.name t; git config commit.gpgsign false
mkdir -p .github/workflows src; echo a >src/a; echo w >.github/workflows/migration-immutability.yml; git add -A; git commit -qm c0
c0=$(git rev-parse HEAD)
echo b >src/a; git add -A; git commit -qm app; app=$(git rev-parse HEAD)
echo w2 >.github/workflows/migration-immutability.yml; git add -A; git commit -qm gate; gate=$(git rev-parse HEAD)
echo x >.github/workflows/migration-immutability.yml.bak; git add -A; git commit -qm lookalike; look=$(git rev-parse HEAD)
git checkout -q -b pr "$c0"; echo c >src/a; git add -A; git commit -qm pr-app; git checkout -q --detach "$c0"
git merge -q --no-ff -m merge pr; prmerge=$(git rev-parse HEAD)
zero=0000000000000000000000000000000000000000
detect() { # <want> <name> <checkout> KEY=VALUE...
  local want=$1 name=$2 at=$3; shift 3
  git checkout -q --detach "$at"
  : >"$work/out"
  env -i PATH="$PATH" HOME="$HOME" GITHUB_OUTPUT="$work/out" PR_BASE_SHA= PUSH_BEFORE_SHA= "$@" bash -e "$work/detect.sh" >/dev/null 2>&1 || { echo "FAIL $name: step errored"; failures=$((failures + 1)); return; }
  if grep -qx "gate_changed=$want" "$work/out"; then echo "ok   $name"; else echo "FAIL $name: want $want, got $(cat "$work/out")"; failures=$((failures + 1)); fi
}
detect false should_skipSelfTests_when_pushTouchesOnlyApp "$app" EVENT_NAME=push PUSH_BEFORE_SHA="$c0"
detect true should_runSelfTests_when_pushTouchesGateFile "$gate" EVENT_NAME=push PUSH_BEFORE_SHA="$app"
detect false should_matchExactPath_when_lookalikeFileChanged "$look" EVENT_NAME=push PUSH_BEFORE_SHA="$gate"
detect true should_runSelfTests_when_beforeZero "$app" EVENT_NAME=push PUSH_BEFORE_SHA="$zero"
detect true should_runSelfTests_when_beforeUnreachable "$app" EVENT_NAME=push PUSH_BEFORE_SHA=2222222222222222222222222222222222222222
detect true should_runSelfTests_when_beforeEmpty "$app" EVENT_NAME=push
detect false should_skipSelfTests_when_prTouchesOnlyApp "$prmerge" EVENT_NAME=pull_request PR_BASE_SHA="$c0"
detect true should_runSelfTests_when_prBaseUnreachable "$prmerge" EVENT_NAME=pull_request PR_BASE_SHA=2222222222222222222222222222222222222222
detect true should_runSelfTests_when_prBaseEmpty "$prmerge" EVENT_NAME=pull_request
detect true should_runSelfTests_when_prDiffAgainstEventBaseTouchesGate "$prmerge" EVENT_NAME=pull_request PR_BASE_SHA="$look"
detect true should_runSelfTests_when_schedule "$app" EVENT_NAME=schedule
detect true should_runSelfTests_when_dispatch "$app" EVENT_NAME=workflow_dispatch

echo "workflow-meta-test: $failures failed group(s)"
[[ "$failures" -eq 0 ]]
