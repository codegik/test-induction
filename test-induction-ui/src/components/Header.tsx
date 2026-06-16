import { FlaskConical, RefreshCw, Trash2 } from "lucide-react";
import type { Health } from "../types";

const BADGE: Record<Health, { dot: string; text: string; label: string }> = {
  checking: { dot: "bg-slate-500", text: "text-slate-300", label: "checking API…" },
  up: { dot: "bg-emerald-400", text: "text-emerald-300", label: "API: up" },
  down: { dot: "bg-rose-500", text: "text-rose-300", label: "API: unreachable" },
};

interface Props {
  health: Health;
  onRefresh: () => void;
  onReset: () => void;
}

export function Header({ health, onRefresh, onReset }: Props) {
  const badge = BADGE[health];
  return (
    <header className="sticky top-0 z-10 border-b border-slate-800/80 bg-slate-950/80 backdrop-blur">
      <div className="mx-auto flex h-16 max-w-6xl items-center justify-between px-4 sm:px-6">
        <div className="flex items-center gap-3">
          <span className="grid h-9 w-9 place-items-center rounded-xl bg-indigo-500/15 text-indigo-400 ring-1 ring-inset ring-indigo-500/30">
            <FlaskConical className="h-5 w-5" />
          </span>
          <div>
            <h1 className="text-base font-semibold leading-tight text-white">test-induction</h1>
            <p className="text-xs text-slate-400">mock manager</p>
          </div>
        </div>
        <div className="flex items-center gap-2 sm:gap-3">
          <span className={"inline-flex items-center gap-2 rounded-full bg-slate-800/70 px-3 py-1.5 text-xs font-medium " + badge.text}>
            <span className={"live-dot h-2 w-2 rounded-full " + badge.dot} />
            {badge.label}
          </span>
          <button onClick={onRefresh} className="btn btn-ghost">
            <RefreshCw className="h-4 w-4" />
            <span className="hidden sm:inline">Refresh</span>
          </button>
          <button onClick={onReset} className="btn btn-danger">
            <Trash2 className="h-4 w-4" />
            <span className="hidden sm:inline">Reset all</span>
          </button>
        </div>
      </div>
    </header>
  );
}
