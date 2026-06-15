# test-induction (sidecar)

A test-induction **sidecar**, written in Scala 3 (sbt) with [WireMock](https://wiremock.org/)
as the mock engine under the hood. It lets you inject failures into the external
services an application depends on — timeouts, HTTP errors, connection resets,
slow and malformed responses — **at runtime, over REST, without restarting**.

It addresses the problems in the repository-root `README.md`:

| Requirement        | How it's met                                                                 |
|--------------------|------------------------------------------------------------------------------|
| Isolation          | Runs as a standalone process; the app talks to it over HTTP only.            |
| No dependencies    | Single JVM process; no external service required.                            |
| No restart         | Behaviors are registered/removed via the control API while running.          |
| Fine-grained       | Behaviors are keyed by **profile** and **caller** (client type).            |
| Thread safe        | Registrations are held in a `ConcurrentHashMap`.                             |
| Zero impact        | When a caller sends no induction headers / points at the real URL, nothing fires. |
| Testable           | Any WireMock fault/response can be induced per service.                      |

## How it works

A single HTTP listener (default `:8080`) serves two planes:

- **Mock engine** (WireMock) — the application under test points its
  external-service base URL here during testing.
- **Control plane** — multiplexed onto the same port under the reserved
  `/__induction` namespace (a WireMock request filter), used to register and
  toggle behaviors. The reserved prefix mirrors WireMock's own `/__admin`, so it
  can never shadow a stub you register.

You register a behavior as a **WireMock stub mapping** (we wrap WireMock's own
format rather than reinventing it), wrapped in an envelope that names the
`profile` and `caller`:

```json
{
  "profile": "payment-timeout",
  "caller":  "payment-service",
  "mapping": {
    "request":  { "method": "POST", "urlPath": "/payments" },
    "response": { "status": 200, "fixedDelayMilliseconds": 8000,
                  "jsonBody": { "paymentId": "slow", "status": "CONFIRMED" } }
  }
}
```

The sidecar injects header matchers for `x-induction-test-profile` and
`x-induction-test-caller` into that mapping's request pattern. At request time
the caller sends those two headers; WireMock matches and serves the behavior.
The same engine therefore serves different behaviors per profile and per caller.

## Control API

All control endpoints live under `/__induction` on the mock engine port.

| Method & path                            | Body                          | Purpose                                   |
|------------------------------------------|-------------------------------|-------------------------------------------|
| `POST /__induction/register`             | `{ profile, caller, mapping }`| Register a behavior. Returns the stub id. |
| `DELETE /__induction/{profile}/{caller}` | —                             | Remove all behaviors for that pair.       |
| `POST /__induction/reset`                | —                             | Remove every registered behavior.         |
| `GET /__induction/status`                | —                             | List what's registered.                   |
| `GET /__induction/health`                | —                             | Liveness check.                           |

## Run

```bash
sbt run
# Port is configurable:
#   INDUCTION_MOCK_PORT  (default 8080)  — serves both the mock engine and /__induction
```

## Example fault recipes (the `mapping` value)

```jsonc
// HTTP 500
{ "request": { "method": "POST", "urlPath": "/payments" },
  "response": { "status": 500, "body": "upstream boom" } }

// Slow response / read timeout (delay longer than the client's read timeout)
{ "request": { "method": "POST", "urlPath": "/payments" },
  "response": { "status": 200, "fixedDelayMilliseconds": 8000 } }

// Connection reset by peer
{ "request": { "method": "POST", "urlPath": "/payments" },
  "response": { "fault": "CONNECTION_RESET_BY_PEER" } }

// Malformed JSON
{ "request": { "method": "POST", "urlPath": "/payments" },
  "response": { "status": 200, "headers": { "Content-Type": "application/json" },
                "body": "{ this is : not valid json" } }
```

See `../sample-app` for a Spring Boot service that drives all of these.
