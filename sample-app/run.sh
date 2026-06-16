#!/usr/bin/env bash
# Start the sample-app (Spring Boot).
# Env overrides: SERVER_PORT (default 9090), INDUCTION_SIDECARHOST / INDUCTION_SIDECARPORT
# (default localhost:8080), PAYMENT_BASEURL (default http://localhost:9099).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
exec mvn -q spring-boot:run
