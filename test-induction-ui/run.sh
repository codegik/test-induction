#!/usr/bin/env bash
# Start the test-induction UI in dev mode (Vite dev server + /api proxy to the
# sidecar, with hot reload). The Docker image instead builds and serves via
# server.mjs (see Dockerfile).
# Env overrides: UI_PORT (default 8090), INDUCTION_API_BASEURL (default http://localhost:8080).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
[ -d node_modules ] || npm install
exec npm run dev
