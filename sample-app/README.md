# sample-app

A Java 25 + Spring Boot (Maven) service that calls an external **payment** API,
demonstrating the `../test-induction` sidecar.

It exposes `POST /pay`, which calls the payment API at `payment.base-url`. When
that base URL points at the sidecar and the request carries an
`x-induction-test-profile` header, the sidecar serves the behavior registered
for that profile and this app's `caller` (`payment-service`).

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
  base-url: http://localhost:8080   # the sidecar mock-engine port (use the real URL for zero-impact runs)
  caller: payment-service           # sent as x-induction-test-caller
  connect-timeout-ms: 2000
  read-timeout-ms: 4000
```

**Zero impact when disabled:** point `payment.base-url` at the real payment
service and send no induction header — the app behaves exactly as in production.

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
