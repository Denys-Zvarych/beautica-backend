#!/bin/bash
# Nightly database backup (phase 366): pg_dump -> verify -> age (public recipient) -> R2.
# Runs to completion and exits (Railway cron). Plaintext never leaves the container: the dump lives
# in a umask-077 temp dir that the EXIT trap removes.
#
# Required env : DATABASE_URL BACKUP_R2_ENDPOINT BACKUP_R2_ACCESS_KEY_ID BACKUP_R2_SECRET_ACCESS_KEY
#                BACKUP_R2_BUCKET (beautica-db-backups-prod; ...-staging is the non-prod/IT bucket)
#                BACKUP_AGE_RECIPIENT (age1... public key)
# Optional env : BACKUP_DB_SIZE_WARN_BYTES (default 4 GiB)  BACKUP_FORCE_WEEKLY=1 (also write weekly/)
#                BACKUP_SENTRY_CRON_URL (dead-man's-switch check-in URL; a secret)
#                RAILWAY_ENVIRONMENT_NAME (set by Railway; cross-checked against the bucket)
#
# Prod guards (bucket beautica-db-backups-prod): https EU R2 endpoint only, sslmode require|verify-ca|verify-full,
# https check-in URL, recipient equal to the one committed in prod-recipient.txt (no committed pin = refused).
# The staging bucket is refused when RAILWAY_ENVIRONMENT_NAME=production; it is the only way the IT can use http.
# The job never deletes or overwrites: keys are unique per run (daily/ is minute-stamped, weekly/ copies it),
# so R2 Bucket Lock retention on daily/ and weekly/ never blocks it.
#
# Exit: 0 ok | 1 error | 2 size_warn (backup uploaded, database is above the size threshold).
# Output: progress lines, then EXACTLY ONE final `db_backup_result ...` line. No URL, host, key id or
# secret value is ever printed (missing variables are named, never their values), and no tool's
# stderr is forwarded (pg_dump/curl error text can carry the host).
set -euo pipefail
# Fixed PATH, no startup-file injection. /usr/local/sbin stays first: the IT curl shim lives there.
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
unset BASH_ENV ENV
umask 077

STATUS=error
ENV_NAME=unknown
DUMP_BYTES=0
DB_BYTES=0
KEY=""
WEEKLY=0
TMP=""
CHECKIN=0
START=$SECONDS
DEADLINE_S=780   # 13 min overall; the Sentry monitor's max runtime is 15 min
# Test-only override (DbBackupRoundTripIT): it can only LOWER the deadline, never raise it, so a mis-set variable in
# Railway cannot push a run past the Sentry monitor's 15 minute window.
if [[ "${BACKUP_DEADLINE_S:-}" =~ ^[0-9]{1,3}$ ]] && [ "$((10#$BACKUP_DEADLINE_S))" -ge 1 ] && [ "$((10#$BACKUP_DEADLINE_S))" -lt "$DEADLINE_S" ]; then
    DEADLINE_S="$((10#$BACKUP_DEADLINE_S))"
fi
PIN_FILE=/etc/db-backup/prod-recipient.txt
AGE_RE='^age1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]{58}$'
EMPTY_SHA=e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
EXPECT_SIZE=""   # size + MD5 of the encrypted object; used to confirm a PUT whose reply was lost (see r2_retry)
EXPECT_MD5=""
HEAD_CONFIRMED=0
CURL_PROTO='=https'
SENTRY_URL=""
R2_KEY_ID=""
R2_SECRET=""
LAST_CODE=000

log() { printf 'db_backup: %s\n' "$*"; }
die() { log "$*"; exit 1; }
time_left() { printf '%s' "$((DEADLINE_S - (SECONDS - START)))"; }
check_deadline() { [ "$(time_left)" -gt 0 ] || die "overall ${DEADLINE_S}s deadline exceeded"; }
# capped N: N seconds, but never more than what is left of the overall deadline (minimum 1), so that no single
# step can carry a failing run past the deadline.
capped() {
    local n="$1" left
    left="$(time_left)"
    [ "$left" -ge 1 ] || left=1
    [ "$n" -le "$left" ] || n="$left"
    printf '%s' "$n"
}

# Escape a value for a double-quoted curl-config string; refuse control characters.
cfg_escape() {
    local s="${1-}"
    [[ "$s" =~ [[:cntrl:]] ]] && return 1
    s="${s//\\/\\\\}"
    printf '%s' "${s//\"/\\\"}"
}

# Sentry Cron check-in. Best effort: 5 s timeout, never fails the backup. The URL is a secret, so it
# rides a curl config on stdin (never argv) and curl's stderr is discarded. https only in prod.
checkin() {
    [ -n "$SENTRY_URL" ] || return 0
    local sep='?' esc
    [[ "$SENTRY_URL" == *\?* ]] && sep='&'
    esc="$(cfg_escape "${SENTRY_URL}${sep}status=$1")" || return 0
    printf 'silent\nproto = "%s"\nconnect-timeout = 5\nmax-time = 5\noutput = "/dev/null"\nurl = "%s"\n' "$CURL_PROTO" "$esc" \
        | curl -K - >/dev/null 2>&1 || true
}

on_exit() {
    local rc=$?
    trap - EXIT
    [ -z "$TMP" ] || rm -rf -- "$TMP"
    # STATUS only ever becomes ok/size_warn on the last lines, so any earlier failure is an error.
    if [ "$rc" -ne 0 ] && [ "$STATUS" = ok ]; then STATUS=error; fi
    if [ "$CHECKIN" = 1 ]; then
        if [ "$STATUS" = ok ]; then checkin ok; else checkin error; fi
    fi
    printf 'db_backup_result status=%s env=%s dump_bytes=%s db_bytes=%s key=%s weekly=%s duration_s=%s\n' \
        "$STATUS" "$ENV_NAME" "$DUMP_BYTES" "$DB_BYTES" "${KEY:--}" "$WEEKLY" "$((SECONDS - START))"
    exit "$rc"
}
trap on_exit EXIT
# SIGTERM (Railway stop) / SIGINT: leave through the EXIT trap so the plaintext dir is removed.
trap 'exit 143' TERM INT

# Reference pattern: scripts/_r2_wipe.sh (curl --aws-sigv4 "aws:amz:auto:s3", credentials on a stdin
# curl config). The backend repo cannot source root scripts/, so this is a self-contained copy.
# r2_call METHOD KEY PAYLOAD_SHA [UPLOAD_FILE [COPY_SOURCE]] -> LAST_CODE (HTTP status, 000 = transport
# failure); the response body lands in $TMP/resp. The payload sha256 is sent explicitly so R2 verifies the body.
# Per request: 120 s (+1 s per 512 KiB of object) capped by the remaining deadline. A body upload also has a
# 256 KiB/s floor over 30 s (half the rate the max-time formula assumes, ~2 Mbit/s, far below Railway egress): at the
# old 1 KiB/s a trickling connection could hold one attempt for its whole max-time, and three attempts for the whole
# deadline. Body-less requests (server-side copy, HEAD) move no bytes, so they have NO speed floor: max-time only.
# Method HEAD sends a real HEAD (curl --head) with a fixed 30 s max-time; the response headers land in $TMP/resp.
r2_call() {
    local method="$1" key="$2" sha="$3" file="${4-}" copy_src="${5-}" maxt
    maxt=$((120 + DUMP_BYTES / 524288))
    [ "$method" != HEAD ] || maxt=30
    [ "$maxt" -le "$(time_left)" ] || maxt="$(time_left)"
    [ "$maxt" -ge 1 ] || maxt=1
    LAST_CODE="$({
        printf 'silent\n'
        printf 'proto = "%s"\n' "$CURL_PROTO"
        printf 'aws-sigv4 = "aws:amz:auto:s3"\n'
        printf 'user = "%s:%s"\n' "$(cfg_escape "$R2_KEY_ID")" "$(cfg_escape "$R2_SECRET")"
        printf 'header = "x-amz-content-sha256: %s"\n' "$sha"
        if [ -n "$copy_src" ]; then
            printf 'header = "x-amz-copy-source: %s"\n' "$(cfg_escape "$copy_src")"
            printf 'header = "Content-Length: 0"\n'
        fi
        if [ "$method" = HEAD ]; then printf 'head\n'; else printf 'request = "%s"\n' "$method"; fi
        if [ -n "$file" ]; then printf 'upload-file = "%s"\n' "$(cfg_escape "$file")"; fi
        printf 'url = "%s/%s/%s"\n' "$(cfg_escape "$ENDPOINT")" "$BACKUP_R2_BUCKET" "$key"
        printf 'output = "%s"\n' "$TMP/resp"
        printf 'write-out = "%%{http_code}"\n'
        printf 'connect-timeout = 15\nmax-time = %s\n' "$maxt"
        if [ -n "$file" ]; then printf 'speed-limit = 262144\nspeed-time = 30\n'; fi
    } | curl -K - 2>/dev/null)" || LAST_CODE=000
}

# head_confirms KEY: HEAD the object and compare Content-Length and the ETag with the local size and MD5.
# A single-part PUT's ETag is the MD5 of the body on S3 and on R2. 0 = the object is there and is ours.
head_confirms() {
    local len etag
    [ -n "$EXPECT_MD5" ] || return 1
    check_deadline
    : >"$TMP/resp"
    r2_call HEAD "$1" "$EMPTY_SHA"
    [ "$LAST_CODE" = 200 ] || return 1
    len="$(tr -d '\r' <"$TMP/resp" | awk 'tolower($1) == "content-length:" { print $2; exit }')"
    etag="$(tr -d '\r' <"$TMP/resp" | awk 'tolower($1) == "etag:" { gsub(/"/, "", $2); print tolower($2); exit }')"
    [ "$len" = "$EXPECT_SIZE" ] && [ "$etag" = "$EXPECT_MD5" ]
}

# r2_retry LABEL <r2_call args...>: three attempts, retrying ONLY transport failures (000), 429 and 5xx.
# Any other status (403 bad credentials, 404 missing bucket, 400, ...) is final: retrying cannot fix it.
# After a 000 on a PUT the request may still have been applied server-side (reply lost). Retrying would then hit
# Bucket Lock (the key exists) and fail a backup that is safely stored, so the key is HEADed first: same size and MD5
# means success, no retry (HEAD_CONFIRMED=1: the response body of the PUT is gone).
r2_retry() {
    local label="$1" attempt
    shift
    HEAD_CONFIRMED=0
    for attempt in 1 2 3; do
        check_deadline
        : >"$TMP/resp"
        r2_call "$@"
        log "$label attempt $attempt http=$LAST_CODE"
        if [[ "$LAST_CODE" =~ ^2[0-9]{2}$ ]]; then return 0; fi
        if [ "$LAST_CODE" = 000 ] && [ "$1" = PUT ]; then
            if head_confirms "$2"; then
                log "$label confirmed by HEAD (size and MD5 match), not retrying"
                HEAD_CONFIRMED=1
                LAST_CODE=200
                return 0
            fi
            LAST_CODE=000   # the HEAD's status must not turn this retryable failure into a final one
        fi
        [[ "$LAST_CODE" =~ ^(000|429|5[0-9]{2})$ ]] || return 1
        [ "$attempt" = 3 ] || sleep 3
    done
    return 1
}

# Decode %XX escapes only. (printf %b on the whole string would also interpret backslashes in the password.)
urldecode() {
    local s="${1-}" out="" esc ch
    while [ -n "$s" ]; do
        if [[ "$s" =~ ^%([0-9A-Fa-f]{2}) ]]; then
            printf -v esc '\\x%s' "${BASH_REMATCH[1]}"
            printf -v ch '%b' "$esc"
            out+="$ch"
            s="${s:3}"
        else
            out+="${s:0:1}"
            s="${s:1}"
        fi
    done
    printf '%s' "$out"
}

# ── 1. validate everything BEFORE any network call ──────────────────────────────────────────────
missing=()
for v in DATABASE_URL BACKUP_R2_ENDPOINT BACKUP_R2_ACCESS_KEY_ID BACKUP_R2_SECRET_ACCESS_KEY \
         BACKUP_R2_BUCKET BACKUP_AGE_RECIPIENT; do
    [ -n "${!v:-}" ] || missing+=("$v")
done
if [ "${#missing[@]}" -gt 0 ]; then
    die "missing required variable(s): ${missing[*]}"
fi
[[ "$BACKUP_AGE_RECIPIENT" =~ $AGE_RE ]] || die "BACKUP_AGE_RECIPIENT must be an age public recipient (age1 + 58 bech32 characters)"

if [[ "$BACKUP_R2_BUCKET" =~ ^beautica-db-backups-(prod|staging)$ ]]; then
    ENV_NAME="${BASH_REMATCH[1]}"
else
    die "BACKUP_R2_BUCKET must be beautica-db-backups-prod (or beautica-db-backups-staging outside production)"
fi
# prod bucket <=> production environment (when Railway tells us which one this is).
railway_env="${RAILWAY_ENVIRONMENT_NAME:-}"
if [ "$ENV_NAME" = prod ]; then
    { [ -z "$railway_env" ] || [ "$railway_env" = production ]; } \
        || die "BACKUP_R2_BUCKET is the prod bucket but this is not the production environment"
else
    [ "$railway_env" != production ] || die "BACKUP_R2_BUCKET is the staging bucket, which is refused in the production environment"
fi

ENDPOINT="${BACKUP_R2_ENDPOINT%/}"
if [ "$ENV_NAME" = prod ]; then
    [[ "$ENDPOINT" =~ ^https://[0-9a-f]{32}\.eu\.r2\.cloudflarestorage\.com$ ]] \
        || die "BACKUP_R2_ENDPOINT must be an https EU R2 endpoint (<32-hex account id>.eu.r2.cloudflarestorage.com) for the prod bucket"
    CURL_PROTO='=https'
else
    [[ "$ENDPOINT" =~ ^https?://[A-Za-z0-9.-]+(:[0-9]{1,5})?$ ]] || die "BACKUP_R2_ENDPOINT must be http(s)://host[:port] with no path, query or userinfo"
    CURL_PROTO='=http,https'
fi

# Recipient pin: prod-recipient.txt is baked into the image (fixed path, no env override). Comment/blank lines
# are ignored. FAIL CLOSED: a prod run with no valid pin (empty, unreadable or malformed file) is refused, so a
# swapped BACKUP_AGE_RECIPIENT can never silently redirect the encryption. Runbook order: E3 commits the pin,
# E6 is the first prod run.
if [ "$ENV_NAME" = prod ]; then
    pin=""
    if [ -r "$PIN_FILE" ]; then
        pin="$(grep -vE '^[[:space:]]*(#|$)' "$PIN_FILE" | head -n 1 | tr -d '[:space:]' || true)"
    fi
    [ -n "$pin" ] || die "no prod recipient pinned: commit the age1 public key to ops/db-backup/prod-recipient.txt and redeploy (runbook E3)"
    [[ "$pin" =~ $AGE_RE ]] || die "prod-recipient.txt does not hold a valid age recipient"
    [ "$BACKUP_AGE_RECIPIENT" = "$pin" ] || die "BACKUP_AGE_RECIPIENT does not match the pinned prod recipient (prod-recipient.txt)"
    log "recipient pin: matched"
fi

WARN_BYTES="${BACKUP_DB_SIZE_WARN_BYTES:-4294967296}"
[[ "$WARN_BYTES" =~ ^[0-9]+$ ]] || die "BACKUP_DB_SIZE_WARN_BYTES must be a non-negative integer"

# D7: split DATABASE_URL into libpq environment variables so the password never reaches argv.
# sslmode=require is applied only when the URL carries no sslmode parameter.
db_re='^postgres(ql)?://([^:@/]+)(:([^@]*))?@([^:/?]+)(:([0-9]+))?/([^?]*)(\?(.*))?$'
[[ "$DATABASE_URL" =~ $db_re ]] || die "DATABASE_URL is not a postgres:// URL of the form user:password@host[:port]/database"
PGUSER="$(urldecode "${BASH_REMATCH[2]}")"
PGPASSWORD="$(urldecode "${BASH_REMATCH[4]}")"
PGHOST="${BASH_REMATCH[5]}"
PGPORT="${BASH_REMATCH[7]:-5432}"
PGDATABASE="$(urldecode "${BASH_REMATCH[8]}")"
db_query="${BASH_REMATCH[10]:-}"
PGSSLMODE=require
if [[ "&$db_query" =~ \&sslmode=([a-z-]+) ]]; then PGSSLMODE="${BASH_REMATCH[1]}"; fi
if [ "$ENV_NAME" = prod ]; then
    [[ "$PGSSLMODE" =~ ^(require|verify-ca|verify-full)$ ]] || die "DATABASE_URL sslmode must be require, verify-ca or verify-full for the prod bucket"
fi
PGCONNECT_TIMEOUT=10
export PGUSER PGPASSWORD PGHOST PGPORT PGDATABASE PGSSLMODE PGCONNECT_TIMEOUT
unset DATABASE_URL

# Check-in URL: https in prod (the URL is a secret and must not cross the network in clear text).
SENTRY_URL="${BACKUP_SENTRY_CRON_URL:-}"
if [ -n "$SENTRY_URL" ]; then
    if [ "$ENV_NAME" = prod ]; then sentry_re='^https://'; else sentry_re='^https?://'; fi
    if ! [[ "$SENTRY_URL" =~ $sentry_re ]]; then
        log "WARNING: BACKUP_SENTRY_CRON_URL is not an https URL, check-ins are disabled"
        SENTRY_URL=""
    fi
fi

# Keep the R2 secret and the check-in URL out of the environment that pg_dump, psql, age and sha256sum inherit.
R2_KEY_ID="$BACKUP_R2_ACCESS_KEY_ID"
R2_SECRET="$BACKUP_R2_SECRET_ACCESS_KEY"
unset BACKUP_R2_ACCESS_KEY_ID BACKUP_R2_SECRET_ACCESS_KEY BACKUP_SENTRY_CRON_URL

CHECKIN=1
checkin in_progress

TMP="$(mktemp -d)"
DUMP="$TMP/db.dump"
ENC="$TMP/db.dump.age"

# ── 2. database reachable (private networking can lag at container start) ───────────────────────
ready=0
for _ in $(seq 1 15); do
    check_deadline
    if pg_isready -q -t 2 >/dev/null 2>&1; then ready=1; break; fi
    sleep 2
done
[ "$ready" = 1 ] || die "database not reachable after 15 attempts"

# ── 3. size guard input ─────────────────────────────────────────────────────────────────────────
check_deadline
DB_BYTES="$(timeout -k 5 "$(capped 30)" psql -X -At -c 'SELECT pg_database_size(current_database())' 2>/dev/null)" || die "could not read the database size"
[[ "$DB_BYTES" =~ ^[0-9]+$ ]] || die "unexpected database size answer"

# ── 4. dump + integrity check, all before anything leaves the container ─────────────────────────
check_deadline
log "dumping"
dump_budget="$(capped 600)"
timeout -k 5 "$dump_budget" pg_dump --format=custom --no-owner --no-privileges --lock-wait-timeout=60s --file "$DUMP" >/dev/null 2>&1 || die "pg_dump failed"
check_deadline
timeout -k 5 "$(capped 120)" pg_restore --list "$DUMP" >"$TMP/toc" 2>/dev/null || die "dump failed the pg_restore --list check"
grep -Eq 'TABLE DATA public flyway_schema_history ' "$TMP/toc" || die "dump has no flyway_schema_history data (refusing to upload)"

# ── 5. encrypt (public recipient only) and upload ───────────────────────────────────────────────
check_deadline
timeout -k 5 "$(capped 300)" env -u PGPASSWORD age -r "$BACKUP_AGE_RECIPIENT" -o "$ENC" "$DUMP" >/dev/null 2>&1 || die "age encryption failed"
rm -f -- "$DUMP" "$TMP/toc"
DUMP_BYTES="$(stat -c %s -- "$ENC")"
ENC_SHA="$(timeout -k 5 "$(capped 120)" sha256sum -- "$ENC" | cut -d' ' -f1)" || die "could not hash the encrypted dump"
[[ "$ENC_SHA" =~ ^[0-9a-f]{64}$ ]] || die "unexpected sha256 answer"
check_deadline
EXPECT_MD5="$(timeout -k 5 "$(capped 120)" md5sum -- "$ENC" | cut -d' ' -f1)" || die "could not hash the encrypted dump"
[[ "$EXPECT_MD5" =~ ^[0-9a-f]{32}$ ]] || die "unexpected md5 answer"
EXPECT_SIZE="$DUMP_BYTES"

KEY="daily/beautica-$(date -u +%Y%m%dT%H%MZ).dump.age"
log "uploading"
r2_retry upload PUT "$KEY" "$ENC_SHA" "$ENC" || die "upload failed"
if [ "$(date -u +%u)" = 7 ] || [ "${BACKUP_FORCE_WEEKLY:-}" = 1 ]; then
    # Server-side copy of the object just written: no second upload of the body.
    r2_retry copy PUT "weekly/${KEY#daily/}" "$EMPTY_SHA" "" "$BACKUP_R2_BUCKET/$KEY" || die "weekly copy failed"
    [ "$HEAD_CONFIRMED" = 1 ] || grep -q '<CopyObjectResult' "$TMP/resp" || die "weekly copy was not confirmed by the storage service"
    WEEKLY=1
fi

# ── 6. size guard: the backup is safe in R2; the exit code tells the operator the database is large
if [ "$DB_BYTES" -gt "$WARN_BYTES" ]; then
    STATUS=size_warn
    exit 2
fi
STATUS=ok
