import { type FormEvent, useState } from "react";
import { Badge, DataTable, ErrorBox, Section } from "../components/ui";
import { adminFetch } from "../lib/api";
import { CATEGORY_LABEL, dateTime } from "../lib/format";
import { href } from "../lib/hooks";

type Result = {
  users: { id: string; nickname: string; status: string; created_at: string }[];
  properties: { id: string; category: string; subtype: string | null; address_label: string | null; eligibility: string }[];
};

export function Search() {
  const [q, setQ] = useState("");
  const [result, setResult] = useState<Result | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (q.trim().length < 2) return;
    setBusy(true);
    setError(null);
    try {
      setResult(await adminFetch<Result>("/v1/search", { query: { q: q.trim() } }));
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <h1>Buscar</h1>
      <form className="row" onSubmit={onSubmit}>
        <input className="grow" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Apelido, id de usuário, id de imóvel ou endereço" minLength={2} />
        <button type="submit" className="primary" disabled={busy}>Buscar</button>
      </form>
      {error != null && <ErrorBox error={error} />}
      {result && (
        <div className="grid">
          <Section title={`Jogadores (${result.users.length})`}>
            <DataTable rows={result.users} columns={[
              { key: "nickname", label: "Apelido", render: (u) => <a href={href("users", u.id)}>{u.nickname}</a> },
              { key: "status", label: "Status", render: (u) => <Badge value={u.status} /> },
              { key: "created_at", label: "Criado", render: (u) => dateTime(u.created_at) },
            ]} />
          </Section>
          <Section title={`Imóveis (${result.properties.length})`}>
            <DataTable rows={result.properties} columns={[
              { key: "address_label", label: "Imóvel", render: (p) => <a href={href("properties", p.id)}>{p.address_label ?? p.id}</a> },
              { key: "category", label: "Categoria", render: (p) => CATEGORY_LABEL[p.category] ?? p.category },
              { key: "eligibility", label: "Elegibilidade", render: (p) => <Badge value={p.eligibility} /> },
            ]} />
          </Section>
        </div>
      )}
    </>
  );
}
