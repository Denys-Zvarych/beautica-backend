#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.yml"

echo "Stopping system PostgreSQL if running..."
# Best-effort; -n so it never blocks on a password prompt in non-interactive shells.
sudo -n systemctl stop postgresql 2>/dev/null || true

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
