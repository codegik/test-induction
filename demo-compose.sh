#!/usr/bin/env bash
#
# End-to-end demo of the test-induction sidecar + sample-app, running entirely in
# Docker via docker compose. Same story as ./demo.sh, but the apps are containers:
#
#   build & start both -> register a mock -> call the app (mocked) ->
#   unregister -> call again (different) -> call with no profile (bypass) -> down.
#
# Sidecar observability is shown after each step via `docker compose logs`.
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="$ROOT/docker-compose.yml"

CONTROL="http://localhost:8080/__induction"   # sidecar control plane (published)
APP="http://localhost:9090"                    # sample-app (published)
CALLER=payment-service
PROFILE=payment-confirmed
# The app's REAL payment base-url (its default); behaviors are keyed by it.
TARGET=${TARGET:-http://localhost:9099}

dc() { docker compose -f "$COMPOSE_FILE" "$@"; }

# --- pretty output -----------------------------------------------------------
bold() { printf '\033[1m%s\033[0m' "$*"; }
say()  { printf '\n%s\n' "$(bold "== $* ==")"; }
info() { printf '   %s\n' "$*"; }

cleanup() {
  say "shutting down (docker compose down)"
  dc down --remove-orphans >/dev/null 2>&1 || true
  info "stack removed"
}
trap cleanup EXIT

# --- observability via compose logs ------------------------------------------
side_logs() { dc logs --no-color --no-log-prefix sidecar 2>/dev/null | grep -aE 'MockEngine|InductionControlFilter' || true; }
logmark()   { side_logs | wc -l; }
show_logs() { # from-line
  local from=$1
  printf '   \033[2m┌─ sidecar logs (in container) ──────────────────────\033[0m\n'
  side_logs | tail -n "+$((from + 1))" | sed -E 's/^/   \x1b[2m│\x1b[0m /'
  printf '   \033[2m└────────────────────────────────────────────────────\033[0m\n'
}

# --- control-plane call with a short retry -----------------------------------
ctl() { # METHOD PATH [DATA]
  local method=$1 path=$2 data=${3:-} i
  for i in 1 2 3 4 5 6; do
    if [ -n "$data" ]; then
      curl -fs -m 10 -X "$method" "$CONTROL$path" -H 'Content-Type: application/json' -d "$data" && return 0
    else
      curl -fs -m 10 -X "$method" "$CONTROL$path" && return 0
    fi
    sleep 1
  done
  echo "ERROR: control $method $CONTROL$path failed after retries" >&2
  return 1
}

pay() { # profile label
  local profile=$1 label=$2 mark out code body hdr=()
  mark=$(logmark)
  say "$label"
  [ -n "$profile" ] && hdr=(-H "x-induction-test-profile: $profile")
  out=$(curl -s -w $'\n%{http_code}' -X POST "$APP/pay" \
    -H 'Content-Type: application/json' "${hdr[@]}" \
    -d '{"amount":42.00,"currency":"USD"}')
  code=$(tail -n1 <<<"$out"); body=$(sed '$d' <<<"$out")
  info "$(bold "HTTP $code"): $body"
  show_logs "$mark"
}

# --- 1) build + start the stack ----------------------------------------------
say "building images and starting the stack (this can take a while the first time)"
command -v docker >/dev/null || { echo "ERROR: docker is required" >&2; exit 1; }
dc up -d --build --wait --wait-timeout 300
info "sidecar and sample-app are up and healthy"

# --- 2) register a mock ------------------------------------------------------
say "register mock: profile '$PROFILE' -> 200 CONFIRMED for $TARGET/payments"
mark=$(logmark)
ctl POST /register "{\"profile\":\"$PROFILE\",\"caller\":\"$CALLER\",\"behaviors\":[{\"name\":\"payments\",\"match\":{\"baseUrl\":\"$TARGET\",\"method\":\"POST\",\"path\":\"/payments\"},\"response\":{\"status\":200,\"headers\":{\"Content-Type\":\"application/json\"},\"jsonBody\":{\"paymentId\":\"pay_123\",\"status\":\"CONFIRMED\"}}}]}" >/dev/null
info "registered"
show_logs "$mark"

# --- 3) call WITH the mock registered ---------------------------------------
pay "$PROFILE" "call /pay WITH mock registered  ->  expect SUCCESS (served by sidecar)"

# --- 4) unregister the mock --------------------------------------------------
say "unregister mock for profile '$PROFILE'"
mark=$(logmark)
ctl DELETE "/$PROFILE/$CALLER" >/dev/null
info "unregistered"
show_logs "$mark"

# --- 5) call again -> different result ---------------------------------------
pay "$PROFILE" "call /pay AFTER unregister      ->  expect DIFFERENT result (no mock -> 404 upstream)"

# --- 6) call with NO profile -> bypasses the sidecar entirely ----------------
pay "" "call /pay WITHOUT a profile     ->  bypasses sidecar, hits the REAL service (note: no sidecar logs below)"

say "done — mock vs unregistered vs no-profile all give different results"
