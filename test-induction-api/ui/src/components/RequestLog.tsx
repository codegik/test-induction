import { useEffect, useMemo, useState } from "react";
import { Activity, RefreshCw, Search, Trash2 } from "lucide-react";
import { clearRequests, getRequests } from "../api";
import { methodColor, statusColor } from "../method";
import type { RequestEntry } from "../types";

const haystack = (e: RequestEntry) =>
  [e.method, e.url, e.profile, e.caller, String(e.status), e.requestBody, e.responseBody]
    .join(" ")
    .toLowerCase();

const time = (iso: string) => {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleTimeString();
};

export function RequestLog() {
  const [entries, setEntries] = useState<RequestEntry[]>([]);
  const [query, setQuery] = useState("");

  const load = async () => {
    const r = await getRequests();
    setEntries(Array.isArray(r.json) ? r.json : []);
  };

  useEffect(() => {
    load();
    // Poll for new requests, but not while the tab is in the background; refresh
    // immediately when the tab becomes visible again.
    const id = setInterval(() => {
      if (!document.hidden) load();
    }, 3000);
    const onVisible = () => {
      if (!document.hidden) load();
    };
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      clearInterval(id);
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, []);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return q ? entries.filter((e) => haystack(e).includes(q)) : entries;
  }, [entries, query]);

  const onClear = async () => {
    if (!confirm("Clear the recorded request log?")) return;
    await clearRequests();
    await load();
  };

  return (
    <div className="card p-5 sm:p-6">
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-2">
          <Activity className="h-5 w-5 text-indigo-400" />
          <h2 className="text-lg font-semibold text-white">Requests</h2>
          <span className="rounded-full bg-slate-800 px-2 py-0.5 text-xs text-slate-400">
            {filtered.length}{query && ` / ${entries.length}`}
          </span>
        </div>
        <div className="flex items-center gap-2">
          <div className="relative">
            <Search className="pointer-events-none absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-500" />
            <input
              className="input w-56 pl-8"
              placeholder="Search requests…"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
            />
          </div>
          <button className="btn btn-ghost !px-2.5" title="Refresh" onClick={load}>
            <RefreshCw className="h-4 w-4" />
          </button>
          <button className="btn btn-danger !px-2.5" title="Clear log" onClick={onClear}>
            <Trash2 className="h-4 w-4" />
          </button>
        </div>
      </div>

      {filtered.length === 0 ? (
        <div className="rounded-xl border border-dashed border-slate-800 px-4 py-10 text-center text-sm text-slate-500">
          {entries.length === 0 ? "No requests recorded yet." : "No requests match your search."}
        </div>
      ) : (
        <div className="space-y-2">
          {filtered.map((e) => (
            <details key={e.id} className="overflow-hidden rounded-xl border border-slate-800 bg-slate-950/40">
              <summary className="flex cursor-pointer select-none items-center gap-3 px-4 py-2.5 hover:bg-slate-900/40">
                <span className={"shrink-0 rounded-md px-2 py-0.5 text-xs font-bold ring-1 ring-inset " + statusColor(e.status)}>
                  {e.status || "—"}
                </span>
                <span className={"shrink-0 rounded-md px-2 py-0.5 text-xs font-bold ring-1 ring-inset " + methodColor(e.method)}>
                  {e.method}
                </span>
                <span className="min-w-0 flex-1 truncate font-mono text-sm text-slate-200" title={e.url}>{e.url}</span>
                {!e.matched && <span className="shrink-0 rounded bg-amber-500/15 px-1.5 py-0.5 text-[10px] font-medium text-amber-300">no match</span>}
                <span className="hidden shrink-0 text-xs text-slate-500 sm:inline">{e.profile}</span>
                <span className="shrink-0 text-xs text-slate-500">{time(e.loggedAt)}</span>
              </summary>
              <div className="space-y-3 border-t border-slate-800 px-4 py-3">
                <div className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-slate-400">
                  <span>profile: <span className="text-slate-200">{e.profile}</span></span>
                  <span>caller: <span className="text-slate-200">{e.caller}</span></span>
                </div>
                <Body label="Request body" value={e.requestBody} />
                <Body label="Response body" value={e.responseBody} />
              </div>
            </details>
          ))}
        </div>
      )}
    </div>
  );
}

function Body({ label, value }: { label: string; value: string }) {
  if (!value) return null;
  return (
    <div>
      <p className="mb-1 text-xs font-medium uppercase tracking-wide text-slate-500">{label}</p>
      <pre className="overflow-x-auto rounded-lg bg-slate-950/70 p-3 text-xs leading-relaxed text-slate-300 ring-1 ring-inset ring-slate-800">
        <code>{value}</code>
      </pre>
    </div>
  );
}
