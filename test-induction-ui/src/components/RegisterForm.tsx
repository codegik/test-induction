import { useEffect, useState, type FormEvent } from "react";
import { Check, Pencil, PlusCircle } from "lucide-react";
import { errorOf, registerMock, updateMock } from "../api";
import type { Behavior, BehaviorSpec, Group } from "../types";

const DEFAULT_RESPONSE = `{
  "status": 200,
  "headers": { "Content-Type": "application/json" },
  "jsonBody": { "status": "CONFIRMED" }
}`;

const RECIPES: { label: string; json: string }[] = [
  { label: "HTTP 500", json: '{"status":500,"body":"upstream boom"}' },
  { label: "Slow (8s)", json: '{"status":200,"fixedDelayMilliseconds":8000}' },
  { label: "Connection reset", json: '{"fault":"CONNECTION_RESET_BY_PEER"}' },
  { label: "Malformed JSON", json: '{"status":200,"headers":{"Content-Type":"application/json"},"body":"{ not valid json"}' },
];

interface Props {
  editTarget: { group: Group; behavior: Behavior } | null;
  onCancelEdit: () => void;
  onSaved: () => void;
}

export function RegisterForm({ editTarget, onCancelEdit, onSaved }: Props) {
  const editing = editTarget !== null;
  const [profile, setProfile] = useState("");
  const [caller, setCaller] = useState("payment-service");
  const [name, setName] = useState("");
  const [baseUrl, setBaseUrl] = useState("");
  const [method, setMethod] = useState("POST");
  const [path, setPath] = useState("");
  const [pathIsPattern, setPathIsPattern] = useState(false);
  const [response, setResponse] = useState(DEFAULT_RESPONSE);
  const [msg, setMsg] = useState<{ text: string; ok: boolean } | null>(null);

  useEffect(() => {
    if (!editTarget) return;
    const { group, behavior } = editTarget;
    setProfile(group.profile);
    setCaller(group.caller);
    setName(behavior.name ?? "");
    setBaseUrl(behavior.baseUrl);
    setMethod(behavior.method);
    const isPattern = behavior.pathPattern != null;
    setPathIsPattern(isPattern);
    setPath(isPattern ? (behavior.pathPattern ?? "") : (behavior.path ?? ""));
    setResponse(JSON.stringify(behavior.response, null, 2));
    setMsg({ text: "Editing — profile/caller/match are fixed; change name or response. Cancel to add a new one.", ok: true });
  }, [editTarget]);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    let parsed: unknown;
    try {
      parsed = JSON.parse(response);
    } catch (err) {
      setMsg({ text: "Response is not valid JSON: " + (err as Error).message, ok: false });
      return;
    }
    const match: BehaviorSpec["match"] = { baseUrl: baseUrl.trim(), method };
    if (pathIsPattern) match.pathPattern = path.trim();
    else match.path = path.trim();
    const behavior: BehaviorSpec = { match, response: parsed };
    if (name.trim()) behavior.name = name.trim();

    if (editing) {
      const r = await updateMock({ profile, caller, behaviors: [behavior] });
      if (r.ok) {
        setMsg({ text: "Saved ✓", ok: true });
        onCancelEdit();
        onSaved();
      } else {
        setMsg({ text: "Failed: " + errorOf(r), ok: false });
      }
      return;
    }
    const r = await registerMock({ profile: profile.trim(), caller: caller.trim(), behaviors: [behavior] });
    if (r.ok) {
      setMsg({ text: "Registered ✓", ok: true });
      onSaved();
    } else {
      setMsg({ text: "Failed: " + errorOf(r), ok: false });
    }
  }

  return (
    <div className="card sticky top-24 p-5 sm:p-6">
      <div className="mb-1 flex items-center gap-2">
        {editing ? <Pencil className="h-5 w-5 text-indigo-400" /> : <PlusCircle className="h-5 w-5 text-indigo-400" />}
        <h2 className="text-lg font-semibold text-white">{editing ? "Edit behavior" : "Register a behavior"}</h2>
      </div>
      <p className="mb-5 text-sm text-slate-400">
        {editing
          ? "Update the response for an existing mock."
          : "A mock is unique per profile/caller/match. Registering a duplicate match is rejected — edit it instead."}
      </p>

      <form className="space-y-4" onSubmit={onSubmit}>
        <div className="grid grid-cols-2 gap-3">
          <div>
            <label className="label" htmlFor="f-profile">Profile</label>
            <input id="f-profile" className="input" value={profile} disabled={editing}
              onChange={(e) => setProfile(e.target.value)} placeholder="payment-confirmed" required />
          </div>
          <div>
            <label className="label" htmlFor="f-caller">Caller</label>
            <input id="f-caller" className="input" value={caller} disabled={editing}
              onChange={(e) => setCaller(e.target.value)} required />
          </div>
        </div>

        <div>
          <label className="label" htmlFor="f-name">Name <span className="normal-case text-slate-500">(optional)</span></label>
          <input id="f-name" className="input" value={name} onChange={(e) => setName(e.target.value)} placeholder="payments" />
        </div>

        <div className="grid grid-cols-3 gap-3">
          <div className="col-span-2">
            <label className="label" htmlFor="f-baseUrl">Target base URL</label>
            <input id="f-baseUrl" className="input" value={baseUrl} disabled={editing}
              onChange={(e) => setBaseUrl(e.target.value)} placeholder="http://api.payments.com" required />
          </div>
          <div>
            <label className="label" htmlFor="f-method">Method</label>
            <select id="f-method" className="input" value={method} disabled={editing} onChange={(e) => setMethod(e.target.value)}>
              <option>POST</option><option>GET</option><option>PUT</option><option>DELETE</option><option>PATCH</option>
            </select>
          </div>
        </div>

        <div>
          <label className="label" htmlFor="f-path">Path</label>
          <input id="f-path" className="input" value={path} disabled={editing}
            onChange={(e) => setPath(e.target.value)} placeholder="/v1/charges" required />
          <label className="mt-2 flex items-center gap-2 text-xs text-slate-400">
            <input type="checkbox" className="h-4 w-4 rounded border-slate-600 bg-slate-900 text-indigo-500 focus:ring-indigo-500"
              checked={pathIsPattern} disabled={editing} onChange={(e) => setPathIsPattern(e.target.checked)} />
            path is a regex (<code className="rounded bg-slate-800 px-1 py-0.5 text-slate-300">pathPattern</code>)
          </label>
        </div>

        <div>
          <label className="label" htmlFor="f-response">Response <span className="normal-case text-slate-500">(verbatim WireMock response JSON)</span></label>
          <textarea id="f-response" className="input font-mono text-xs leading-relaxed" rows={9} spellCheck={false}
            value={response} onChange={(e) => setResponse(e.target.value)} />
        </div>

        <div>
          <p className="mb-2 text-xs font-medium uppercase tracking-wide text-slate-500">Fault recipes</p>
          <div className="flex flex-wrap gap-2">
            {RECIPES.map((r) => (
              <button key={r.label} type="button"
                className="rounded-full border border-slate-700 bg-slate-800/60 px-3 py-1 text-xs text-slate-300 transition hover:border-indigo-500 hover:text-white"
                onClick={() => setResponse(JSON.stringify(JSON.parse(r.json), null, 2))}>
                {r.label}
              </button>
            ))}
          </div>
        </div>

        <div className="flex items-center justify-between gap-3 pt-1">
          <span className={"text-sm " + (msg ? (msg.ok ? "text-emerald-400" : "text-rose-400") : "")}>{msg?.text}</span>
          <div className="flex items-center gap-2">
            {editing && (
              <button type="button" className="btn btn-ghost" onClick={() => { onCancelEdit(); setMsg(null); }}>Cancel</button>
            )}
            <button type="submit" className="btn btn-primary">
              <Check className="h-4 w-4" />
              {editing ? "Save changes" : "Register"}
            </button>
          </div>
        </div>
      </form>
    </div>
  );
}
