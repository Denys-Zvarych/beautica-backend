#!/usr/bin/env bash
# Phase 25.6 — CI gate: fail the build if `th:utext` (Thymeleaf's UNESCAPED text directive)
# appears anywhere under the email templates directory.
#
# Why this exists: email templates are the ONE place in this codebase that renders
# user-supplied free text (booking notes — clientComment / providerComment /
# clientCancellationNote) into an HTML document. `th:text` HTML-escapes its value;
# `th:utext` does not. A future edit that swaps `th:text` for `th:utext` (e.g. to render a
# `<br>`-substituted version of a multi-line comment, instead of the correct
# `white-space: pre-line` CSS approach already used here) would reopen a stored-XSS hole in
# every recipient's mail client. There is no legitimate use of `th:utext` under this
# directory — every variable rendered here is either system-derived (dates, names resolved
# server-side) or explicitly user-supplied free text that MUST stay escaped.
#
# Usage: ./scripts/forbid_th_utext_in_email.sh   (run from beautica-backend/, or anywhere —
#   the template path below is resolved relative to this script's own location)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEMPLATES_DIR="${SCRIPT_DIR}/../src/main/resources/templates/email"

if [[ ! -d "${TEMPLATES_DIR}" ]]; then
  echo "forbid_th_utext_in_email: templates directory not found at ${TEMPLATES_DIR}" >&2
  exit 1
fi

# Matches the actual attribute usage (`th:utext="..."`), not a documentation comment merely
# mentioning the string (e.g. "No th:utext anywhere" in a template's variable-doc header).
MATCHES="$(grep -rEn 'th:utext[[:space:]]*=' "${TEMPLATES_DIR}" || true)"

if [[ -n "${MATCHES}" ]]; then
  echo "forbid_th_utext_in_email: th:utext is forbidden under templates/email/ — it renders" >&2
  echo "unescaped HTML and email is the one place in this app that renders free-text user" >&2
  echo "input. Use th:text (auto-escaped) + 'white-space: pre-line' CSS for multi-line notes" >&2
  echo "instead. Offending lines:" >&2
  echo "${MATCHES}" >&2
  exit 1
fi

echo "forbid_th_utext_in_email: OK — no th:utext under templates/email/"
