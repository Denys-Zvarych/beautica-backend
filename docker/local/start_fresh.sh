#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.yml"

# Compose project name. NOT simply the directory basename: Compose resolves it from, in
# precedence order, a top-level `name:` key in the compose file, then $COMPOSE_PROJECT_NAME, then
# the project directory's basename. Getting this wrong is DESTRUCTIVE — remove_foreign_name_conflicts
# below compares it against each container's com.docker.compose.project label and `docker rm -f`s
# anything that does not match, so a mismatch makes the script delete THIS project's own containers
# as if they were strays. Ask Compose itself when `jq` is available; otherwise reproduce the last
# two precedence steps by hand.
#
# The `|| true` inside the substitution is NOT decoration. This runs under `set -e`, and a command
# substitution assigned to a plain variable makes the PIPELINE's exit status the whole simple
# command's status — so a non-zero `jq` (exit 2/4 on malformed stdout: an older Compose with no
# `--format json`, or a Compose build printing a deprecation banner to stdout) would ABORT THE
# SCRIPT here instead of falling through to the documented $COMPOSE_PROJECT_NAME/basename fallback
# below. `|| true` is what makes that fallback reachable.
resolve_compose_project() {
  local resolved
  if command -v jq >/dev/null 2>&1; then
    resolved="$(docker compose -f "$COMPOSE_FILE" config --format json 2>/dev/null | jq -r '.name // empty' 2>/dev/null || true)"
    if [ -n "$resolved" ]; then
      printf '%s\n' "$resolved"
      return 0
    fi
  fi
  printf '%s\n' "${COMPOSE_PROJECT_NAME:-$(basename "$SCRIPT_DIR")}"
}

COMPOSE_PROJECT="$(resolve_compose_project)"

# Every service here pins a fixed `container_name:`, so a container holding one
# of those names blocks `up` with "container name is already in use" — even when
# it is stopped, and even when it publishes a different port.
#
# `docker compose down` cannot clear it: `down` filters by the
# com.docker.compose.project label, so anything created by a bare `docker run`
# (or by a different project) is invisible to it. Remove such strays explicitly.
#
# Here this must also run BEFORE `down -v`: a stray still attached to the data
# volume makes the volume "in use" and `down -v` silently fails to remove it,
# which would leave a "fresh" start running on the OLD database.
remove_foreign_name_conflicts() {
  local name owner
  while read -r name; do
    [ -n "$name" ] || continue
    docker container inspect "$name" >/dev/null 2>&1 || continue

    owner="$(docker container inspect "$name" \
      --format '{{index .Config.Labels "com.docker.compose.project"}}' 2>/dev/null || true)"
    # A missing label prints as "<no value>" on some Docker versions.
    [ "$owner" = "<no value>" ] && owner=""
    [ "$owner" = "$COMPOSE_PROJECT" ] && continue

    echo "  Removing stray container '$name' (compose project: '${owner:-<none>}', expected '$COMPOSE_PROJECT')."
    docker rm -f "$name" >/dev/null 2>&1 || true
  done < <(awk '/^[[:space:]]+container_name:[[:space:]]*/ {print $2}' "$COMPOSE_FILE")
}

# Returns 0 if host TCP port 5432 is currently bound (listening), 1 otherwise.
# Works without root: prefer `ss`, fall back to `lsof`.
port_5432_in_use() {
  if command -v ss >/dev/null 2>&1; then
    ss -ltn 'sport = :5432' 2>/dev/null | grep -q ':5432'
  elif command -v lsof >/dev/null 2>&1; then
    lsof -ti tcp:5432 >/dev/null 2>&1
  else
    # No tooling to detect — assume free so we don't block the flow.
    return 1
  fi
}

# Best-effort diagnostic: print WHAT currently holds host port 5432.
# Never let the probe itself abort the script (it runs under `set -e`).
diagnose_port_5432() {
  echo "  Diagnosing what holds port 5432..."

  # 1. Listening socket + owning PID. `ss` shows the PID only with privileges,
  #    so try passwordless sudo first and degrade gracefully to an unprivileged
  #    probe (which still shows the socket, just without the PID).
  if command -v ss >/dev/null 2>&1; then
    if sudo -n ss -ltnp 'sport = :5432' 2>/dev/null | grep ':5432'; then
      :
    else
      ss -ltnp 'sport = :5432' 2>/dev/null | grep ':5432' || true
    fi
  fi

  # 2. Any Docker container (other than ours) publishing 5432.
  if command -v docker >/dev/null 2>&1; then
    docker ps --filter 'publish=5432' \
      --format '  docker container: {{.Names}} ({{.Image}}) -> {{.Ports}}' 2>/dev/null || true
  fi
}

# This host's user is in the `docker` group, so the Docker CLI does NOT need sudo.
# Using sudo here would hang/fail on a password prompt in a non-interactive shell.
#
# Tear down THIS project's own stack FIRST. The common cause of a busy port 5432
# is a leftover `beautica-postgres` container from a previous run — `down -v`
# releases its published port. We must do this before blaming system PostgreSQL.
PGDATA_VOLUME="${COMPOSE_PROJECT}_beautica-pgdata"

fail_fresh() {
  {
    echo ""
    echo "ERROR: $1"
    echo "The local database was NOT reset. Containers referencing volume '$PGDATA_VOLUME':"
    docker ps -a --filter "volume=$PGDATA_VOLUME" \
      --format '  {{.ID}} {{.Names}} ({{.Image}}) {{.Status}} project={{.Label "com.docker.compose.project"}}' || true
    echo "Remove them (docker rm -f <id>) and re-run this script."
  } >&2
  exit 1
}

# A "fresh" start that silently keeps the old volume boots on the OLD database (seen 2026-10-19
# and 2026-10-21: flyway_schema_history kept V199/V200 → checksum mismatch). So every critical
# step below shows its stderr, and the wipe is VERIFIED rather than assumed.
echo "Removing containers AND volumes (fresh database)..."
remove_foreign_name_conflicts
if ! docker compose -f "$COMPOSE_FILE" down -v --remove-orphans; then
  echo "  WARNING: 'docker compose down -v' failed — removing the data volume explicitly." >&2
fi

# Belt and braces: remove the data volume by its resolved name, whatever `down -v` did.
if docker volume inspect "$PGDATA_VOLUME" >/dev/null 2>&1; then
  echo "  Volume '$PGDATA_VOLUME' survived 'down -v' — removing it explicitly."
  docker volume rm "$PGDATA_VOLUME" || fail_fresh "could not remove volume '$PGDATA_VOLUME'."
fi
if docker volume inspect "$PGDATA_VOLUME" >/dev/null 2>&1; then
  fail_fresh "volume '$PGDATA_VOLUME' still exists after removal."
fi
echo "  Volume '$PGDATA_VOLUME' removed."

echo "Checking host port 5432..."
if port_5432_in_use; then
  # The port survived our own `down -v`, so a NON-Docker process (typically the
  # system PostgreSQL service) holds it. Attempt to stop system PostgreSQL.
  echo "  Port 5432 still in use after tearing down our stack — attempting to stop the system PostgreSQL service..."
  # Stop BOTH the wrapper unit (postgresql.service) and the versioned cluster
  # unit (postgresql@14-main.service) — the cluster unit is what holds the port.
  # Try passwordless sudo first; if that fails, fall back to an interactive
  # stop so the user gets a chance to type their password (output NOT hidden).
  if sudo -n systemctl stop 'postgresql*.service' 2>/dev/null; then
    echo "  Stopped system PostgreSQL (passwordless sudo)."
  else
    echo "  Passwordless sudo unavailable — you may be prompted for your password:"
    sudo systemctl stop 'postgresql*.service' || true
  fi

  # Give the kernel a moment to release the socket, then re-check.
  sleep 1
  if port_5432_in_use; then
    {
      echo ""
      echo "ERROR: host port 5432 is STILL in use after tearing down our stack and stopping system PostgreSQL."
      echo "The Docker container 'beautica-postgres' cannot bind to 5432."
      diagnose_port_5432
      echo ""
      echo "Free the port manually, then re-run this script. If it is system PostgreSQL:"
      echo "  sudo systemctl stop postgresql postgresql@14-main"
      echo ""
      echo "Or disable host PostgreSQL permanently (it will no longer auto-start):"
      echo "  sudo systemctl disable --now postgresql"
    } >&2
    exit 1
  fi
  echo "  Port 5432 is now free."
else
  echo "  Port 5432 is free — proceeding."
fi

echo "Starting Beautica local stack..."
docker compose -f "$COMPOSE_FILE" up -d

echo "Waiting for Postgres to be healthy..."
# Exact match: `grep -q healthy` would also accept "unhealthy". Capped so a broken container fails
# loudly instead of looping forever.
health=""
for _ in $(seq 1 60); do
  health="$(docker inspect beautica-postgres --format '{{.State.Health.Status}}' 2>/dev/null || true)"
  [ "$health" = "healthy" ] && break
  sleep 2
done
[ "$health" = "healthy" ] || fail_fresh "beautica-postgres did not become healthy within 120 s (last status: '${health:-<none>}')."

# Verify the database really is empty. A surviving flyway_schema_history means the old data came back.
leftover="$(docker exec beautica-postgres psql -U beautica -d beautica -tAc \
  "SELECT count(*) FROM information_schema.tables WHERE table_schema NOT IN ('pg_catalog','information_schema')")" \
  || fail_fresh "could not query the new database to verify it is empty."
if [ "$leftover" != "0" ]; then
  fail_fresh "the database is NOT empty after the reset ($leftover table(s); flyway_schema_history present: $(docker exec beautica-postgres psql -U beautica -d beautica -tAc "SELECT to_regclass('public.flyway_schema_history') IS NOT NULL" || echo '?'))."
fi
echo "  Verified: volume '$PGDATA_VOLUME' created $(docker volume inspect "$PGDATA_VOLUME" --format '{{.CreatedAt}}'), database has 0 tables."

echo ""
echo "Done. Database is empty — Flyway will run all migrations on next app start."
echo "  Postgres : localhost:5432  (user: beautica / pass: beautica / db: beautica)"
echo "  pgAdmin  : http://localhost:5050  (admin@admin.com / admin)"
echo "  Mailpit  : http://localhost:8025  (SMTP → localhost:1025, no auth)"
