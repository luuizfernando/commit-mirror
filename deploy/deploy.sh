#!/usr/bin/env bash
# Runs on the VPS (as the SSH forced command and for manual rollback):
set -euo pipefail

# Each stack (production, homolog) keeps its own copy of this script next to its compose file and .env.
APP_DIR="${APP_DIR:-$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")}"
HEALTH_TIMEOUT_SECONDS="${HEALTH_TIMEOUT_SECONDS:-120}"
TAG="${1:-${SSH_ORIGINAL_COMMAND:-}}"

if [[ ! "$TAG" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-rc\.[0-9]+)?$ ]]; then
  echo "Invalid or missing version tag" >&2
  exit 2
fi

cd "$APP_DIR"
ENV_FILE=".env"

current_tag() {
  grep -E '^IMAGE_TAG=' "$ENV_FILE" | head -n1 | cut -d= -f2
}

set_tag() {
  sed -i "s/^IMAGE_TAG=.*/IMAGE_TAG=$1/" "$ENV_FILE"
}

wait_healthy() {
  local deadline=$((SECONDS + HEALTH_TIMEOUT_SECONDS))
  local container status
  while ((SECONDS < deadline)); do
    container="$(docker compose ps -q app)"
    if [[ -n "$container" ]]; then
      status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$container")"
      [[ "$status" == "healthy" ]] && return 0
    fi
    sleep 3
  done
  return 1
}

PREVIOUS_TAG="$(current_tag || true)"
echo "Deploying $TAG (previous: ${PREVIOUS_TAG:-none})"

set_tag "$TAG"
docker compose pull app
docker compose up -d app

if wait_healthy; then
  echo "Deploy of $TAG is healthy"
  exit 0
fi

echo "Deploy of $TAG failed the health check" >&2
if [[ -n "$PREVIOUS_TAG" && "$PREVIOUS_TAG" != "$TAG" ]]; then
  echo "Rolling back to $PREVIOUS_TAG" >&2
  set_tag "$PREVIOUS_TAG"
  docker compose up -d app
  wait_healthy || echo "Previous version did not become healthy either" >&2
fi
exit 1
