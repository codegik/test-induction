# test-induction-api/ui

The React + TypeScript (Vite) UI for managing the sidecar's mock behaviors and
viewing recorded requests. It lives inside the sidecar module because it ships
**bundled into the sidecar image** — in production the JVM sidecar serves this
app at the root (`/`) on its own port, and the app calls the control plane
same-origin at `/__induction/...` (no CORS, no separate server). This folder is
the source; the sidecar's Docker build runs `npm run build` and copies `dist/`
into the sidecar's resources.

## Dev

```bash
./run.sh            # Vite dev server on :8090 with hot reload
```

In dev the app is served at `/` and Vite proxies `/__induction` to a running
sidecar (`INDUCTION_API_BASEURL`, default `http://localhost:8080`). So start the
sidecar first (`../run.sh`, the parent module), then this.

| Env var                 | Default                 | Purpose                                  |
|-------------------------|-------------------------|------------------------------------------|
| `UI_PORT`               | `8090`                  | Vite dev server port.                    |
| `INDUCTION_API_BASEURL` | `http://localhost:8080` | Sidecar the dev proxy forwards to.       |

## Build

```bash
npm run build       # base /, outputs dist/ (bundled into the sidecar image)
```

## Stack

- React 19 + TypeScript, built with Vite
- Tailwind CSS (via CDN) + Inter, icons from `lucide-react`
