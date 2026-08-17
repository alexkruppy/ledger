#!/usr/bin/env bash
set -euo pipefail

# Run this ONCE on a fresh Ubuntu/Debian VPS to set up the deploy environment.

echo ">>> Updating system..."
sudo apt-get update -qq
sudo apt-get install -y -qq docker.io docker-compose-v2 curl jq

echo ">>> Adding deploy user..."
sudo useradd -m -s /bin/bash -G docker deploy 2>/dev/null || true

echo ">>> Setting up SSH..."
sudo mkdir -p /home/deploy/.ssh
sudo chmod 700 /home/deploy/.ssh

if [ ! -f /home/deploy/.ssh/authorized_keys ]; then
  echo ">>> Add your GitHub Actions SSH public key to /home/deploy/.ssh/authorized_keys"
  echo ">>> Generate one with: ssh-keygen -t ed25519 -C 'github-actions'"
fi
sudo chmod 600 /home/deploy/.ssh/authorized_keys 2>/dev/null || true
sudo chown -R deploy:deploy /home/deploy/.ssh

echo ">>> Setting up deploy directory..."
sudo mkdir -p /opt/ledger
sudo chown deploy:deploy /opt/ledger

echo ">>> Setting up Docker log rotation..."
sudo mkdir -p /etc/docker
cat <<'EOF' | sudo tee /etc/docker/daemon.json > /dev/null
{
  "log-driver": "json-file",
  "log-opts": {
    "max-size": "10m",
    "max-file": "3"
  }
}
EOF
sudo systemctl restart docker

echo ">>> Setup complete!"
echo ""
echo "Next steps:"
echo "  1. Copy deploy scripts to /opt/ledger/"
echo "  2. ssh deploy@<host> and run: cd /opt/ledger && bash deploy.sh"
echo "  3. Edit /opt/ledger/.env with real secrets"
echo "  4. Set GitHub repo secrets: DEPLOY_HOST, DEPLOY_USER, DEPLOY_SSH_KEY, DEPLOY_PATH"
