# test-induction-ui

A React + TypeScript (Vite) single-page app for managing the sidecar's mock
behaviors, served by Node.

The browser only ever talks to this server: everything under `/api` is
reverse-proxied to the sidecar control plane (`/__induction/...`), so there's no
CORS and the API URL is never baked into the page.

- **Dev** (`./run.sh` → `npm run dev`): Vite dev server with hot reload; the
  `/api` proxy is configured in `vite.config.ts`.
- **Prod** (Docker): `npm run build` produces `dist/`, served by `server.mjs`
  (Node built-ins only) which also proxies `/api`.

## Configuration

| Env var                 | Default                 | Purpose                                   |
|-------------------------|-------------------------|-------------------------------------------|
| `UI_PORT`               | `8090`                  | Port this server listens on.              |
| `INDUCTION_API_BASEURL` | `http://localhost:8080` | Sidecar control plane the UI proxies to.  |

## Run

```bash
./run.sh                      # dev server on :8090, proxying /api -> :8080
# or production-style:
npm install && npm run build && npm start
```

## Stack

- React 19 + TypeScript, built with Vite
- Tailwind CSS (via CDN) + Inter, icons from `lucide-react`
- `server.mjs`: dependency-free Node static server + `/api` reverse proxy
