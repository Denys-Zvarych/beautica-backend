#!/usr/bin/env bash
# Fail-loud check (phase 358): every FQN in the selection list must have produced a JUnit XML
# report with tests > 0. Gradle's failOnNoMatchingTests only fires when the WHOLE filter matches
# nothing, never per pattern, so one stale or misspelled class would otherwise vanish silently.
#
#   verify-selected-ran.sh [selected-tests.txt] [results-dir] [report-aliases.txt]
# A class's own @Nested inner classes report under TEST-<FQN>$<Inner>.xml and count toward it.
# @Nested classes declared in an ABSTRACT BASE report under the DECLARING class
# (TEST-<Base>$<Inner>.xml); the concrete subclass gets only a 0-test stub. The selector therefore
# emits report-aliases.txt (`<selectedFqn><TAB><ancestorFqn>`) next to selected-tests.txt, and for an
# aliased FQN a TEST-<ancestorFqn>$*.xml with tests > skipped is accepted as evidence too. The alias
# file defaults to the one beside the list (the trusted selection artifact), never the PR tree.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LIST="${1:-$ROOT/build/ci/selected-tests.txt}"
RESULTS="${2:-$ROOT/build/test-results/test}"
ALIASES="${3:-$(dirname "$LIST")/report-aliases.txt}"

[[ -f "$LIST" ]] || { echo "verify-selected-ran: list not found: $LIST" >&2; exit 2; }

# executed <report.xml>: true when the suite ran at least one non-skipped test.
executed() {
  local suite tests skipped
  suite=$(grep -o -m1 '<testsuite [^>]*' "$1" || true)
  tests=$(sed -n 's/.* tests="\([0-9]*\)".*/\1/p' <<<"$suite")
  skipped=$(sed -n 's/.* skipped="\([0-9]*\)".*/\1/p' <<<"$suite")
  [[ "${tests:-0}" -gt "${skipped:-0}" ]]
}

missing=0 checked=0
while IFS= read -r fqn || [[ -n "$fqn" ]]; do
  [[ -z "$fqn" ]] && continue
  checked=$((checked + 1))
  ran=0
  candidates=("$RESULTS/TEST-$fqn.xml" "$RESULTS/TEST-$fqn"\$*.xml)
  if [[ -f "$ALIASES" ]]; then
    while IFS=$'\t' read -r aliased ancestor || [[ -n "$aliased" ]]; do
      [[ "$aliased" == "$fqn" && -n "$ancestor" ]] && candidates+=("$RESULTS/TEST-$ancestor"\$*.xml)
    done <"$ALIASES"
  fi
  for report in "${candidates[@]}"; do
    [[ -f "$report" ]] || continue
    executed "$report" && ran=1
  done
  if [[ $ran -eq 0 ]]; then
    echo "NOT EXECUTED: $fqn" >&2
    missing=$((missing + 1))
  fi
done <"$LIST"

# An empty selection can never be "all executed": mode full|selective always yields >= 1 class.
if [[ $checked -eq 0 ]]; then
  echo "verify-selected-ran: selection list is empty: $LIST" >&2
  exit 1
fi
if [[ $missing -gt 0 ]]; then
  echo "verify-selected-ran: $missing of $checked selected test class(es) did not execute" >&2
  exit 1
fi
echo "verify-selected-ran: all $checked selected test class(es) executed"
