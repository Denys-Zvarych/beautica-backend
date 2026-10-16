#!/usr/bin/env bash
# Supply-chain pin gate for the backup image, run by CI (pr-validate.yml, db-backup shellcheck job).
# Usage: check-pins.sh <Dockerfile>
# Fails (exit 1) unless
#   * every FROM line is pinned by digest (image@sha256:<64 hex>), and
#   * every package after `apk add` carries an exact =version (flags such as --no-cache are skipped), and
#   * at least one `apk add` package was found (a gate that finds nothing is inert, so it fails too).
set -euo pipefail

file="${1:?usage: check-pins.sh <Dockerfile>}"
[ -r "$file" ] || { echo "check-pins: cannot read $file" >&2; exit 1; }
rc=0

grep -Eq '^FROM ' "$file" || { echo "check-pins: no FROM line in $file" >&2; exit 1; }
while IFS= read -r line; do
    [[ "$line" =~ ^FROM\ [^\ ]+@sha256:[0-9a-f]{64}(\ |$) ]] || { echo "check-pins: FROM must be pinned with @sha256:<digest>: $line" >&2; rc=1; }
done < <(grep -E '^FROM ' "$file")

# Join backslash-continued lines so `RUN apk add \` + package lines is one logical line.
joined="$(sed -e ':a' -e '/\\$/{N;s/\\\n/ /;ba' -e '}' "$file")"
found=0
while IFS= read -r line; do
    [[ "$line" == *"apk add"* ]] || continue
    rest="${line#*apk add}"
    rest="${rest%%&&*}"; rest="${rest%%;*}"; rest="${rest%%|*}"
    for pkg in $rest; do
        [[ "$pkg" == -* ]] && continue
        found=$((found + 1))
        [[ "$pkg" =~ ^[A-Za-z0-9._+-]+=[A-Za-z0-9._+~-]+$ ]] || { echo "check-pins: apk package '$pkg' has no exact =version pin" >&2; rc=1; }
    done
done <<<"$joined"
[ "$found" -gt 0 ] || { echo "check-pins: no 'apk add' package found in $file (gate would be inert)" >&2; rc=1; }
exit "$rc"
