// Production server (used by the Docker image): serves the built SPA from ./dist
// and reverse-proxies everything under /api to the sidecar control plane, so the
// browser never makes a cross-origin request. Node built-ins only — no deps.
import http from "node:http";
import https from "node:https";
import { readFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import { extname, join, normalize } from "node:path";
import { fileURLToPath } from "node:url";

const PORT = Number(process.env.UI_PORT ?? 8090);
const API_BASE = (process.env.INDUCTION_API_BASEURL ?? "http://localhost:8080").replace(/\/+$/, "");
const DIST = fileURLToPath(new URL("./dist", import.meta.url));

const TYPES = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json",
  ".svg": "image/svg+xml",
  ".ico": "image/x-icon",
  ".woff2": "font/woff2",
  ".map": "application/json",
};

function proxy(req, res, url) {
  const target = new URL(API_BASE);
  const client = target.protocol === "https:" ? https : http;
  const options = {
    protocol: target.protocol,
    hostname: target.hostname,
    port: target.port || (target.protocol === "https:" ? 443 : 80),
    method: req.method,
    path: url.pathname.replace(/^\/api/, "") + url.search,
    headers: { ...req.headers, host: target.host },
  };
  const upstream = client.request(options, (r) => {
    res.writeHead(r.statusCode ?? 502, r.headers);
    r.pipe(res);
  });
  upstream.on("error", (e) => {
    res.writeHead(502, { "content-type": "application/json" });
    res.end(JSON.stringify({ error: `proxy to API failed: ${e.message}` }));
  });
  req.pipe(upstream);
}

async function serveStatic(pathname, res) {
  const rel = pathname === "/" ? "/index.html" : pathname;
  let file = normalize(join(DIST, rel));
  if (!file.startsWith(DIST)) {
    res.writeHead(403);
    res.end("forbidden");
    return;
  }
  if (!existsSync(file)) file = join(DIST, "index.html"); // SPA fallback
  try {
    const body = await readFile(file);
    res.writeHead(200, { "content-type": TYPES[extname(file)] ?? "application/octet-stream" });
    res.end(body);
  } catch {
    res.writeHead(404);
    res.end("not found");
  }
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url ?? "/", "http://localhost");
  if (url.pathname.startsWith("/api/")) proxy(req, res, url);
  else void serveStatic(url.pathname, res);
});

server.listen(PORT, () => {
  console.log(`[test-induction-ui] http://localhost:${PORT}  ->  proxying /api to ${API_BASE}`);
});
