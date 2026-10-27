# ops/db-backup — nightly encrypted database backup (phase 366)

A Railway cron service built from this directory. Once a day it runs `pg_dump`, checks the dump, encrypts it with
`age` (public recipient only) and `PUT`s it to a dedicated R2 bucket. It runs to completion and exits.
Design and decisions: `docs/backend-phases/phase-366-db-backup-restore.md`.

```
pg_dump --format=custom  ->  pg_restore --list (must list flyway_schema_history data)  ->  age -r  ->  PUT daily/ (+ weekly/)
```

Plaintext never leaves the container (umask-077 temp dir, removed by an EXIT trap, which also runs on SIGTERM: the image
runs under `tini -g`). The job never deletes or overwrites anything: keys are unique per run (`daily/` is minute-stamped,
`weekly/` is a server-side copy of the same key), so retention is storage-side. Expiry is an R2 lifecycle rule
(`daily/` 14 d, `weekly/` 56 d, abort incomplete multipart uploads after 1 d) and **R2 Bucket Lock** retention rules of
the same length stop the token from deleting or overwriting a backup earlier (runbook E1).

Time budget: 13 minutes overall (checked before every step), `pg_dump` 10 minutes (`--lock-wait-timeout=60s`),
`PGCONNECT_TIMEOUT=10`, 120 s per request (+1 s per 512 KiB) with a 1 KiB/s floor, upload retries only on
transport failures, 429 and 5xx (a 403 or any other 4xx is final).

## Variables

| Variable | Required | Meaning |
|---|---|---|
| `DATABASE_URL` | yes | `postgresql://user:pass@host:port/db`. `sslmode=require` is added unless the URL has an `sslmode`. |
| `BACKUP_R2_ENDPOINT` | yes | Prod: exactly `https://<32-hex account id>.eu.r2.cloudflarestorage.com` (EU jurisdiction; curl is restricted to https). No path. |
| `BACKUP_R2_ACCESS_KEY_ID` / `BACKUP_R2_SECRET_ACCESS_KEY` | yes | Token scoped to the one backup bucket. Deliberately not `CLOUDFLARE_R2_*`. |
| `BACKUP_R2_BUCKET` | yes | `beautica-db-backups-prod`. The `-staging` name exists only as the non-prod signal for the IT and is refused when `RAILWAY_ENVIRONMENT_NAME=production`; the prod bucket is refused when that variable is set to anything else. |
| `BACKUP_AGE_RECIPIENT` | yes | `age1` + 58 bech32 characters (public key). The private key is never in Railway, GitHub or the repo. For the prod bucket it must equal the key committed in `prod-recipient.txt`, once one is committed. |
| `RAILWAY_ENVIRONMENT_NAME` | no | Set by Railway. When present it is cross-checked against the bucket (`production` <=> prod bucket). |
| `BACKUP_DB_SIZE_WARN_BYTES` | no | Default 4 GiB. Above it the backup is still uploaded and the run exits 2 (`size_warn`). |
| `BACKUP_FORCE_WEEKLY` | no | `1` also writes `weekly/` (Sundays UTC do it automatically). Operator input: "take a weekly before a risky migration". |
| `BACKUP_SENTRY_CRON_URL` | no | Sentry Cron check-in URL (a secret). Prod only. Must be `https://` (otherwise check-ins are skipped with a warning). Check-ins: `in_progress`, then `ok` or `error`. |

`DATABASE_URL` for the prod bucket must use `sslmode=require`, `verify-ca` or `verify-full` (default `require`).
The R2 secret and the check-in URL are copied to shell variables and unset, so `pg_dump`, `psql` and `age` never inherit them.

`prod-recipient.txt` (baked into the image at the fixed path `/etc/db-backup/prod-recipient.txt`, deliberately not
configurable by an environment variable) pins the prod recipient. It is empty (comments only) until runbook step E3.

Every run ends with exactly one line:
`db_backup_result status=<ok|size_warn|error> env=<prod|staging|unknown> dump_bytes=<n> db_bytes=<n> key=<daily key> weekly=<0|1> duration_s=<n>`
(`dump_bytes` is the size of the uploaded, encrypted object). Exit codes: 0 ok, 1 error, 2 size_warn. No URL, host,
key id or secret is ever printed, and no tool's stderr is forwarded (pg_dump/curl errors can carry the host); a
missing variable is named, never its value.

## Tests

- `DbBackupRoundTripIT` (`src/test/java/com/beautica/ops`): builds this image and runs it against a Flyway-migrated
  Postgres 18, an S3-compatible gateway that verifies SigV4 (stand-in for R2) and a WireMock Sentry stub, then
  decrypts and restores into a fresh Postgres 18 and compares the Flyway version and every table's row count.
  It also covers the guards: prod endpoint/recipient-pin/environment cross-wiring/sslmode/check-in scheme, no
  retry on a 403, a 503 then 200 retried, a lost reply (HEAD size + MD5 confirms the stored object, no retry), a hung `pg_dump` cut at the deadline, prod refused without a committed pin, the server-side weekly copy, secrets not inherited by `pg_dump`/`psql`/`age`, and passwords with a backslash.
  (The IT replaces the pin file by copying a file over `/etc/db-backup/prod-recipient.txt`; prod has no such path.)
  Tagged `db-backup-image`: it is excluded from the default Gradle test task and from the sharded CI runs.
  Run it with `./gradlew test -PdbBackupIt --tests com.beautica.ops.DbBackupRoundTripIT`.
  CI runs it (plus `shellcheck ops/db-backup/*.sh` and `ops/db-backup/check-pins.sh`, the `FROM ...@sha256:` and `apk add pkg=version` pin gate) on a PR only when
  `ops/db-backup/**` or the IT (`src/test/java/com/beautica/ops/`) changed, and every nightly.
- `scripts/tests/db-restore-drill.test.sh` (root repo) covers the restore drill script.

## Local round trip (manual, acceptance A5)

Needs Docker and the local database. MinIO no longer publishes public images, so a throwaway Versity S3 gateway
stands in for R2 (it verifies SigV4 and the `x-amz-content-sha256` header, as R2 does). All secrets are throwaway.

```bash
# from beautica-backend/
BE="$PWD"
docker compose -f docker/local/docker-compose.yml up -d postgres
docker build -t beautica-db-backup:local ops/db-backup

umask 077; W="$(mktemp -d)"; cd "$W"
docker run --rm --entrypoint age-keygen beautica-db-backup:local >key.txt 2>pub.txt   # key.txt = private key
REC="$(grep -o 'age1[a-z0-9]*' pub.txt)"
AK="AK$(openssl rand -hex 8)"; SK="$(openssl rand -hex 16)"
printf 'ROOT_ACCESS_KEY_ID=%s\nROOT_SECRET_ACCESS_KEY=%s\nVGW_REGION=auto\n' "$AK" "$SK" >gw.env
printf 'DATABASE_URL=postgresql://beautica:beautica@127.0.0.1:5432/beautica?sslmode=disable\nBACKUP_R2_ENDPOINT=http://127.0.0.1:17070\nBACKUP_R2_ACCESS_KEY_ID=%s\nBACKUP_R2_SECRET_ACCESS_KEY=%s\nBACKUP_R2_BUCKET=beautica-db-backups-staging\nBACKUP_AGE_RECIPIENT=%s\n' "$AK" "$SK" "$REC" >backup.env

# throwaway S3 gateway on the host network (secrets via --env-file, not argv)
docker run -d --rm --name beautica-rt-s3 --network host --env-file gw.env \
  ghcr.io/versity/versitygw:v1.9.0 --port :17070 posix /tmp
sleep 3

# signed request helper: the image's curl, credentials on a stdin curl config (host curl < 8 does not sign x-amz-content-sha256)
s3() { # s3 METHOD PATH [FILE-to-upload, relative to $PWD]
  local sha=e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
  [ -z "${3:-}" ] || sha="$(sha256sum "$3" | cut -d' ' -f1)"
  { printf 'silent\naws-sigv4 = "aws:amz:auto:s3"\nuser = "%s:%s"\n' "$AK" "$SK"
    printf 'header = "x-amz-content-sha256: %s"\nrequest = "%s"\nurl = "http://127.0.0.1:17070%s"\n' "$sha" "$1" "$2"
    [ -z "${3:-}" ] || printf 'upload-file = "/w/%s"\n' "$3"
  } | docker run --rm -i --user "$(id -u)" --network host -v "$PWD:/w" --entrypoint sh beautica-db-backup:local -c 'curl -K -'
}
printf '%s' '<CreateBucketConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/"><LocationConstraint>auto</LocationConstraint></CreateBucketConfiguration>' >mk.xml
s3 PUT /beautica-db-backups-staging mk.xml                                                # create the bucket

docker run --rm --network host --env-file backup.env beautica-db-backup:local             # the backup; ends in db_backup_result status=ok
KEY="$(s3 GET '/beautica-db-backups-staging?list-type=2' | grep -o 'daily/[^<]*' | head -1)"
s3 GET "/beautica-db-backups-staging/$KEY" >obj.dump.age                                  # fetch the object
# restore drill (repo root): decrypts and restores inside an isolated ephemeral container (no network, tmpfs).
# The key goes in on STDIN; for the prod key this is `bw get notes '<item>' | ... --identity -`, never a file.
"$BE/../scripts/db-restore-drill.sh" --file obj.dump.age --identity - <key.txt

# clean up: stop only the gateway you started, delete the throwaway key and files
docker rm -f beautica-rt-s3; cd /; shred -u "$W/key.txt"; rm -rf "$W"
```

Last recorded pass: 2026-10-16 (audit cycle 1 build), backup 1 s (1.4 MB object from the 26 MB local database), drill 4 s (V198, users=1, cities=25698; key on stdin, `--network none`, tmpfs).

## Bumping the pins

The `Dockerfile` pins the base image by digest and the apk packages by exact version; CI (`check-pins.sh`) fails if the `FROM` line loses `@sha256:` or any `apk add` package loses its `=version`.

```bash
docker pull postgres:18-alpine
docker image inspect postgres:18-alpine --format '{{index .RepoDigests 0}}'          # new FROM ...@sha256:<digest>
ops/db-backup/resolve-pins.sh ops/db-backup/Dockerfile                                 # versions the pinned base's Alpine release serves NOW
```

`resolve-pins.sh` runs `apk update && apk policy <pkgs>` inside the Dockerfile's pinned base (a bare `apk policy` without
`apk update` has no index and shows nothing) and exits 1 naming every pin that the repo no longer serves.

Edit `FROM` and the four `apk add pkg=version` pins together, rebuild, and run `DbBackupRoundTripIT`.

**Why the build breaks with no change here:** Alpine keeps only the latest revision of each package per release branch.
When a security fix lands (e.g. curl `8.22.0-r0` -> `8.23.0-r0`, October 2026) the old revision is deleted and
`apk add curl=8.22.0-r0` exits 1, which the nightly `DbBackupRoundTripIT` reports. Run `resolve-pins.sh`, bump the
package pin it names (the base digest only needs a bump when the Alpine release itself changes), rebuild, re-run the IT.

## Enable at closed-test start

Runbook § E in `docs/backend-phases/phase-366-db-backup-restore.md` (prod bucket with Bucket Lock, token, offline `age` key,
`prod-recipient.txt` pin, Railway cron service, Sentry monitor, first scheduled run, restore drill, R6 gate).
There is no staging rehearsal: the real-infra checks happen at E6 and E8 on prod.

## R. Prod restore runbook

Read once before you need it. The prod `age` private key lives only in Bitwarden and one paper copy. It is never a
file on the dev VM: it is pasted into a hidden prompt or piped from `bw`, and goes to `age` on stdin.

**Preferred path: a Railway one-off service on the private network** (no TCP proxy, no data through your machine).

1. In Railway `production`, create a new Postgres service `Postgres-restore` (template `postgres-ssl:18`). Leave it without a TCP proxy.
2. Create a temporary service `restore-job` from this image (`beautica-backend/ops/db-backup`, custom start command `sleep 3600`). The Dockerfile has `ENTRYPOINT ["/sbin/tini","-g","--"]` and `CMD ["/usr/bin/env","-u","BASH_ENV","-u","ENV","/bin/bash","/usr/local/bin/backup.sh"]`, so a start command replaces only the CMD (it runs under tini); if Railway replaces the ENTRYPOINT too, `sleep` simply runs bare. Both ways `backup.sh` does not run. Still check the deploy log shows no `db_backup:` line before you continue, and if it does, delete the service at once; the restore database is untouched because the job needs `DATABASE_URL`, which this service does not have.
   Give it reference variables only: `PGHOST`, `PGPORT`, `PGUSER`, `PGPASSWORD`, `PGDATABASE` from `Postgres-restore`, and the `BACKUP_R2_*` values from `db-backup`.
   Open a shell with `railway ssh`. The age key is never stored as a variable.
3. In that shell:
   ```bash
   export PGSSLMODE=require PGCONNECT_TIMEOUT=10     # connection details come from the PG* variables, never from argv
   # 3a. refuse anything but an EMPTY target (a restore must never overlay live data)
   [ "$(psql -X -At -c "SELECT to_regclass('public.flyway_schema_history') IS NULL")" = t ] || { echo "target is not empty: abort"; exit 1; }
   # 3b. fetch the chosen object (daily/... or weekly/...) with a signed GET from the BACKUP_R2_* values (the s3() helper above, GET, output to obj.dump.age)
   # 3c. decrypt and restore; the key is read from a hidden prompt and goes to age on stdin (no file, no argv)
   read -rs -p 'age secret key (the AGE-SECRET-KEY-1... line): ' AGE_KEY; echo
   printf '%s\n' "$AGE_KEY" | age -d -i - obj.dump.age | pg_restore --no-owner --no-privileges --exit-on-error -d "$PGDATABASE"
   unset AGE_KEY; rm -f obj.dump.age
   ```
4. Delete the `restore-job` service. Point the app at the restored database: set `DATABASE_URL=${{Postgres-restore.DATABASE_URL}}`
   on the app **and** on the `db-backup` service, then redeploy. Verify `/actuator/health`, the Flyway version
   (`flyway_schema_history`), and a login.
5. Retire the old Postgres service only after 7 days. On a plan with native volume backups, restoring the volume
   in place is the faster alternative (minutes).

**Fallback (TCP proxy)** only if the private-network path is unavailable: add a TCP proxy to `Postgres-restore` for this step only
and remove it right after. Never put the connection URL on a command line (it would show in `ps` and shell history):
`read -rs -p 'password: ' PGPASSWORD; export PGPASSWORD`, set `PGHOST`/`PGPORT`/`PGUSER`/`PGDATABASE` from the proxy
details, `export PGSSLMODE=require`, run the same empty-target check (3a), then `bw get notes '<item>' | age -d -i - obj.dump.age | pg_restore ...`
(3c with the key piped from `bw`). Do not restore a prod dump anywhere else (staging, local, long-lived containers).

Checklist: [ ] target empty (3a) [ ] `sslmode=require` [ ] key never written to a file [ ] no URL/password in argv
[ ] TCP proxy removed (fallback) [ ] `restore-job` and the downloaded object deleted [ ] Flyway version and a login verified.

Before the first tester signs up, prove the pipeline once (acceptance A10), from the dev VM, with the key piped from Bitwarden:
`bw get notes '<item>' | scripts/db-restore-drill.sh --file <obj> --identity -`.
