#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.yml"

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

echo "Stopping system PostgreSQL if running..."
if port_5432_in_use; then
  echo "  Port 5432 is in use — attempting to stop the system PostgreSQL service..."
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
      echo "ERROR: host port 5432 is STILL in use after attempting to stop system PostgreSQL."
      echo "The Docker container 'beautica-postgres' cannot bind to 5432."
      echo ""
      echo "Free the port manually, then re-run this script:"
      echo "  sudo systemctl stop postgresql postgresql@14-main"
      echo ""
      echo "Or disable host PostgreSQL permanently (it will no longer auto-start):"
      echo "  sudo systemctl disable --now postgresql"
    } >&2
    exit 1
  fi
  echo "  Port 5432 is now free."
else
  echo "  Port 5432 is free — nothing to stop."
fi

# This host's user is in the `docker` group, so the Docker CLI does NOT need sudo.
# Using sudo here would hang/fail on a password prompt in a non-interactive shell.
echo "Removing containers AND volumes (fresh database)..."
docker compose -f "$COMPOSE_FILE" down -v 2>/dev/null || true

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
