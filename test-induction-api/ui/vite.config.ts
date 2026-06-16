import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// The UI is served at the root (both in dev and from the bundled sidecar image),
// and the control plane lives at /__induction on the same origin. In dev, Vite
// proxies /__induction to the running sidecar.
const apiBase = (process.env.INDUCTION_API_BASEURL ?? "http://localhost:8080").replace(/\/+$/, "");
const port = Number(process.env.UI_PORT ?? 8090);

export default defineConfig({
  base: "/",
  plugins: [react()],
  server: {
    port,
    proxy: {
      "/__induction": { target: apiBase, changeOrigin: true },
    },
  },
});
