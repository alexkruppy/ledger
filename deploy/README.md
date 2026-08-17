# Deploy Guide

## Architecture

```
GitHub Push → CI (build + test + Docker build) → Push to ghcr.io
                                                 ↓
                                              CD (SSH) → VPS: docker compose pull + restart
```

## GitHub Repository Secrets

Go to **Settings → Secrets and variables → Actions** and add:

| Secret | Example | Description |
|---|---|---|
| `DEPLOY_HOST` | `123.45.67.89` | VPS IP or hostname |
| `DEPLOY_USER` | `deploy` | SSH user on VPS |
| `DEPLOY_SSH_KEY` | `-----BEGIN OPENSSH...` | Private SSH key |
| `DEPLOY_PATH` | `/opt/ledger` | Path to docker-compose.prod.yml on VPS |

## First-time VPS Setup

```bash
# SSH into your VPS as root
ssh root@YOUR_HOST

# Run the setup script (installs Docker, creates deploy user)
bash deploy/setup-vps.sh

# Generate SSH key pair for GitHub Actions
ssh-keygen -t ed25519 -C "github-actions" -f /tmp/deploy_key -N ""

# Add the public key to deploy user
cat /tmp/deploy_key.pub >> /home/deploy/.ssh/authorized_keys

# Copy the PRIVATE key content → paste into GitHub DEPLOY_SSH_KEY secret
cat /tmp/deploy_key

# Copy deploy scripts to VPS
scp -r deploy/* root@YOUR_HOST:/opt/ledger/

# Switch to deploy user and initialize
su - deploy
cd /opt/ledger
bash deploy.sh   # creates .env template, then Ctrl+C, edit .env, run again
```

## Environment Variables (.env on VPS)

```bash
# REQUIRED
POSTGRES_PASSWORD=<random-32-chars>
JWT_SECRET=<random-32-chars>

# OPTIONAL
POSTGRES_USER=ledger
GRAFANA_PASSWORD=<your-password>
```

## How It Works

1. **CI** (`.github/workflows/ci.yml`):
   - On push to `master` or PR: compile + test + package
   - On push to `master` only: build Docker images, push to `ghcr.io/alexkruppy/ledger:latest`

2. **CD** (`.github/workflows/deploy.yml`):
   - Triggers after CI succeeds on `master`
   - SSH into VPS, pull new images, rolling restart
   - Waits 30s, checks `/actuator/health`
   - Prunes images older than 7 days

3. **Docker Compose** (`docker-compose.prod.yml`):
   - All services bound to `127.0.0.1` (not exposed to internet)
   - Use nginx/caddy as reverse proxy for HTTPS
   - Persistent volumes for Postgres, Redis, Kafka, Grafana

## Reverse Proxy (nginx)

```nginx
server {
    listen 443 ssl http2;
    server_name ledger.yourdomain.com;

    ssl_certificate     /etc/letsencrypt/live/ledger.yourdomain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/ledger.yourdomain.com/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}

server {
    listen 443 ssl http2;
    server_name ledger-grafana.yourdomain.com;

    ssl_certificate     /etc/letsencrypt/live/ledger-grafana.yourdomain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/ledger-grafana.yourdomain.com/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:3000;
        proxy_set_header Host $host;
    }
}
```

## Manual Deploy

```bash
ssh deploy@YOUR_HOST
cd /opt/ledger
docker compose -f docker-compose.prod.yml pull
docker compose -f docker-compose.prod.yml up -d
```

## Logs

```bash
# App logs
docker compose -f docker-compose.prod.yml logs -f ledger-app

# All services
docker compose -f docker-compose.prod.yml logs -f

# Outbox poller / messaging
docker compose -f docker-compose.prod.yml logs -f ledger-app | grep -i outbox
```
