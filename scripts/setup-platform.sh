#!/usr/bin/env bash
# One-time setup: registers the "araba-skor" platform and its default role on
# user-platform-service. Safe to re-run (tolerates 409 Conflict).
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
if [ -f .env ]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi

: "${ADMIN_SECRET:?ADMIN_SECRET is not set (put it in .env or export it)}"
USER_PLATFORM_SERVICE_URL="${USER_PLATFORM_SERVICE_URL:-http://localhost:8089}"
PLATFORM_SLUG="${PLATFORM_SLUG:-araba-skor}"

call() {
  local method="$1" path="$2" body="${3:-}"
  local response status
  if [ -n "$body" ]; then
    response=$(curl -sS -w '\n%{http_code}' -X "$method" "$USER_PLATFORM_SERVICE_URL$path" \
      -H "X-Admin-Secret: $ADMIN_SECRET" -H "Content-Type: application/json" -d "$body")
  else
    response=$(curl -sS -w '\n%{http_code}' -X "$method" "$USER_PLATFORM_SERVICE_URL$path" \
      -H "X-Admin-Secret: $ADMIN_SECRET")
  fi
  status=$(echo "$response" | tail -n1)
  body_out=$(echo "$response" | sed '$d')
  echo "$method $path -> $status"
  echo "$body_out"
  if [ "$status" != "200" ] && [ "$status" != "201" ] && [ "$status" != "409" ]; then
    echo "Unexpected status $status from $method $path" >&2
    exit 1
  fi
}

echo "== Creating platform '$PLATFORM_SLUG' =="
call POST "/v1/platforms" "{\"name\": \"Araba Skor\", \"slug\": \"$PLATFORM_SLUG\"}"

echo "== Creating 'member' role (auto-assigned to new users) =="
call POST "/v1/platforms/$PLATFORM_SLUG/roles" "{\"name\": \"member\"}"

echo "== Ensuring a 'free' tier plan exists (required for the context endpoint to auto-subscribe new users) =="
existing_plans=$(curl -sS "$USER_PLATFORM_SERVICE_URL/v1/platforms/$PLATFORM_SLUG/plans")
if echo "$existing_plans" | grep -q '"tier":"free"'; then
  echo "Free plan already exists, skipping."
else
  call POST "/v1/platforms/$PLATFORM_SLUG/plans" \
    '{"name": "Free", "tier": "free", "features": {"daily_reviews": {"limit": 10, "period": "daily"}}, "lemonSqueezyVariantId": null}'
fi

echo "== Plans for '$PLATFORM_SLUG' =="
curl -sS "$USER_PLATFORM_SERVICE_URL/v1/platforms/$PLATFORM_SLUG/plans"
echo
echo "Done."
