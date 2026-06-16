#!/usr/bin/env bash
# Start the test-induction sidecar: mock engine + /__induction control plane +
# bundled UI, all on one port. UI at http://localhost:8080/__induction/ui/
# Env overrides: INDUCTION_MOCK_PORT (default 8080), INDUCTION_DB_PATH (default data/requests).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

# Bundle the UI once so the sidecar can serve it. For live UI development, run the
# Vite dev server instead (ui/run.sh). Best-effort: a missing npm just means the
# sidecar starts without the bundled UI.
WEB=src/main/resources/web
if [ ! -f "$WEB/index.html" ] && [ -d ui ] && command -v npm >/dev/null; then
  echo "[run] building UI bundle into $WEB ..."
  if ( cd ui && npm install && npm run build ); then
    rm -rf "$WEB"; mkdir -p "$WEB"; cp -r ui/dist/. "$WEB/"
  else
    echo "[run] UI build failed; starting sidecar without the bundled UI"
  fi
fi

exec sbt -batch run
