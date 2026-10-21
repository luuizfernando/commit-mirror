#!/usr/bin/env bash
# Starts the given image next to a throwaway Postgres and waits for the container healthcheck.
# Usage: smoke-test.sh <image:tag>
set -euo pipefail

IMAGE="$1"
SUFFIX="$$"
NETWORK="smoke-net-$SUFFIX"
DB="smoke-db-$SUFFIX"
APP="smoke-app-$SUFFIX"

# shellcheck disable=SC2317,SC2329 # invoked through trap (older shellcheck reports SC2317, newer SC2329)
cleanup() {
  docker rm -f "$APP" "$DB" >/dev/null 2>&1 || true
  docker network rm "$NETWORK" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker network create "$NETWORK" >/dev/null
docker run -d --name "$DB" --network "$NETWORK" \
  -e POSTGRES_DB=smoke -e POSTGRES_USER=smoke -e POSTGRES_PASSWORD=smoke \
  postgres:17-alpine >/dev/null
sleep 8
docker run -d --name "$APP" --network "$NETWORK" \
  -e DB_URL="jdbc:postgresql://$DB:5432/smoke" -e POSTGRES_USER=smoke -e POSTGRES_PASSWORD=smoke \
  "$IMAGE" >/dev/null

for _ in $(seq 1 40); do
  status="$(docker inspect --format '{{.State.Health.Status}}' "$APP")"
  if [[ "$status" == "healthy" ]]; then
    echo "Smoke test passed"
    exit 0
  fi
  sleep 3
done

echo "Smoke test failed (status: $status)" >&2
docker logs "$APP" | tail -n 50 >&2
exit 1
