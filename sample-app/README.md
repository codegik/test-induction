# sample-app

A Java 25 + Spring Boot (Maven) service that calls an external **payment** API,
demonstrating the `../test-induction` sidecar.

It exposes `POST /pay`, which calls the **real** payment API at `payment.base-url`
— the same URL in every environment. When the inbound `/pay` request carries an
`x-induction-test-profile` header, the app transparently **proxies** that
outbound call through the sidecar (which mocks it); with no header, the call goes
straight to the real service. The app's business code never changes URLs and
never knows whether a given call was mocked.

The app classifies what came back so each induced fault is visible:

| Induced behavior            | `/pay` outcome                | HTTP |
|-----------------------------|-------------------------------|------|
| Normal response             | `SUCCESS`                     | 200  |
| HTTP 5xx                     | `UPSTREAM_ERROR`              | 502  |
| Slow / read timeout         | `TIMEOUT`                     | 504  |
| Connection reset by peer    | `CONNECTION_RESET`            | 502  |
| Malformed JSON              | `MALFORMED_OR_DECODE_ERROR`   | 502  |

## Configuration (`application.yml`)

```yaml
server:
  port: 9090
payment:
  base-url: http://localhost:9099   # the REAL payment service URL (unchanged across envs)
  connect-timeout-ms: 2000
  read-timeout-ms: 4000
induction:
  sidecar-host: localhost           # where the sidecar listens; calls are proxied
  sidecar-port: 8080                # here ONLY when an x-induction-test-profile is present
  caller: payment-service           # sent as x-induction-test-caller
```

**Zero impact when disabled:** with no induction header the app calls
`payment.base-url` directly — the sidecar is never in the path, so the app
behaves exactly as in production. (In the local demo that URL has nothing
listening, so a header-less call fails fast, which is itself the proof the
sidecar was bypassed.)

How it routes (see `InductionRoutingRequestFactory`): an inbound interceptor
copies the profile header into a request-scoped context; the shared `RestClient`
then picks a sidecar-proxying request factory when a profile is active, or a
direct one otherwise. Any future external-service client built the same way gets
this for free.

## Run

```bash
mvn spring-boot:run
```

## Demo

With the sidecar running (`cd ../test-induction && sbt run`) and behaviors
registered, run `../demo.sh`, or manually:

```bash
curl -s -X POST http://localhost:9090/pay \
  -H 'Content-Type: application/json' \
  -H 'x-induction-test-profile: payment-timeout' \
  -d '{"amount":42.00,"currency":"USD"}'
# -> {"outcome":"TIMEOUT","error":"Read timed out"}
```
