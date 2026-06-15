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

COPY build.sbt ./
COPY project/build.properties project/plugins.sbt ./project/
RUN sbt update

COPY src ./src
RUN sbt stage

# ---------- runtime stage: slim JRE 25, non-root ----------
FROM eclipse-temurin:25-jre-jammy AS runtime

WORKDIR /app
COPY --from=build /build/target/universal/stage/ ./

# UI port; the API base URL is injected at runtime (compose sets it to the sidecar).
ENV UI_PORT=8090
ENV INDUCTION_API_BASEURL=http://localhost:8080
EXPOSE 8090

RUN useradd --system --uid 1001 ui && chown -R ui:ui /app
USER ui

HEALTHCHECK --interval=15s --timeout=3s --start-period=20s --retries=3 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/${UI_PORT:-8090}' || exit 1

ENTRYPOINT ["/app/bin/test-induction-ui"]
