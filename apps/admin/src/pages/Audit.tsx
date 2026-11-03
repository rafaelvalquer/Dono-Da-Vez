import { useEffect, useState } from "react";
import { DataTable, ErrorBox, JsonView, Loading } from "../components/ui";
import { adminFetch } from "../lib/api";
import { dateTime, shortId } from "../lib/format";
import { href } from "../lib/hooks";

type Entry = {
  id: number; actor_id: string; action: string; target_type: string; target_id: string | null; reason: string;
  before: unknown; after: unknown; operation_id: string | null; created_at: string;
};

function targetLink(e: Entry) {
  if (!e.target_id) return "—";
  if (e.target_type === "wallet" || e.target_type === "user" || e.target_type === "profile") return <a href={href("users", e.target_id)}>{shortId(e.target_id)}</a>;
  if (e.target_type === "property") return <a href={href("properties", e.target_id)}>{shortId(e.target_id)}</a>;
  return <code title={e.target_id}>{shortId(e.target_id)}</code>;
}

/** Trilha de auditoria (somente leitura, append-only no banco). */
export function Audit() {
  const [rows, setRows] = useState<Entry[]>([]);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(true);
  const [done, setDone] = useState(false);

  async function load(before?: number) {
    setLoading(true);
    setError(null);
    try {
      const page = await adminFetch<Entry[]>("/v1/audit", { query: { before } });
      setRows((r) => (before ? [...r, ...page] : page));
      setDone(page.length < 100);
    } catch (e) {
      setError(e);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void load(); }, []);

  return (
    <>
      <h1>Auditoria</h1>
      {error != null && <ErrorBox error={error} onRetry={() => void load(rows.at(-1)?.id)} />}
      <DataTable rows={rows} empty={loading ? "Carregando…" : "Sem registros."} columns={[
        { key: "created_at", label: "Quando", render: (e) => dateTime(e.created_at) },
        { key: "actor_id", label: "Operador", render: (e) => <a href={href("users", e.actor_id)}>{shortId(e.actor_id)}</a> },
        { key: "action", label: "Ação", render: (e) => <code>{e.action}</code> },
        { key: "target", label: "Alvo", render: (e) => <>{e.target_type} {targetLink(e)}</> },
        { key: "reason", label: "Motivo" },
        { key: "diff", label: "Antes / depois", render: (e) => <details><summary>ver</summary><JsonView value={{ before: e.before, after: e.after }} /></details> },
      ]} />
      {loading && rows.length > 0 && <Loading />}
      {!loading && !done && rows.length > 0 && <button type="button" onClick={() => void load(rows.at(-1)!.id)}>Carregar mais</button>}
    </>
  );
}
