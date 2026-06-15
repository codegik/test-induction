# test-induction

## Problem statement

Any application that is integrating with many external services has challenges during the tests phase.

It could be integration tests, regression tests, stress tests, etc. Will have the csame problem.

### External dependency

There will be scenario where you cannot test your application because the external service is down. And you cannot controll what is going on in the other service.

### Impossible test failure scenario

Sometimes we want to test corner cases and failure cases like:

- Connection timeout.
- Random intermittent.
- Slow responses.
- Connection reset by peer.
- Malformed Json responses.

### Integration tests are expensive and fragile

Running integration tests agains real staging services is challenge.

- Tests are slow.
- Tests are flaky.
- Tests are expensive.
- Tests shared environments.
- Can't run tests in parallel.

### Local development requires all services Running

Engineer wants to work on a feature running locally. The problem start when this feature is calling external services.

The engineer needs:

- All services running locally.
- Valid credentials for each service.
- Network access to all services.
- Data in the correct state accross all services.

### Can't reproduce production issues

The feature `payment` is failing because is taking more than 30s to finish.

- So you can't reproduce locally.
- Can't chance the external service behavior.
- Can't inject delays into real external service.

### Stress Testing Hits Real Services

Need to stress test your service with 1000 concurrent `payments`.

Problem:

- Can't hammer real service (DDoS)
- Real services rate-limit your requests
- Real services cost money (API usage)
- Real services have test data limits

## Solution

Need a test induction strategy by following these requirements.

- Isolation: it must run as a sidecard, it doesn't impact the existing application. They will communicate with REST API only.
- No dependencies: there is no external service dependency.
- No restart required: toggle mocks via REST API.
- Fine-grained control: enable/disable per client type.
- Thread safe: use concurrent hash map.
- Zero impact: when disabled, uses original URLs.
- Testable: can test resilience, timeouts, errors per service.

## Repository layout

| Folder                | What it is                                                                                       |
|-----------------------|--------------------------------------------------------------------------------------------------|
| `test-induction-api/` | The sidecar — Scala 3 + sbt, wrapping WireMock. Registers/toggles induced behaviors over REST.   |
| `test-induction-ui/`  | A small web UI (Scala 3 + vanilla JS) to manage behaviors; proxies to the API control plane.     |
| `sample-app/`         | A Java 25 + Spring Boot (Maven) service calling an external payment API through the sidecar.      |

Each folder has its own README with details.

## Running demonstration

In three terminals:

```bash
./demo.sh
```

`demo.sh` registers per-profile behaviors (success, HTTP 500, slow/timeout,
connection reset, malformed JSON), then calls the app's `POST /pay` with the
`x-induction-test-profile` header to trigger each one — and shows that with no
header, nothing is induced (zero impact).
