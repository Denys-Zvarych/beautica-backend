# Nightly encrypted pg_dump -> R2 (phase 366). Same Postgres major as TestPostgresImage / prod
# (pg_dump's client major must be >= the server major).
#
# Supply chain: the base is pinned by digest (Alpine 3.24.2) and so are the apk packages, which must be
# the versions that Alpine release serves. To bump (see README "Bumping the pins"): pull the tag, copy the
# new digest from `docker image inspect --format '{{index .RepoDigests 0}}'`, then read the matching
# versions with `./resolve-pins.sh` (runs `apk update && apk policy ...` in the pinned base; without `apk update`
# the image has no package index and `apk policy` shows nothing useful). Alpine DROPS a superseded revision from its
# repo when a security fix lands (curl 8.22.0-r0 -> 8.23.0-r0, 2026-10), so the build breaks with no change here.
# CI (pr-validate.yml, db-backup shellcheck job, ops/db-backup/check-pins.sh) fails if the FROM line loses its
# @sha256: pin or any apk package loses its =version.
FROM postgres:18-alpine@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873

RUN apk add --no-cache age=1.3.2-r1 curl=8.23.0-r0 coreutils=9.11-r0 tini=0.19.0-r3 \
 && addgroup -S backup \
 && adduser -S -G backup -H -h /nonexistent backup

COPY backup.sh /usr/local/bin/backup.sh
# The prod age recipient pin (REQUIRED: backup.sh refuses prod runs until runbook E3 commits it). A fixed in-image path, deliberately NOT an env var:
# changing it in prod means committing the file and rebuilding. Only the IT overrides it, by copying a file over it.
COPY prod-recipient.txt /etc/db-backup/prod-recipient.txt
RUN chmod 0755 /usr/local/bin/backup.sh && chmod 0644 /etc/db-backup/prod-recipient.txt

# ENTRYPOINT is only the init (tini), CMD is the job. A platform start command (Railway's custom start command, or
# `docker run <image> sleep 3600`) therefore replaces just the CMD and runs under tini; a platform that replaces the
# ENTRYPOINT as well runs it bare. Either way backup.sh does NOT run, which the prod restore runbook (README § R)
# relies on. With no start command the cron runs backup.sh.
# tini is PID 1 and, with -g, signals the whole process group: on SIGTERM (Railway stop) pg_dump/curl die,
# backup.sh's TERM trap exits, the EXIT trap removes the plaintext temp dir and emits the result line.
USER backup
ENTRYPOINT ["/sbin/tini", "-g", "--"]
# CMD starts bash through `env -u BASH_ENV -u ENV` with an absolute bash path: the script's OWN shell would otherwise source
# $BASH_ENV at startup (non-interactive bash does) and resolve `#!/usr/bin/env bash` through an inherited PATH. backup.sh's
# own PATH pin / `unset` only protects its children.
CMD ["/usr/bin/env", "-u", "BASH_ENV", "-u", "ENV", "/bin/bash", "/usr/local/bin/backup.sh"]
