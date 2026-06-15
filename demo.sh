#!/usr/bin/env bash
#
# End-to-end demo of the test-induction sidecar + sample-app.
#
# Prerequisites (in two other terminals):
#   cd test-induction && sbt run        # sidecar: single port :8080
#   cd sample-app     && mvn spring-boot:run   # app: :9090
#
set -euo pipefail

# Mock engine and control plane share one port; control lives under /__induction.
CONTROL=${CONTROL:-http://localhost:8080/__induction}
APP=${APP:-http://localhost:9090}
CALLER=payment-service

register() {
  curl -s -X POST "$CONTROL/register" \
    -H 'Content-Type: application/json' -d "$1" >/dev/null
  echo "registered profile: $2"
}

pay() {
  local profile=$1
  echo "--- /pay  (profile: ${profile:-<none>}) ---"
  local hdr=()
  [ -n "$profile" ] && hdr=(-H "x-induction-test-profile: $profile")
  curl -s -w '  [http %{http_code}, %{time_total}s]\n' -X POST "$APP/pay" \
    -H 'Content-Type: application/json' "${hdr[@]}" \
    -d '{"amount":42.00,"currency":"USD"}'
}

echo "== registering behaviors =="
register '{"profile":"happy","caller":"'$CALLER'","mapping":{"request":{"method":"POST","urlPath":"/payments"},"response":{"status":200,"headers":{"Content-Type":"application/json"},"jsonBody":{"paymentId":"pay_123","status":"CONFIRMED"}}}}' happy
register '{"profile":"payment-timeout","caller":"'$CALLER'","mapping":{"request":{"method":"POST","urlPath":"/payments"},"response":{"status":200,"fixedDelayMilliseconds":8000,"jsonBody":{"paymentId":"slow","status":"CONFIRMED"}}}}' payment-timeout
register '{"profile":"payment-500","caller":"'$CALLER'","mapping":{"request":{"method":"POST","urlPath":"/payments"},"response":{"status":500,"body":"upstream boom"}}}' payment-500
register '{"profile":"payment-malformed","caller":"'$CALLER'","mapping":{"request":{"method":"POST","urlPath":"/payments"},"response":{"status":200,"headers":{"Content-Type":"application/json"},"body":"{ this is : not valid json"}}}' payment-malformed
register '{"profile":"payment-reset","caller":"'$CALLER'","mapping":{"request":{"method":"POST","urlPath":"/payments"},"response":{"fault":"CONNECTION_RESET_BY_PEER"}}}' payment-reset

echo
echo "== status =="
curl -s "$CONTROL/status"; echo

echo
echo "== exercising behaviors =="
pay happy
pay payment-500
pay payment-malformed
pay payment-reset
pay payment-timeout
pay ""   # no profile -> nothing matches (zero impact)

echo
echo "== cleanup =="
curl -s -X POST "$CONTROL/reset" >/dev/null && echo "reset done"
