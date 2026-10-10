#!/usr/bin/env bash
# Fail-loud check (phase 358): every FQN in the selection list must have produced a JUnit XML
# report with tests > 0. Gradle's failOnNoMatchingTests only fires when the WHOLE filter matches
# nothing, never per pattern, so one stale or misspelled class would otherwise vanish silently.
#
#   verify-selected-ran.sh [selected-tests.txt] [results-dir]
# A @Nested-only class reports under TEST-<FQN>$<Inner>.xml; those count toward the outer FQN.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LIST="${1:-$ROOT/build/ci/selected-tests.txt}"
RESULTS="${2:-$ROOT/build/test-results/test}"

[[ -f "$LIST" ]] || { echo "verify-selected-ran: list not found: $LIST" >&2; exit 2; }

missing=0 checked=0
while IFS= read -r fqn || [[ -n "$fqn" ]]; do
  [[ -z "$fqn" ]] && continue
  checked=$((checked + 1))
  ran=0
  for report in "$RESULTS/TEST-$fqn.xml" "$RESULTS/TEST-$fqn"\$*.xml; do
    [[ -f "$report" ]] || continue
    suite=$(grep -o -m1 '<testsuite [^>]*' "$report" || true)
    tests=$(sed -n 's/.* tests="\([0-9]*\)".*/\1/p' <<<"$suite")
    skipped=$(sed -n 's/.* skipped="\([0-9]*\)".*/\1/p' <<<"$suite")
    [[ "${tests:-0}" -gt "${skipped:-0}" ]] && ran=1
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
