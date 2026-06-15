# syntax=docker/dockerfile:1

# ---------- build stage: compile + stage a self-contained app layout ----------
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

# Compile and stage: produces target/universal/stage/{bin,lib}.
COPY src ./src
RUN sbt stage

# ---------- runtime stage: slim JRE, non-root ----------
FROM eclipse-temurin:25-jre-jammy AS runtime

WORKDIR /app
COPY --from=build /build/target/universal/stage/ ./

# The mock engine + /__induction control plane share this single port.
ENV INDUCTION_MOCK_PORT=8080
EXPOSE 8080

# Run as an unprivileged user.
RUN useradd --system --uid 1001 induction && chown -R induction:induction /app
USER induction

# Liveness: a TCP connect to the control/mock port (no curl in the JRE image).
HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=3 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/${INDUCTION_MOCK_PORT:-8080}' || exit 1

ENTRYPOINT ["/app/bin/test-induction"]
