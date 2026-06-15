# test-induction-api (sidecar)

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

A **profile** is a named scenario containing a list of **behaviors** — one per
external service the app calls. Each behavior is keyed by the target's **full
base URL** plus its method and path, and carries a verbatim **WireMock response**
(we wrap WireMock's own response format rather than reinventing it):

```json
{
  "profile": "black-friday-meltdown",
  "caller":  "payment-service",
  "behaviors": [
    {
      "name":  "payments-slow",
      "match": { "baseUrl": "https://api.payments.com", "method": "POST", "path": "/v1/charges" },
      "response": { "status": 200, "fixedDelayMilliseconds": 8000,
                    "jsonBody": { "id": "ch_1", "status": "succeeded" } }
    },
    {
      "name":  "inventory-down",
      "match": { "baseUrl": "https://api.inventory.com", "method": "GET", "pathPattern": "/v2/stock/.*" },
      "response": { "status": 503, "body": "service unavailable" }
    }
  ]
}
```

For each behavior the sidecar expands `match.baseUrl` into `host` + `port`
matchers (the scheme is ignored, so `http`/`https` don't have to be
distinguished), turns `path`/`pathPattern` into a `urlPath`/`urlPathPattern`
matcher, and injects `x-induction-test-profile` and `x-induction-test-caller`
header matchers. The app calls its **real** service URLs and, when a profile is
active, proxies those calls through the sidecar (so the absolute target URL is on
the wire); the sidecar matches on base-url + path + profile + caller and serves
the behavior. Unmatched requests get a terminal **404** — the sidecar never
forwards to a real service.

## Control API

All control endpoints live under `/__induction` on the mock engine port.

| Method & path                            | Body                             | Purpose                                       |
|------------------------------------------|----------------------------------|-----------------------------------------------|
| `POST /__induction/register`             | `{ profile, caller, behaviors }` | Register a profile's behaviors. Returns stub ids. |
| `DELETE /__induction/{profile}/{caller}` | —                                | Remove all behaviors for that pair.           |
| `POST /__induction/reset`                | —                                | Remove every registered behavior.             |
| `GET /__induction/status`                | —                                | List what's registered (grouped by profile).  |
| `GET /__induction/health`                | —                                | Liveness check.                               |

## Run

```bash
sbt run
# Port is configurable:
#   INDUCTION_MOCK_PORT  (default 8080)  — serves both the mock engine and /__induction
```

## Example fault recipes (each behavior's `response` value)

```jsonc
// HTTP 500
{ "status": 500, "body": "upstream boom" }

// Slow response / read timeout (delay longer than the client's read timeout)
{ "status": 200, "fixedDelayMilliseconds": 8000 }

// Connection reset by peer
{ "fault": "CONNECTION_RESET_BY_PEER" }

// Malformed JSON
{ "status": 200, "headers": { "Content-Type": "application/json" },
  "body": "{ this is : not valid json" }
```

Drop any of these in as the `response` of a behavior whose `match` names the
target service. See `../sample-app` for a Spring Boot service that drives all of
these, and `../demo.sh` for ready-to-run registrations.
