#!/usr/bin/env bash
# Affected-test selection wrapper (phase 358, shards from phase 360).
#
#   BASE_SHA=<sha> HEAD_SHA=<sha> scripts/ci/select-tests.sh   # CI pull_request: diff BASE_SHA..HEAD_SHA
#   scripts/ci/select-tests.sh --force-full "<reason>" [ci_changed]   # push / dispatch / full-ci label / CI infra changed
#   scripts/ci/select-tests.sh --local                          # origin/dev..HEAD + uncommitted + untracked
#   scripts/ci/select-tests.sh --replay N                       # replay the last N first-parent commits of dev
#                                                               #   (REPLAY_COMMITS="sha sha" replays exactly those)
#
# Writes (OUT_DIR; CI_OUT_DIR in CI, which MUST be outside the checked-out PR tree; local default build/ci):
#   selection.env       MODE / REASON / COUNT / TOTAL / SHARDS / SHARD_MATRIX (non-empty shards only) / CI_CHANGED / SHARD_WEIGHTS
#   selected-tests.txt  FQNs to run (every test class when MODE=full)
#   shard-<i>.txt       the same list split into SHARDS weight-balanced shards (TestSharder)
# MODE=none writes empty lists. Any failure to decide falls back to MODE=full.
#
# TRUST SPLIT (cycle-1 security audit): ROOT is the tree THIS SCRIPT lives in; the selector is
# compiled from ROOT's src/test/java/com/beautica/ci with plain javac (JDK-only classes, no Gradle,
# no project classpath). PROJECT_ROOT is the tree being analysed and defaults to ROOT. In CI a pull
# request is analysed (PROJECT_ROOT) by a script + selector extracted from the merge commit's BASE
# parent (ROOT), so a PR cannot edit the rules that decide what runs against it.
#
# OUTPUT TRUST (cycle-5 security audit): every write lands in OUT_DIR, which in CI is CI_OUT_DIR
# ($RUNNER_TEMP/ci-out), never inside the PR tree: a PR-committed build/ci.changes.txt symlink could
# otherwise redirect `git diff >file` onto a trusted script. The out dir is created fresh (never
# reused) and a symlinked out dir is refused; in CI a PR tree holding `build` as a symlink or any
# committed path under build/ fails closed.
#
# Env: PROJECT_ROOT, CI_OUT_DIR, FULL_SHARDS, SELECTIVE_SHARD_THRESHOLD, SELECTOR_CLASSES,
#      EXCLUDE_PREFIX (FQN prefix dropped from every list, e.g. com.beautica.ci. when the self-test job runs it).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PROJECT_ROOT="$(cd "${PROJECT_ROOT:-$ROOT}" && pwd)"
IN_CI="${GITHUB_ACTIONS:+true}"
if [[ -n "$IN_CI" ]]; then
  OUT_DIR="${CI_OUT_DIR:-${RUNNER_TEMP:+$RUNNER_TEMP/ci-out}}"
  [[ -n "$OUT_DIR" ]] || { echo "select-tests: CI_OUT_DIR (or RUNNER_TEMP) is required in CI" >&2; exit 1; }
else
  OUT_DIR="${CI_OUT_DIR:-$PROJECT_ROOT/build/ci}"
fi
FULL_SHARDS="${FULL_SHARDS:-3}"
SELECTIVE_SHARD_THRESHOLD="${SELECTIVE_SHARD_THRESHOLD:-120}"
# CI compiles the selector under the runner temp dir so nothing lands in a repo tree; local is unchanged.
if [[ -n "$IN_CI" ]]; then
  SELECTOR_CLASSES="${SELECTOR_CLASSES:-${RUNNER_TEMP:-$ROOT/build}/ci-selector}"
else
  SELECTOR_CLASSES="${SELECTOR_CLASSES:-$ROOT/build/ci-selector}"
fi
SELECTOR_SRC="$ROOT/src/test/java/com/beautica/ci"
SELECTOR_MAIN="com.beautica.ci.AffectedTestSelector"

# Always rebuilt (about a second): a stale class dir must never decide what runs. The unit tests and
# SourceTreeFixture need JUnit/AssertJ and are not part of the runtime selector.
# Fail closed (return 1) on anything that could redirect a selector write.
check_out_dir() {
  if [[ -L "$OUT_DIR" ]]; then echo "select-tests: refusing symlinked out dir $OUT_DIR" >&2; return 1; fi
  if [[ -n "$IN_CI" ]]; then
    case "$(realpath -m "$OUT_DIR")/" in
      "$(realpath -m "$PROJECT_ROOT")"/*) echo "select-tests: CI out dir must be outside the PR tree ($OUT_DIR)" >&2; return 1 ;;
    esac
  fi
}

# CI only: the PR tree must not carry anything under build/ (nor build itself as a symlink); the
# build dir is produced by the runner, never committed.
guard_workspace() {
  [[ -n "$IN_CI" ]] || return 0
  if [[ -L "$PROJECT_ROOT/build" || -L "$ROOT/build" ]]; then echo "select-tests: 'build' is a symlink" >&2; return 1; fi
  local listed tracked
  # Fail closed: a git error must not read as "nothing tracked under build/".
  listed=$(git -C "$PROJECT_ROOT" ls-files -- build 2>/dev/null) || { echo "select-tests: git ls-files failed" >&2; return 1; }
  tracked=${listed%%$'\n'*}
  if [[ -n "$tracked" ]]; then echo "select-tests: committed path under build/ is forbidden ($tracked)" >&2; return 1; fi
}

# fresh_out: (re)create an empty out dir; never writes through a symlink.
fresh_out() {
  check_out_dir || return 1
  rm -rf "$OUT_DIR"
  mkdir -p "$OUT_DIR"
  [[ -d "$OUT_DIR" && ! -L "$OUT_DIR" ]] || { echo "select-tests: out dir not usable: $OUT_DIR" >&2; return 1; }
}

ensure_selector() {
  local srcs=() f
  for f in "$SELECTOR_SRC"/*.java; do
    case "$f" in *Test.java | */SourceTreeFixture.java) continue ;; esac
    srcs+=("$f")
  done
  rm -rf "$SELECTOR_CLASSES"
  mkdir -p "$SELECTOR_CLASSES"
  javac -proc:none -d "$SELECTOR_CLASSES" "${srcs[@]}"
}

selector() {
  local extra=()
  [[ -z "${EXCLUDE_PREFIX:-}" ]] || extra=(--exclude "$EXCLUDE_PREFIX")
  java -cp "$SELECTOR_CLASSES" "$SELECTOR_MAIN" "${extra[@]}" "$@"
}

# value_of KEY < selector-stdout
value_of() { sed -n "s/^$1=//p" | head -1; }

# n -> JSON array of the shard indices whose shard-<i>.txt is non-empty, so an empty shard never
# pays for a runner (checkout, JDK, Gradle). MODE=none yields [] (the test job is skipped then).
shard_matrix() {
  local n=$1 out="" i
  for ((i = 0; i < n; i++)); do
    if [[ -s "$OUT_DIR/shard-$i.txt" ]]; then out+="${out:+,}$i"; fi
  done
  printf '[%s]' "$out"
}

write_env() { # mode reason count total shards ci_changed shard_weights
  mkdir -p "$OUT_DIR"
  {
    printf 'MODE=%s\n' "$1"
    printf 'REASON=%q\n' "${2//$'\n'/ }"
    printf 'COUNT=%s\nTOTAL=%s\nSHARDS=%s\n' "$3" "$4" "$5"
    printf 'SHARD_MATRIX=%s\n' "$(shard_matrix "$5")"
    printf 'CI_CHANGED=%s\nSHARD_WEIGHTS=%s\n' "$6" "${7:-}"
  } >"$OUT_DIR/selection.env"
}

# decide <project-root> <changes-file|""> <deleted-dir> <force-full-reason|""> <ci_changed>
decide() {
  local proot=$1 changes=$2 deleted=$3 forced=$4 ci_changed=$5 tmp mode reason count total shards weights
  mkdir -p "$OUT_DIR"
  rm -f "$OUT_DIR"/shard-*.txt "$OUT_DIR/selected-tests.txt"
  tmp="$OUT_DIR/.first"
  if [[ -n "$forced" ]]; then
    out=$(selector --root "$proot" --all --out "$tmp") || return 1
    mode=full; reason=$forced
  else
    out=$(selector --root "$proot" --changes "$changes" --deleted-content "$deleted" --out "$tmp") || return 1
    mode=$(value_of MODE <<<"$out"); reason=$(value_of REASON <<<"$out")
  fi
  total=$(value_of TOTAL <<<"$out")
  case "$mode" in
    full)
      selector --root "$proot" --all --shards "$FULL_SHARDS" --out-dir "$OUT_DIR" >"$OUT_DIR/.shardout" || return 1
      count=$(wc -l <"$OUT_DIR/selected-tests.txt") || return 1; shards=$FULL_SHARDS ;;
    selective)
      count=$(value_of COUNT <<<"$out")
      if [[ "$count" -eq 0 ]]; then
        mode=none; reason="no test class reaches the change ($reason)"; shards=1
        selector --root "$proot" --list "$tmp" --shards 1 --out-dir "$OUT_DIR" >"$OUT_DIR/.shardout" || return 1
      else
        shards=1; [[ "$count" -gt "$SELECTIVE_SHARD_THRESHOLD" ]] && shards=$FULL_SHARDS
        selector --root "$proot" --list "$tmp" --shards "$shards" --out-dir "$OUT_DIR" >"$OUT_DIR/.shardout" || return 1
      fi ;;
    none)
      count=0; shards=1
      selector --root "$proot" --list "$tmp" --shards 1 --out-dir "$OUT_DIR" >"$OUT_DIR/.shardout" || return 1 ;;
    *) echo "select-tests: selector gave no MODE (got '$mode')" >&2; return 1 ;;
  esac
  weights=$(value_of SHARD_WEIGHTS <"$OUT_DIR/.shardout")
  rm -f "$tmp" "$OUT_DIR/.shardout"
  write_env "$mode" "$reason" "$count" "$total" "$shards" "$ci_changed" "$weights"
}

# changes_for <git-dir> <base> <head> <outfile>: name-status diff + base copies of deleted main files
changes_for() {
  local gdir=$1 base=$2 head=$3 out=$4 status path
  git -C "$gdir" diff --name-status -M "$base" "$head" >"$out" || return 1
  mkdir -p "$OUT_DIR/deleted" || return 1
  while IFS=$'\t' read -r status path _rest; do
    [[ "$status" == D || "$status" == R* ]] || continue
    [[ "$path" == src/main/java/*.java ]] || continue
    mkdir -p "$OUT_DIR/deleted/$(dirname "$path")"
    git -C "$gdir" show "$base:$path" >"$OUT_DIR/deleted/$path" 2>/dev/null || true
  done <"$out"
}

ci_changed_in() { grep -qE $'\t(src/test/java/com/beautica/ci/|scripts/ci/)' "$1" && echo true || echo false; }

run_diff() { # <git-dir> <project-root> <base> <head>
  local changes ci
  fresh_out || return 1
  changes="$OUT_DIR/changes.txt"
  # Explicit `|| return 1`: callers use `if ! run_diff`, which disables `set -e` inside this function,
  # and a swallowed diff failure would read as "no changes" -> MODE=none.
  changes_for "$1" "$3" "$4" "$changes" || return 1
  ci=$(ci_changed_in "$changes")
  decide "$2" "$OUT_DIR/changes.txt" "$OUT_DIR/deleted" "" "$ci" || return 1
}

replay() {
  local n=$1 ref commit parent work saved_out
  ref=$(git -C "$PROJECT_ROOT" rev-parse --verify -q origin/dev || git -C "$PROJECT_ROOT" rev-parse --verify dev)
  work=$(mktemp -d); saved_out=$OUT_DIR
  printf '%-9s %-10s %7s %-16s %s\n' COMMIT MODE SEL/TOT SHARD-WEIGHTS 'SUBJECT | REASON'
  while read -r commit; do
    parent=$(git -C "$PROJECT_ROOT" rev-parse "$commit^" 2>/dev/null) || continue
    rm -rf "$work/tree"; mkdir -p "$work/tree"
    git -C "$PROJECT_ROOT" archive "$commit" src | tar -x -C "$work/tree"
    OUT_DIR="$work/out"
    run_diff "$PROJECT_ROOT" "$work/tree" "$parent" "$commit" || { echo "$commit REPLAY-FAILED"; continue; }
    # shellcheck disable=SC1091
    source "$OUT_DIR/selection.env" # sets MODE REASON COUNT TOTAL SHARD_WEIGHTS
    # shellcheck disable=SC2153
    printf '%-9s %-10s %3s/%-3s %-16s %s | %s\n' "${commit:0:8}" "$MODE" "$COUNT" "$TOTAL" "$SHARD_WEIGHTS" "$(git -C "$PROJECT_ROOT" log -1 --format=%s "$commit" | cut -c1-48)" "$(printf '%s' "$REASON" | cut -c1-95)"
  done < <(if [[ -n "${REPLAY_COMMITS:-}" ]]; then tr ' ' '\n' <<<"$REPLAY_COMMITS"; else git -C "$PROJECT_ROOT" rev-list --first-parent -n "$n" "$ref"; fi)
  OUT_DIR=$saved_out
  rm -rf "$work"
}

main() {
  guard_workspace || exit 1
  check_out_dir || exit 1
  ensure_selector
  case "${1:-}" in
    --force-full)
      fresh_out || exit 1
      decide "$PROJECT_ROOT" "" "" "${2:-forced full run}" "${3:-false}" ;;
    --local)
      local base
      base=$(git -C "$PROJECT_ROOT" rev-parse --verify -q origin/dev || git -C "$PROJECT_ROOT" rev-parse --verify dev)
      base=$(git -C "$PROJECT_ROOT" merge-base "$base" HEAD)
      fresh_out || exit 1
      # base..worktree: committed + staged + unstaged changes in one diff, plus untracked files as additions
      git -C "$PROJECT_ROOT" diff --name-status -M "$base" >"$OUT_DIR/changes.txt"
      git -C "$PROJECT_ROOT" ls-files --others --exclude-standard | sed 's/^/A\t/' >>"$OUT_DIR/changes.txt"
      mkdir -p "$OUT_DIR/deleted"
      while IFS=$'\t' read -r status path _; do
        [[ "$status" == D || "$status" == R* ]] && [[ "$path" == src/main/java/*.java ]] || continue
        mkdir -p "$OUT_DIR/deleted/$(dirname "$path")"
        git -C "$PROJECT_ROOT" show "$base:$path" >"$OUT_DIR/deleted/$path" 2>/dev/null || true
      done <"$OUT_DIR/changes.txt"
      decide "$PROJECT_ROOT" "$OUT_DIR/changes.txt" "$OUT_DIR/deleted" "" "$(ci_changed_in "$OUT_DIR/changes.txt")" ;;
    --replay)
      replay "${2:-30}"; return ;;
    "")
      : "${BASE_SHA:?BASE_SHA is required}" "${HEAD_SHA:?HEAD_SHA is required}"
      # Diff exactly what the test jobs run: CI passes the merge commit's first parent (the base tip
      # the merge was made against) and the merge commit itself, which is what actions/checkout
      # builds. No merge-base: it would diff head against an older base than the one under test.
      if ! run_diff "$PROJECT_ROOT" "$PROJECT_ROOT" "$BASE_SHA" "$HEAD_SHA"; then
        check_out_dir || exit 1
        decide "$PROJECT_ROOT" "" "" "diff $BASE_SHA..$HEAD_SHA failed (missing history?)" false
      fi ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
  cat "$OUT_DIR/selection.env"
}

main "$@"
