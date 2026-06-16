import { useCallback, useEffect, useRef, useState } from "react";
import { Header } from "./components/Header";
import { BehaviorList } from "./components/BehaviorList";
import { RegisterForm } from "./components/RegisterForm";
import { deleteProfile, getHealth, getStatus, resetAll } from "./api";
import type { Behavior, Group, Health } from "./types";

export default function App() {
  const [groups, setGroups] = useState<Group[]>([]);
  const [health, setHealth] = useState<Health>("checking");
  const [editTarget, setEditTarget] = useState<{ group: Group; behavior: Behavior } | null>(null);

  // Read in the focus handler without making it an effect dependency.
  const editingRef = useRef(false);
  editingRef.current = editTarget !== null;

  const reload = useCallback(async () => {
    const r = await getStatus();
    setGroups(Array.isArray(r.json) ? r.json : []);
  }, []);

  const ping = useCallback(async () => {
    try {
      const r = await getHealth();
      setHealth(r.ok ? "up" : "down");
    } catch {
      setHealth("down");
    }
  }, []);

  useEffect(() => {
    ping();
    reload();
    const id = setInterval(ping, 5000);
    // Pick up changes made elsewhere (e.g. via curl) when returning to the tab.
    const onFocus = () => {
      if (!editingRef.current) reload();
    };
    window.addEventListener("focus", onFocus);
    return () => {
      clearInterval(id);
      window.removeEventListener("focus", onFocus);
    };
  }, [ping, reload]);

  const onReset = async () => {
    if (!confirm("Remove ALL registered behaviors?")) return;
    setEditTarget(null);
    await resetAll();
    await reload();
  };

  const onDelete = async (profile: string, caller: string) => {
    if (editTarget && editTarget.group.profile === profile && editTarget.group.caller === caller) {
      setEditTarget(null);
    }
    await deleteProfile(profile, caller);
    await reload();
  };

  return (
    <>
      <Header health={health} onRefresh={() => { reload(); ping(); }} onReset={onReset} />
      <main className="mx-auto grid max-w-6xl grid-cols-1 gap-6 px-4 py-8 sm:px-6 lg:grid-cols-5">
        <section className="lg:col-span-3">
          <BehaviorList groups={groups} onEdit={(group, behavior) => setEditTarget({ group, behavior })} onDelete={onDelete} />
        </section>
        <section className="lg:col-span-2">
          <RegisterForm editTarget={editTarget} onCancelEdit={() => setEditTarget(null)} onSaved={reload} />
        </section>
      </main>
    </>
  );
}
