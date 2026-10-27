#!/usr/bin/env bash
# Drift check for the backup image's apk pins against what Alpine serves TODAY (needs Docker + network).
# Usage: resolve-pins.sh <Dockerfile>
# Runs `apk update && apk policy <pkg...>` inside the Dockerfile's digest-pinned base image and compares the
# newest available revision of each pinned package with its =version pin. Exit 0 when every pin is still served,
# exit 1 (listing pin -> available) when Alpine has dropped a revision, exit 2 on usage/infra errors.
# Complements check-pins.sh (static: are there pins at all?) with the question that check cannot answer.
set -euo pipefail

file="${1:?usage: resolve-pins.sh <Dockerfile>}"
[ -r "$file" ] || { echo "resolve-pins: cannot read $file" >&2; exit 2; }

base="$(grep -Em1 '^FROM ' "$file" | awk '{print $2}')"
[[ "$base" == *@sha256:* ]] || { echo "resolve-pins: FROM is not digest-pinned: $base" >&2; exit 2; }

joined="$(sed -e ':a' -e '/\\$/{N;s/\\\n/ /;ba' -e '}' "$file")"
declare -A pin=()
while IFS= read -r line; do
    [[ "$line" == *"apk add"* ]] || continue
    rest="${line#*apk add}"
    rest="${rest%%&&*}"; rest="${rest%%;*}"; rest="${rest%%|*}"
    for pkg in $rest; do
        [[ "$pkg" == -* || "$pkg" != *=* ]] && continue
        pin["${pkg%%=*}"]="${pkg#*=}"
    done
done <<<"$joined"
[ "${#pin[@]}" -gt 0 ] || { echo "resolve-pins: no pinned apk package in $file" >&2; exit 2; }

# `apk policy` prints "<pkg> policy:" then indented "  <version>:" lines, newest last for the configured repos.
policy="$(docker run --rm --network bridge --entrypoint sh "$base" -c \
    "apk update >/dev/null && apk policy ${!pin[*]}")" || { echo "resolve-pins: apk policy failed in $base" >&2; exit 2; }

rc=0
for name in "${!pin[@]}"; do
    available="$(awk -v p="$name policy:" '$0==p{f=1;next} /policy:$/{f=0} f && /^  [^ ].*:$/{v=$1} END{sub(/:$/,"",v); print v}' <<<"$policy")"
    if [ -z "$available" ]; then
        echo "resolve-pins: $name=${pin[$name]} -> NOT IN REPO" >&2; rc=1
    elif [ "$available" != "${pin[$name]}" ]; then
        echo "resolve-pins: $name=${pin[$name]} -> available $name=$available" >&2; rc=1
    else
        echo "resolve-pins: $name=${pin[$name]} ok"
    fi
done
exit "$rc"
