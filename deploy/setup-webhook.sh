#!/usr/bin/env bash
# Registers (or inspects) the Telegram webhook. Run once per environment, never from the pipeline.
# Secrets are read from the environment and never echoed.
#
# Usage:
#   TELEGRAM_BOT_TOKEN=... TELEGRAM_WEBHOOK_SECRET=... ./setup-webhook.sh set https://bot.example.com/telegram/webhook
#   TELEGRAM_BOT_TOKEN=... ./setup-webhook.sh info
#   TELEGRAM_BOT_TOKEN=... ./setup-webhook.sh delete
set -euo pipefail

API_BASE="${TELEGRAM_API_BASE:-https://api.telegram.org}"
ACTION="${1:-}"

: "${TELEGRAM_BOT_TOKEN:?TELEGRAM_BOT_TOKEN is required}"

# The token travels in the URL path, so keep curl quiet about it and read the config from stdin.
call() {
  local method="$1"
  shift
  curl --silent --show-error --fail-with-body --max-time 20 \
    --config - <<<"url = \"${API_BASE}/bot${TELEGRAM_BOT_TOKEN}/${method}\"" "$@"
}

case "$ACTION" in
  set)
    URL="${2:?usage: setup-webhook.sh set <https-url>}"
    : "${TELEGRAM_WEBHOOK_SECRET:?TELEGRAM_WEBHOOK_SECRET is required}"
    if [[ "$URL" != https://* ]]; then
      echo "The webhook URL must use https" >&2
      exit 1
    fi
    call setWebhook \
      --data-urlencode "url=${URL}" \
      --data-urlencode "secret_token=${TELEGRAM_WEBHOOK_SECRET}" \
      --data-urlencode 'allowed_updates=["message"]' \
      --data-urlencode "drop_pending_updates=true"
    echo
    ;;
  info)
    call getWebhookInfo
    echo
    ;;
  delete)
    call deleteWebhook --data-urlencode "drop_pending_updates=true"
    echo
    ;;
  *)
    echo "Usage: $0 {set <https-url>|info|delete}" >&2
    exit 2
    ;;
esac
