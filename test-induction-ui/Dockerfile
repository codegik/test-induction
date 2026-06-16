# syntax=docker/dockerfile:1

# ---------- build stage: install deps + build the SPA ----------
FROM node:22-bookworm-slim AS build
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm ci || npm install
COPY . .
RUN npm run build

# ---------- runtime stage: slim Node, non-root, serve dist + proxy /api ----------
FROM node:22-bookworm-slim AS runtime
WORKDIR /app
ENV NODE_ENV=production \
    UI_PORT=8090 \
    INDUCTION_API_BASEURL=http://localhost:8080

COPY --from=build /app/dist ./dist
COPY server.mjs ./
EXPOSE 8090

RUN useradd --system --uid 1001 ui && chown -R ui:ui /app
USER ui

HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=3 \
  CMD node -e "fetch('http://127.0.0.1:'+(process.env.UI_PORT||8090)+'/').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"

CMD ["node", "server.mjs"]
