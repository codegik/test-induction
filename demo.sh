#!/usr/bin/env bash
#
# Self-contained end-to-end demo of the test-induction sidecar + sample-app.
#
# It starts BOTH apps, registers a mock behavior, calls the sample app (which is
# served the mock), then UNregisters the mock and calls again (different result).
# After every call it prints the sidecar's own observability logs so you can see
# exactly what the mock engine did.
#
# Usage:  ./demo.sh        # starts/stops both apps for you
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

SIDE_PORT=${SIDE_PORT:-8080}
APP_PORT=${APP_PORT:-9090}
CONTROL="http://localhost:${SIDE_PORT}/__induction"
APP="http://localhost:${APP_PORT}"
CALLER=payment-service
PROFILE=payment-confirmed
# The REAL external payment service URL the app is configured to call. Behaviors
# are keyed by this full base-url; the app proxies to the sidecar only when a
# profile header is present.
TARGET=${TARGET:-http://localhost:9099}

LOGDIR=$(mktemp -d)
SIDE_LOG="$LOGDIR/sidecar.log"
APP_LOG="$LOGDIR/sample-app.log"
SIDE_PID="" ; APP_PID=""

# --- pretty output -----------------------------------------------------------
bold() { printf '\033[1m%s\033[0m' "$*"; }
say()  { printf '\n%s\n' "$(bold "== $* ==")"; }
info() { printf '   %s\n' "$*"; }

# --- lifecycle ---------------------------------------------------------------
kill_port() {
  local pid; pid=$(lsof -ti tcp:"$1" 2>/dev/null || true)
  if [ -n "$pid" ]; then kill "$pid" 2>/dev/null || true; sleep 1; kill -9 "$pid" 2>/dev/null || true; fi
}

cleanup() {
  say "shutting down"
  [ -n "$APP_PID" ]  && { info "stopping sample-app"; kill_port "$APP_PORT"; }
  [ -n "$SIDE_PID" ] && { info "stopping sidecar";    kill_port "$SIDE_PORT"; }
  info "logs kept in $LOGDIR"
}
trap cleanup EXIT

require_free() {
  if lsof -ti tcp:"$1" >/dev/null 2>&1; then
    echo "ERROR: port $1 is already in use — stop that process first." >&2
    exit 1
  fi
}

wait_up() { # url name max-attempts
  local url=$1 name=$2 max=${3:-90} i code
  for ((i = 1; i <= max; i++)); do
    code=$(curl -s -o /dev/null -w '%{http_code}' "$url" 2>/dev/null || echo 000)
    [ "$code" != 000 ] && { info "$name is up"; return 0; }
    sleep 2
  done
  echo "ERROR: $name did not come up — see $SIDE_LOG / $APP_LOG" >&2
  exit 1
}

# --- observability -----------------------------------------------------------
logmark() { wc -l < "$SIDE_LOG" 2>/dev/null || echo 0; }

show_logs() { # from-line
  local from=$1
  printf '   \033[2m┌─ sidecar logs ─────────────────────────────────────\033[0m\n'
  tail -n "+$((from + 1))" "$SIDE_LOG" 2>/dev/null \
    | grep -aE 'MockEngine|InductionControlFilter' \
    | sed -E 's/^\[info\] //; s/^/   \x1b[2m│\x1b[0m /' || true
  printf '   \033[2m└────────────────────────────────────────────────────\033[0m\n'
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

# --- 1) start both apps ------------------------------------------------------
say "starting sidecar (:$SIDE_PORT) and sample-app (:$APP_PORT)"
require_free "$SIDE_PORT"
require_free "$APP_PORT"

# Redirect stdin from /dev/null: the sidecar's build uses `connectInput := true`,
# so a backgrounded `sbt run` reading the terminal would get SIGTTIN and stop.
( cd "$ROOT/test-induction" && exec sbt -batch run ) > "$SIDE_LOG" 2>&1 < /dev/null &
SIDE_PID=$!
wait_up "$CONTROL/health" "sidecar"

( cd "$ROOT/sample-app" && exec mvn -q spring-boot:run ) > "$APP_LOG" 2>&1 < /dev/null &
APP_PID=$!
wait_up "$APP/pay" "sample-app"   # GET /pay -> 405, but proves it's listening

# --- 2) register a mock ------------------------------------------------------
say "register mock: profile '$PROFILE' -> 200 CONFIRMED for $TARGET/payments"
mark=$(logmark)
curl -s -X POST "$CONTROL/register" -H 'Content-Type: application/json' -d "{
  \"profile\": \"$PROFILE\",
  \"caller\":  \"$CALLER\",
  \"behaviors\": [
    { \"name\": \"payments\",
      \"match\": { \"baseUrl\": \"$TARGET\", \"method\": \"POST\", \"path\": \"/payments\" },
      \"response\": { \"status\": 200, \"headers\": { \"Content-Type\": \"application/json\" },
                      \"jsonBody\": { \"paymentId\": \"pay_123\", \"status\": \"CONFIRMED\" } } }
  ]
}" >/dev/null
info "registered"
show_logs "$mark"

# --- 3) call WITH the mock registered ---------------------------------------
pay "$PROFILE" "call /pay WITH mock registered  ->  expect SUCCESS (served by sidecar)"

# --- 4) unregister the mock --------------------------------------------------
say "unregister mock for profile '$PROFILE'"
mark=$(logmark)
curl -s -X DELETE "$CONTROL/$PROFILE/$CALLER" >/dev/null
info "unregistered"
show_logs "$mark"

# --- 5) call again -> different result ---------------------------------------
pay "$PROFILE" "call /pay AFTER unregister      ->  expect DIFFERENT result (no mock -> 404 upstream)"

say "done — same request, different result before vs after unregister"
