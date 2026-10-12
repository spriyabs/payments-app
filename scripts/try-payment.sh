#!/usr/bin/env bash
# Tries the payment endpoints with curl (same flow as payments-api/http/payments.http).
#
#   docker compose up -d postgres meridian-mock
#   cd payments-api && mvn spring-boot:run        # in another terminal
#   ./scripts/try-payment.sh
#
# Optional: API=http://localhost:8080 OUTCOME=tok_decline_funds ./scripts/try-payment.sh
# Tokenisation is out of scope, so the script invents a single-use token. The mock picks the
# outcome from its prefix: tok_decline_funds (AM04), tok_decline_blocked (AC04),
# tok_decline_invalid (FF01), tok_decline_other (NARR), tok_slow (accepted after 3 minutes).
# Any other tok_... token is accepted.
set -u

API="${API:-http://localhost:8080}"
OUTCOME="${OUTCOME:-tok}"
KEY="demo-$(date +%s)"
TOKEN="${OUTCOME}_$(date +%s)_$RANDOM"

show() {
  # Prints the HTTP status and the body of the last response.
  echo "  -> HTTP $1"
  echo "  $2"
  echo
}

call() {
  # call METHOD URL [extra curl args...]; sets STATUS and BODY
  local out
  out=$(curl -s -w '\n%{http_code}' -X "$@")
  STATUS=$(echo "$out" | tail -n1)
  BODY=$(echo "$out" | sed '$d')
}

echo "Using single-use token $TOKEN"
echo

payment_body() {
  echo "{\"token\":\"$TOKEN\",\"amount\":{\"value\":\"$1\",\"currency\":\"$2\"}}"
}

echo "1. Submit the payment (expect 202, status PDNG)"
call POST "$API/api/payments" -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" \
  -d "$(payment_body 125.50 GBP)"
show "$STATUS" "$BODY"
PAYMENT_ID=$(echo "$BODY" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')

echo "2. Repeat the same request (expect 202 and the SAME id)"
call POST "$API/api/payments" -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" \
  -d "$(payment_body 125.50 GBP)"
show "$STATUS" "$BODY"

echo "3. Same key, different amount (expect 409 IDEMPOTENCY_KEY_CONFLICT)"
call POST "$API/api/payments" -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" \
  -d "$(payment_body 999.00 GBP)"
show "$STATUS" "$BODY"

echo "4. Read it back (expect 200)"
call GET "$API/api/payments/$PAYMENT_ID"
show "$STATUS" "$BODY"

echo "5. Wrong amount format (expect 400 VALIDATION_ERROR)"
call POST "$API/api/payments" -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY-fmt" \
  -d "$(payment_body 125.5 GBP)"
show "$STATUS" "$BODY"

echo "6. Currency Meridian cannot take (expect 422 CURRENCY_NOT_SUPPORTED)"
call POST "$API/api/payments" -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY-jpy" \
  -d "$(payment_body 500.00 JPY)"
show "$STATUS" "$BODY"

echo "7. Reuse the token with a new key (expect 422 PROVIDER_REJECTED, tokens are single-use)"
call POST "$API/api/payments" -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY-reuse" \
  -d "$(payment_body 125.50 GBP)"
show "$STATUS" "$BODY"

echo "8. Cancel the first payment (expect 200 and CANC; 409 if the mock has already settled it)"
call POST "$API/api/payments/$PAYMENT_ID/cancellation"
show "$STATUS" "$BODY"

echo "9. Cancel it again (expect 200, the cancel is idempotent)"
call POST "$API/api/payments/$PAYMENT_ID/cancellation"
show "$STATUS" "$BODY"

echo "Done. The cancelled payment stays CANC even if a late callback arrives."
