# syntax=docker/dockerfile:1

# ---------- build stage: Maven + JDK 25 ----------
FROM maven:3.9-eclipse-temurin-25 AS build

WORKDIR /build

# Cache dependencies first so they're reused when only sources change.
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline || true

# Build the Spring Boot executable jar.
COPY src ./src
RUN mvn -B -q -DskipTests package

# ---------- runtime stage: slim JRE 25, non-root ----------
FROM eclipse-temurin:25-jre-jammy AS runtime

WORKDIR /app
COPY --from=build /build/target/sample-app-*.jar app.jar

ENV SERVER_PORT=9090
EXPOSE 9090

RUN useradd --system --uid 1001 app && chown -R app:app /app
USER app

# Liveness: a TCP connect to the HTTP port (no curl in the JRE image).
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/${SERVER_PORT:-9090}' || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
