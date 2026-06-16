#!/usr/bin/env bash
# Start the test-induction sidecar (mock engine + /__induction control plane).
# Env overrides: INDUCTION_MOCK_PORT (default 8080).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
exec sbt -batch run
