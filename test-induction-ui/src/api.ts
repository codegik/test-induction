import type { BehaviorSpec, Group, RequestEntry } from "./types";

// All calls go to this server's /api, reverse-proxied to the sidecar control
// plane — the browser never makes a cross-origin request.
const BASE = "/api/__induction";

export interface ApiResult<T = unknown> {
  ok: boolean;
  status: number;
  json: T | null;
  text: string;
}

async function call<T = unknown>(method: string, path: string, body?: unknown): Promise<ApiResult<T>> {
  const headers: Record<string, string> = {};
  let payload: string | undefined;
  if (body !== undefined) {
    headers["Content-Type"] = "application/json";
    payload = typeof body === "string" ? body : JSON.stringify(body);
  }
  const res = await fetch(BASE + path, { method, headers, body: payload });
  const text = await res.text();
  let json: T | null = null;
  try {
    json = text ? (JSON.parse(text) as T) : null;
  } catch {
    /* leave as text */
  }
  return { ok: res.ok, status: res.status, json, text };
}

interface Envelope {
  profile: string;
  caller: string;
  behaviors: BehaviorSpec[];
}

export const getHealth = () => call("GET", "/health");
export const getStatus = () => call<Group[]>("GET", "/status");
export const registerMock = (body: Envelope) => call("POST", "/register", body);
export const updateMock = (body: Envelope) => call("PUT", "/update", body);
export const deleteProfile = (profile: string, caller: string) =>
  call("DELETE", `/${encodeURIComponent(profile)}/${encodeURIComponent(caller)}`);
export const resetAll = () => call("POST", "/reset");
export const getRequests = () => call<RequestEntry[]>("GET", "/requests");
export const clearRequests = () => call("DELETE", "/requests");

export const errorOf = (r: ApiResult): string => {
  const j = r.json as { error?: string } | null;
  return (j && j.error) || r.text || `HTTP ${r.status}`;
};
