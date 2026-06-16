import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// In dev, the browser only talks to this server; /api is proxied to the sidecar
// control plane (stripping the /api prefix), so there is no CORS and the API URL
// is never baked into the page. Mirrors what server.mjs does in production.
const apiBase = (process.env.INDUCTION_API_BASEURL ?? "http://localhost:8080").replace(/\/+$/, "");
const port = Number(process.env.UI_PORT ?? 8090);

export default defineConfig({
  plugins: [react()],
  server: {
    port,
    proxy: {
      "/api": {
        target: apiBase,
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, ""),
      },
    },
  },
});
