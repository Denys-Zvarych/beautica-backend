#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.yml"

# Compose project name — defaults to the compose file's directory name.
COMPOSE_PROJECT="$(basename "$SCRIPT_DIR")"

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
echo "Removing containers AND volumes (fresh database)..."
remove_foreign_name_conflicts
docker compose -f "$COMPOSE_FILE" down -v 2>/dev/null || true

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
until docker inspect beautica-postgres --format '{{.State.Health.Status}}' 2>/dev/null | grep -q "healthy"; do
  sleep 2
done

echo ""
echo "Done. Database is empty — Flyway will run all migrations on next app start."
echo "  Postgres : localhost:5432  (user: beautica / pass: beautica / db: beautica)"
echo "  pgAdmin  : http://localhost:5050  (admin@admin.com / admin)"
echo "  Mailpit  : http://localhost:8025  (SMTP → localhost:1025, no auth)"
