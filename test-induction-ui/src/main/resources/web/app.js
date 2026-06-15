"use strict";

// All calls go to this server's /api, which reverse-proxies to the API control
// plane. So the browser never makes a cross-origin request.
const API = "/api/__induction";

const $ = (sel) => document.querySelector(sel);
const el = (tag, attrs = {}, ...kids) => {
  const n = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === "class") n.className = v;
    else if (k === "text") n.textContent = v;
    else n.setAttribute(k, v);
  }
  for (const kid of kids) n.append(kid);
  return n;
};

// Refresh Lucide icons after we inject markup that contains <i data-lucide>.
const icons = () => { if (window.lucide) lucide.createIcons(); };

// Color-coded HTTP method badges — a recognizable touch from modern API tools.
const METHOD_COLOR = {
  GET: "bg-emerald-500/15 text-emerald-300 ring-emerald-500/30",
  POST: "bg-indigo-500/15 text-indigo-300 ring-indigo-500/30",
  PUT: "bg-amber-500/15 text-amber-300 ring-amber-500/30",
  DELETE: "bg-rose-500/15 text-rose-300 ring-rose-500/30",
  PATCH: "bg-fuchsia-500/15 text-fuchsia-300 ring-fuchsia-500/30",
};
const methodColor = (m) => METHOD_COLOR[(m || "").toUpperCase()] || "bg-slate-700/40 text-slate-300 ring-slate-600/40";

async function api(method, path, body) {
  const opts = { method, headers: {} };
  if (body !== undefined) {
    opts.headers["Content-Type"] = "application/json";
    opts.body = typeof body === "string" ? body : JSON.stringify(body);
  }
  const res = await fetch(API + path, opts);
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch (_) { /* leave as text */ }
  return { ok: res.ok, status: res.status, json, text };
}

// --- health pill ------------------------------------------------------------
async function checkHealth() {
  const pill = $("#apiState");
  const render = (dotClass, label, textClass) => {
    pill.className =
      "inline-flex items-center gap-2 rounded-full bg-slate-800/70 px-3 py-1.5 text-xs font-medium " + textClass;
    pill.replaceChildren(
      el("span", { class: "live-dot h-2 w-2 rounded-full " + dotClass }),
      document.createTextNode(label),
    );
  };
  try {
    const r = await api("GET", "/health");
    if (r.ok) { render("bg-emerald-400", "API: up", "text-emerald-300"); return; }
    throw new Error(r.status);
  } catch (_) {
    render("bg-rose-500", "API: unreachable", "text-rose-300");
  }
}

// --- status list ------------------------------------------------------------
async function loadStatus() {
  const box = $("#status");
  let r;
  try { r = await api("GET", "/status"); }
  catch (_) { box.replaceChildren(empty("Could not reach the API.")); return; }

  const groups = Array.isArray(r.json) ? r.json : [];
  if (groups.length === 0) {
    box.replaceChildren(empty("No behaviors registered yet."));
    return;
  }

  const frag = document.createDocumentFragment();
  for (const g of groups) {
    const head = el("div", { class: "flex items-center justify-between gap-3 border-b border-slate-800 px-4 py-3" },
      el("div", { class: "flex flex-wrap items-center gap-2" },
        el("span", { class: "rounded-md bg-indigo-500/15 px-2 py-0.5 text-sm font-semibold text-indigo-300", text: g.profile }),
        el("span", { class: "text-xs text-slate-500", text: "caller: " + g.caller })),
      el("button", {
        class: "btn btn-danger !px-2.5 !py-1.5 del",
        "data-profile": g.profile, "data-caller": g.caller, title: "Delete profile",
      }, el("i", { "data-lucide": "trash-2", class: "h-4 w-4" })),
    );

    const group = el("div", { class: "overflow-hidden rounded-xl border border-slate-800 bg-slate-950/40" }, head);
    const list = el("div", { class: "divide-y divide-slate-800/60" });
    for (const b of (g.behaviors || [])) {
      list.append(el("div", { class: "flex items-center gap-3 px-4 py-2.5" },
        el("span", { class: "shrink-0 rounded-md px-2 py-0.5 text-xs font-bold ring-1 ring-inset " + methodColor(b.method), text: b.method }),
        el("span", { class: "min-w-0 flex-1 truncate font-mono text-sm text-slate-200", title: b.baseUrl + b.path, text: b.baseUrl + b.path }),
        b.name ? el("span", { class: "shrink-0 text-xs text-slate-500", text: b.name }) : document.createTextNode(""),
      ));
    }
    group.append(list);
    frag.append(group);
  }
  box.className = "space-y-3";
  box.replaceChildren(frag);
  icons();

  box.querySelectorAll("button.del").forEach((btn) =>
    btn.addEventListener("click", () => deleteProfile(btn.dataset.profile, btn.dataset.caller)));
}

function empty(text) {
  return el("div", { class: "rounded-xl border border-dashed border-slate-800 px-4 py-10 text-center text-sm text-slate-500", text });
}

// --- actions ----------------------------------------------------------------
async function deleteProfile(profile, caller) {
  await api("DELETE", `/${encodeURIComponent(profile)}/${encodeURIComponent(caller)}`);
  await loadStatus();
}

async function resetAll() {
  if (!confirm("Remove ALL registered behaviors?")) return;
  await api("POST", "/reset");
  await loadStatus();
}

function setMsg(text, ok) {
  const m = $("#formMsg");
  m.textContent = text;
  m.className = "text-sm " + (ok ? "text-emerald-400" : "text-rose-400");
}

async function register(ev) {
  ev.preventDefault();
  const f = ev.target;
  const fd = new FormData(f);

  let response;
  try {
    response = JSON.parse(fd.get("response"));
  } catch (e) {
    setMsg("Response is not valid JSON: " + e.message, false);
    return;
  }

  const match = {
    baseUrl: (fd.get("baseUrl") || "").trim(),
    method: fd.get("method"),
  };
  const path = (fd.get("path") || "").trim();
  if (fd.get("pathIsPattern")) match.pathPattern = path; else match.path = path;

  const behavior = { match, response };
  const name = (fd.get("name") || "").trim();
  if (name) behavior.name = name;

  const payload = {
    profile: (fd.get("profile") || "").trim(),
    caller: (fd.get("caller") || "").trim(),
    behaviors: [behavior],
  };

  const r = await api("POST", "/register", payload);
  if (r.ok) {
    setMsg("Registered ✓", true);
    await loadStatus();
  } else {
    const err = (r.json && r.json.error) || r.text || ("HTTP " + r.status);
    setMsg("Failed: " + err, false);
  }
}

// --- wire up ----------------------------------------------------------------
function init() {
  icons();
  $("#regForm").addEventListener("submit", register);
  $("#refreshBtn").addEventListener("click", () => { loadStatus(); checkHealth(); });
  $("#resetBtn").addEventListener("click", resetAll);
  document.querySelectorAll(".chip").forEach((chip) =>
    chip.addEventListener("click", (e) => {
      e.preventDefault();
      $("textarea[name=response]").value = JSON.stringify(JSON.parse(chip.dataset.recipe), null, 2);
    }));

  checkHealth();
  loadStatus();
  setInterval(checkHealth, 5000);
}

document.addEventListener("DOMContentLoaded", init);
