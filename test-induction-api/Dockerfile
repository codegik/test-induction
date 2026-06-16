# syntax=docker/dockerfile:1
#
# Single deployment unit: the JVM sidecar serves the mock engine, the
# /__induction control plane, AND the bundled React UI (ui/) — all on one port.
#
#   docker build -t test-induction test-induction-api

# ---------- stage 1: build the React UI ----------
FROM node:22-bookworm-slim AS ui
WORKDIR /ui
COPY ui/package.json ui/package-lock.json* ./
RUN npm ci || npm install
COPY ui/ ./
# Built with base /__induction/ui/ so the sidecar can serve it from that path.
RUN npm run build

# ---------- stage 2: compile + stage the sidecar, bundling the UI ----------
FROM eclipse-temurin:25-jdk-jammy AS build

ARG SBT_VERSION=1.10.7
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates \
 && curl -fsSL "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz" | tar -xz -C /opt \
 && rm -rf /var/lib/apt/lists/*
ENV PATH="/opt/sbt/bin:${PATH}"

WORKDIR /build

# Resolve dependencies first so they're cached when only sources change.
COPY build.sbt ./
COPY project/build.properties project/plugins.sbt ./project/
RUN sbt update

COPY src ./src
# Bundle the built UI so the sidecar serves it from the classpath (/web).
COPY --from=ui /ui/dist ./src/main/resources/web
RUN sbt stage

# ---------- stage 3: runtime ----------
FROM eclipse-temurin:25-jre-jammy AS runtime

WORKDIR /app
COPY --from=build /build/target/universal/stage/ ./

# Mock engine + /__induction control plane + UI all share this single port.
ENV INDUCTION_MOCK_PORT=8080
# Recorded requests are persisted here (H2). Mount a volume at /data to keep them.
ENV INDUCTION_DB_PATH=/data/requests
EXPOSE 8080

# Run as an unprivileged user; /data must be writable by it.
RUN useradd --system --uid 1001 induction \
 && mkdir -p /data \
 && chown -R induction:induction /app /data
USER induction
VOLUME ["/data"]

# Liveness: a TCP connect to the shared port (no curl in the JRE image).
HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=3 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/${INDUCTION_MOCK_PORT:-8080}' || exit 1

ENTRYPOINT ["/app/bin/test-induction-api"]
