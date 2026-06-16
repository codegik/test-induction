const METHOD_COLOR: Record<string, string> = {
  GET: "bg-emerald-500/15 text-emerald-300 ring-emerald-500/30",
  POST: "bg-indigo-500/15 text-indigo-300 ring-indigo-500/30",
  PUT: "bg-amber-500/15 text-amber-300 ring-amber-500/30",
  DELETE: "bg-rose-500/15 text-rose-300 ring-rose-500/30",
  PATCH: "bg-fuchsia-500/15 text-fuchsia-300 ring-fuchsia-500/30",
};

export const methodColor = (m: string): string =>
  METHOD_COLOR[(m || "").toUpperCase()] ?? "bg-slate-700/40 text-slate-300 ring-slate-600/40";

/** Tailwind classes for an HTTP status badge. */
export const statusColor = (status: number): string => {
  if (status >= 500) return "bg-rose-500/15 text-rose-300 ring-rose-500/30";
  if (status >= 400) return "bg-amber-500/15 text-amber-300 ring-amber-500/30";
  if (status >= 200 && status < 300) return "bg-emerald-500/15 text-emerald-300 ring-emerald-500/30";
  return "bg-slate-700/40 text-slate-300 ring-slate-600/40";
};
