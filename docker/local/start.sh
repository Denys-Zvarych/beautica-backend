#!/usr/bin/env bash
# start.sh — Start the Beautica local Docker stack, PRESERVING the existing database.
#
# Containers are recreated but the Postgres data volume is kept, so all data
# (and Flyway migration history) survives across restarts. For a clean wipe,
# use start_fresh.sh instead.
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
# Only the CONTAINER is removed — named volumes are never touched, so the
# database survives. Containers owned by THIS project are left to `down`.
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
    echo "  Its named volumes are preserved — only the container is discarded."
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

  if command -v ss >/dev/null 2>&1; then
    if sudo -n ss -ltnp 'sport = :5432' 2>/dev/null | grep ':5432'; then
      :
    else
      ss -ltnp 'sport = :5432' 2>/dev/null | grep ':5432' || true
    fi
  fi

  if command -v docker >/dev/null 2>&1; then
    docker ps --filter 'publish=5432' \
      --format '  docker container: {{.Names}} ({{.Image}}) -> {{.Ports}}' 2>/dev/null || true
  fi
}

# This host's user is in the `docker` group, so the Docker CLI does NOT need sudo.
# Using sudo here would hang/fail on a password prompt in a non-interactive shell.
#
# Tear down THIS project's own stack FIRST, but KEEP the data volume (no `-v`).
# This releases the published port while preserving the database.
echo "Stopping existing stack (keeping database volume)..."
docker compose -f "$COMPOSE_FILE" down 2>/dev/null || true

# Runs AFTER `down` (so our own containers are already gone) and BEFORE the port
# probe below — a stray container may itself be the thing holding 5432, and
# clearing it first stops the probe from misattributing that to system Postgres.
remove_foreign_name_conflicts

echo "Checking host port 5432..."
if port_5432_in_use; then
  # The port survived our own `down`, so a NON-Docker process (typically the
  # system PostgreSQL service) holds it. Attempt to stop system PostgreSQL.
  echo "  Port 5432 still in use after tearing down our stack — attempting to stop the system PostgreSQL service..."
  if sudo -n systemctl stop 'postgresql*.service' 2>/dev/null; then
    echo "  Stopped system PostgreSQL (passwordless sudo)."
  else
    echo "  Passwordless sudo unavailable — you may be prompted for your password:"
    sudo systemctl stop 'postgresql*.service' || true
  fi

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
until docker inspect beautica-postgres --format '{{.State.Health.Status}}' 2>/dev/null | grep -q "healthy"; do
  sleep 2
done

echo ""
echo "Done. Existing database preserved — Flyway will apply only NEW migrations on next app start."
echo "  Postgres : localhost:5432  (user: beautica / pass: beautica / db: beautica)"
echo "  pgAdmin  : http://localhost:5050  (admin@admin.com / admin)"
echo "  Mailpit  : http://localhost:8025  (SMTP → localhost:1025, no auth)"
