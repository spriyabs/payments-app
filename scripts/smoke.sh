#!/usr/bin/env bash
# Step 0 smoke test: every service answers. Grows into the full
# submit -> ACCP -> settle -> cancel flow as the build progresses (docs/spec.md section 11).
set -euo pipefail

API="${API_URL:-http://localhost:8080}"
MOCK="${MOCK_URL:-http://localhost:9090}"
UI="${UI_URL:-http://localhost:3000}"

fail=0
check() {
  local name="$1" url="$2"
  if curl -fsS --max-time 10 "$url" >/dev/null; then
    echo "ok   $name  $url"
  else
    echo "FAIL $name  $url"
    fail=1
  fi
}

check "payments-api" "$API/actuator/health"
check "meridian-mock"   "$MOCK/health"
check "payment-ui"      "$UI/"

if [ "$fail" -ne 0 ]; then
  echo "Smoke test failed"
  exit 1
fi
echo "Smoke test passed"
