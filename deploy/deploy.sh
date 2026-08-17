#!/usr/bin/env bash
set -euo pipefail

# Ledger VPS Deploy Script
# Run once on first setup, then CI/CD takes over.
#
# Prerequisites on the VPS:
#   - Docker + Docker Compose v2 installed
#   - User 'deploy' with docker group membership
#   - SSH key from GitHub Actions added to ~/.ssh/authorized_keys

DEPLOY_DIR="${DEPLOY_DIR:-/opt/ledger}"
REPO="ghcr.io/alexkruppy/ledger"
REPO_MOCK="ghcr.io/alexkruppy/ledger-mock-gateway"

mkdir -p "$DEPLOY_DIR"
cd "$DEPLOY_DIR"

# --- .env file ---
if [ ! -f .env ]; then
  echo ">>> Creating .env template — EDIT IT BEFORE RUNNING THE APP"
  cat > .env <<'EOF'
# Required
POSTGRES_PASSWORD=CHANGE_ME
JWT_SECRET=CHANGE_ME_AT_LEAST_32_CHARS

# Optional
POSTGRES_USER=ledger
GRAFANA_PASSWORD=admin
EOF
  chmod 600 .env
  echo ">>> Created $DEPLOY_DIR/.env — fill in secrets and re-run"
  exit 0
fi

# --- Login to GHCR ---
echo ">>> Logging into GitHub Container Registry..."
echo "${GHCR_TOKEN}" | docker login ghcr.io -u "${GHCR_USER:-alexkruppy}" --password-stdin

# --- Pull images ---
echo ">>> Pulling images..."
docker compose -f docker-compose.prod.yml pull

# --- Deploy ---
echo ">>> Starting services..."
docker compose -f docker-compose.prod.yml up -d --remove-orphans

# --- Wait for health ---
echo ">>> Waiting for app to become healthy (30s)..."
sleep 30

if curl -sf http://localhost:8080/actuator/health > /dev/null 2>&1; then
  echo ">>> Ledger is healthy"
else
  echo ">>> WARNING: health check failed"
  docker compose -f docker-compose.prod.yml logs --tail=50 ledger-app
  exit 1
fi

echo ">>> Deploy complete"
