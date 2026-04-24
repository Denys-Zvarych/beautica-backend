#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.yml"

echo "Stopping system PostgreSQL if running..."
sudo systemctl stop postgresql 2>/dev/null || true

echo "Starting Beautica local stack..."
sudo docker compose -f "$COMPOSE_FILE" down 2>/dev/null || true
sudo docker compose -f "$COMPOSE_FILE" up -d

echo "Waiting for Postgres to be healthy..."
until sudo docker inspect beautica-postgres --format '{{.State.Health.Status}}' 2>/dev/null | grep -q "healthy"; do
  sleep 2
done

echo ""
echo "Done."
echo "  Postgres : localhost:5432  (user: beautica / pass: beautica / db: beautica)"
echo "  pgAdmin  : http://localhost:5050  (admin@admin.com / admin)"
