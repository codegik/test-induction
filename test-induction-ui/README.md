# test-induction-ui

A small web UI to manage the [`../test-induction-api`](../test-induction-api)
sidecar's mock behaviors — list, register, delete and reset — without hand-writing
`curl` calls.

It is intentionally **segregated from the API** and runs on its own port. The
server (Scala 3, JDK built-in HTTP server — no framework) does two things:

- serves a single-page app (plain HTML/CSS/JS, no build step) on `/`;
- reverse-proxies everything under `/api` to the API control plane, so the
  browser only ever talks to this server (no CORS, no API URL in the page).

```
browser ──>  test-induction-ui  ──/api──>  test-induction-api  (/__induction/*)
              (UI_PORT, 8090)               (INDUCTION_API_BASEURL, :8080)
```

## Configuration (env)

| Variable                 | Default                  | Meaning                                  |
|--------------------------|--------------------------|------------------------------------------|
| `UI_PORT`                | `8090`                   | Port the UI listens on.                  |
| `INDUCTION_API_BASEURL`  | `http://localhost:8080`  | Base URL of the API (where `/__induction` lives). |

## Run

```bash
# with the API already running on :8080
sbt run
# then open http://localhost:8090
```

Or via Docker / the repo-root `docker-compose.yml` (the compose file points
`INDUCTION_API_BASEURL` at the `sidecar` service):

```bash
docker compose up -d --build
# open http://localhost:8090
```

## What it does

- **Registered behaviors** — live list (grouped by `profile` / `caller`) with a
  per-profile delete and a global reset.
- **Register a behavior** — form for `profile`, `caller`, target `baseUrl`,
  `method`, `path` (or regex `pathPattern`) and a verbatim WireMock `response`
  (with one-click fault recipes). Registering again for the same profile/caller
  appends another behavior.
