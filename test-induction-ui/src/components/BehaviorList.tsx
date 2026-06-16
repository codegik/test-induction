import { ListChecks, Pencil, Trash2 } from "lucide-react";
import { behaviorUrl, type Behavior, type Group } from "../types";

const METHOD_COLOR: Record<string, string> = {
  GET: "bg-emerald-500/15 text-emerald-300 ring-emerald-500/30",
  POST: "bg-indigo-500/15 text-indigo-300 ring-indigo-500/30",
  PUT: "bg-amber-500/15 text-amber-300 ring-amber-500/30",
  DELETE: "bg-rose-500/15 text-rose-300 ring-rose-500/30",
  PATCH: "bg-fuchsia-500/15 text-fuchsia-300 ring-fuchsia-500/30",
};
const methodColor = (m: string) =>
  METHOD_COLOR[(m || "").toUpperCase()] ?? "bg-slate-700/40 text-slate-300 ring-slate-600/40";

interface Props {
  groups: Group[];
  onEdit: (group: Group, behavior: Behavior) => void;
  onDelete: (profile: string, caller: string) => void;
}

export function BehaviorList({ groups, onEdit, onDelete }: Props) {
  return (
    <div className="card p-5 sm:p-6">
      <div className="mb-1 flex items-center gap-2">
        <ListChecks className="h-5 w-5 text-indigo-400" />
        <h2 className="text-lg font-semibold text-white">Registered behaviors</h2>
      </div>
      <p className="mb-5 text-sm text-slate-400">
        Grouped by <code className="rounded bg-slate-800 px-1 py-0.5 text-slate-300">profile</code> /{" "}
        <code className="rounded bg-slate-800 px-1 py-0.5 text-slate-300">caller</code>. A request to the sidecar
        matches a behavior on its full base-url + path + the induction headers.
      </p>

      {groups.length === 0 ? (
        <div className="rounded-xl border border-dashed border-slate-800 px-4 py-10 text-center text-sm text-slate-500">
          No behaviors registered yet.
        </div>
      ) : (
        <div className="space-y-3">
          {groups.map((g) => (
            <div key={g.profile + "::" + g.caller} className="overflow-hidden rounded-xl border border-slate-800 bg-slate-950/40">
              <div className="flex items-center justify-between gap-3 border-b border-slate-800 px-4 py-3">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="rounded-md bg-indigo-500/15 px-2 py-0.5 text-sm font-semibold text-indigo-300">{g.profile}</span>
                  <span className="text-xs text-slate-500">caller: {g.caller}</span>
                </div>
                <button
                  className="btn btn-danger !px-2.5 !py-1.5"
                  title="Delete profile"
                  onClick={() => onDelete(g.profile, g.caller)}
                >
                  <Trash2 className="h-4 w-4" />
                </button>
              </div>

              <div className="divide-y divide-slate-800/60">
                {g.behaviors.map((b) => (
                  <div key={b.stubId} className="px-4 py-2.5">
                    <div className="flex items-center gap-3">
                      <span className={"shrink-0 rounded-md px-2 py-0.5 text-xs font-bold ring-1 ring-inset " + methodColor(b.method)}>
                        {b.method}
                      </span>
                      <span className="min-w-0 flex-1 truncate font-mono text-sm text-slate-200" title={behaviorUrl(b)}>
                        {behaviorUrl(b)}
                      </span>
                      {b.name && <span className="shrink-0 text-xs text-slate-500">{b.name}</span>}
                      <button className="btn btn-ghost !px-2 !py-1 shrink-0" title="Edit behavior" onClick={() => onEdit(g, b)}>
                        <Pencil className="h-4 w-4" />
                      </button>
                    </div>
                    <details className="mt-2">
                      <summary className="cursor-pointer select-none text-xs text-slate-400 hover:text-slate-200">response</summary>
                      <pre className="mt-2 overflow-x-auto rounded-lg bg-slate-950/70 p-3 text-xs leading-relaxed text-slate-300 ring-1 ring-inset ring-slate-800">
                        <code>{JSON.stringify(b.response, null, 2)}</code>
                      </pre>
                    </details>
                  </div>
                ))}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
