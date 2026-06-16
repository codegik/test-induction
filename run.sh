#!/usr/bin/env bash
# Rebuild all three module images, then start the full stack. Ctrl-C stops it.
#
#   sidecar      :8080   (mock engine + /__induction control plane)
#   ui           :8090   (mock manager)
#   sample-app   :9090
#
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

exec docker compose up --build --remove-orphans
