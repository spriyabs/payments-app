#!/usr/bin/env bash
# Sends a correctly signed Meridian webhook to the API, so you can move a payment on by hand.
#
#   ./scripts/send-webhook.sh <endToEndId> <STATUS> [REASON]
#
#   ./scripts/send-webhook.sh 3f9a7c1e5b2d4e8a9c1f0a6d2b7e41c2 ACCP
#   ./scripts/send-webhook.sh 3f9a7c1e5b2d4e8a9c1f0a6d2b7e41c2 RJCT AM04
#
# Find the endToEndId in the response of POST /api/payments. Needs openssl.
# Optional: API=http://localhost:8080 SECRET=local-dev-webhook-secret EVENT_ID=evt_demo ./scripts/send-webhook.sh ...
# Re-run with the same EVENT_ID to see duplicate handling (a 200 that changes nothing).
set -eu

if [ $# -lt 2 ]; then
  echo "Usage: $0 <endToEndId> <PDNG|ACCP|ACSC|RJCT|CANC> [AM04|AC04|FF01|NARR]" >&2
  exit 1
fi

API="${API:-http://localhost:8080}"
SECRET="${SECRET:-${MERIDIAN_WEBHOOK_SECRET:-local-dev-webhook-secret}}"
END_TO_END_ID="$1"
STATUS="$2"
REASON="${3:-}"
EVENT_ID="${EVENT_ID:-evt_manual_$(date +%s)_$RANDOM}"

if [ -n "$REASON" ]; then
  REASON_JSON="\"$REASON\""
else
  REASON_JSON="null"
fi
OCCURRED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

BODY="{\"eventId\":\"$EVENT_ID\",\"paymentId\":\"pay_manual\",\"endToEndId\":\"$END_TO_END_ID\",\"status\":\"$STATUS\",\"statusReason\":$REASON_JSON,\"occurredAt\":\"$OCCURRED_AT\"}"

# Lowercase hex HMAC-SHA256 of the exact body bytes (printf avoids a trailing newline).
SIGNATURE="$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$SECRET" | sed 's/^.* //')"

echo "POST $API/webhooks/meridian  (event $EVENT_ID, status $STATUS)"
curl -s -o /dev/null -w "HTTP %{http_code}\n" -X POST "$API/webhooks/meridian" \
  -H 'Content-Type: application/json' \
  -H "X-Meridian-Signature: $SIGNATURE" \
  --data-binary "$BODY"
