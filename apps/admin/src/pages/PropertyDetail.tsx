import { useState } from "react";
import { MutationDialog } from "../components/MutationDialog";
import { Badge, DataTable, ErrorBox, KeyValues, Loading, Section } from "../components/ui";
import { adminFetch } from "../lib/api";
import { CATEGORY_LABEL, dateTime, money, shortId } from "../lib/format";
import { href, useLoad } from "../lib/hooks";
import { allowed, type Role } from "../lib/roles";

type Row = Record<string, unknown>;
type Detail = {
  property: Row & {
    id: string; eligibility: string; eligibility_reason: string | null; classification_status: string; category_id: number | null;
    subtype: string | null; address_label: string | null; interaction_quality: string; height_m: number; height_source: string;
    version: number; interaction_point: { coordinates: [number, number] } | null;
  };
  ownerships: { ownership_id: string; owner_id: string; owner_nickname: string; purchase_price: string; acquired_at: string;
                acquired_via: string; ended_at: string | null; end_reason: string | null; income_credited: string }[];
  trades: Row[];
  source_features: Row[];
  review_cases: Row[];
  changes: Row[];
};

const ELIGIBILITY = ["eligible", "suspended", "under_review", "pending_classification"] as const;
const CATEGORIES = Object.keys(CATEGORY_LABEL);

export function PropertyDetail({ id, roles }: { id: string; roles: Role[] }) {
  const d = useLoad(() => adminFetch<Detail>(`/v1/properties/${encodeURIComponent(id)}`), [id]);
  const [dialog, setDialog] = useState<"eligibility" | "classification" | null>(null);
  const [eligibility, setEligibility] = useState<(typeof ELIGIBILITY)[number]>("suspended");
  const [category, setCategory] = useState(CATEGORIES[0]!);
  const [subtype, setSubtype] = useState("");

  if (d.loading && !d.data) return <Loading />;
  if (d.error || !d.data) return <ErrorBox error={d.error} onRetry={d.reload} />;
  const p = d.data.property;
  const [lon, lat] = p.interaction_point?.coordinates ?? [];

  return (
    <>
      <h1>{p.address_label ?? "Imóvel sem endereço"} <Badge value={p.eligibility} /></h1>
      <p className="muted small"><code>{p.id}</code> · versão {p.version}</p>
      <div className="grid">
        <Section title="Dados" actions={<>
          {allowed(roles, ["geo", "support"]) && <button type="button" onClick={() => setDialog("eligibility")}>Elegibilidade</button>}
          {allowed(roles, ["geo"]) && <button type="button" onClick={() => setDialog("classification")}>Classificação</button>}
        </>}>
          <KeyValues items={[
            ["Classificação", `${p.classification_status}${p.subtype ? ` · ${p.subtype}` : ""}`],
            ["Motivo da elegibilidade", p.eligibility_reason ?? "—"],
            ["Ponto de interação", lat != null ? `${lat.toFixed(6)}, ${lon!.toFixed(6)} (${p.interaction_quality})` : "—"],
            ["Altura", `${p.height_m} m (${p.height_source})`],
            ["Atualizado", dateTime(p.updated_at)],
          ]} />
        </Section>
        <Section title="Origem (OSM)">
          <DataTable rows={d.data.source_features} empty="Sem feição de origem vinculada." columns={[
            { key: "source_id", label: "Objeto", render: (f) => (
              <a href={`https://www.openstreetmap.org/${String(f.object_type)}/${String(f.source_id)}`} target="_blank" rel="noreferrer noopener">
                {String(f.object_type)}/{String(f.source_id)}
              </a>) },
            { key: "role", label: "Papel" },
            { key: "source_version", label: "Versão", numeric: true },
            { key: "active", label: "Ativa", render: (f) => (f.active ? "sim" : "não") },
          ]} />
        </Section>
      </div>
      <Section title="Posses">
        <DataTable rows={d.data.ownerships} rowKey={(o) => o.ownership_id} columns={[
          { key: "owner_nickname", label: "Dono", render: (o) => <a href={href("users", o.owner_id)}>{o.owner_nickname}</a> },
          { key: "acquired_via", label: "Via" },
          { key: "purchase_price", label: "Preço", numeric: true, render: (o) => money(o.purchase_price) },
          { key: "income_credited", label: "Renda creditada", numeric: true, render: (o) => money(o.income_credited) },
          { key: "acquired_at", label: "Desde", render: (o) => dateTime(o.acquired_at) },
          { key: "ended_at", label: "Até", render: (o) => o.ended_at ? `${dateTime(o.ended_at)} (${o.end_reason ?? "—"})` : "atual" },
        ]} />
      </Section>
      <Section title="Negociações">
        <DataTable rows={d.data.trades} columns={[
          { key: "occurred_at", label: "Quando", render: (t) => dateTime(t.occurred_at) },
          { key: "type", label: "Tipo" },
          { key: "price", label: "Preço", numeric: true, render: (t) => money(t.price) },
          { key: "buyer_id", label: "Comprador", render: (t) => t.buyer_id ? <a href={href("users", String(t.buyer_id))}>{shortId(t.buyer_id)}</a> : "—" },
          { key: "seller_id", label: "Vendedor", render: (t) => t.seller_id ? <a href={href("users", String(t.seller_id))}>{shortId(t.seller_id)}</a> : "sistema" },
        ]} />
      </Section>
      <Section title="Casos de revisão">
        <DataTable rows={d.data.review_cases} columns={[
          { key: "created_at", label: "Aberto", render: (c) => dateTime(c.created_at) },
          { key: "reason", label: "Motivo" },
          { key: "status", label: "Status", render: (c) => <Badge value={c.status} /> },
          { key: "resolution", label: "Resolução", render: (c) => c.resolution ? <code>{JSON.stringify(c.resolution)}</code> : "—" },
        ]} />
      </Section>
      <Section title="Mudanças geográficas (últimas 20)">
        <DataTable rows={d.data.changes} columns={[
          { key: "changed_at", label: "Quando", render: (c) => dateTime(c.changed_at) },
          { key: "change_kind", label: "Tipo" },
          { key: "batch_id", label: "Lote", render: (c) => <code>{shortId(c.batch_id)}</code> },
        ]} />
      </Section>

      {dialog === "eligibility" && (
        <MutationDialog
          title="Alterar elegibilidade"
          description="Imóvel suspenso não aceita compra nem propostas; dono atual mantém a posse e a renda acumulada."
          submit={(reason, key) => adminFetch(`/v1/properties/${id}/eligibility`, { idempotencyKey: key, body: { eligibility, reason } })}
          onClose={() => setDialog(null)} onDone={d.reload}>
          <label className="field"><span>Nova elegibilidade</span>
            <select value={eligibility} onChange={(e) => setEligibility(e.target.value as (typeof ELIGIBILITY)[number])}>
              {ELIGIBILITY.map((v) => <option key={v} value={v}>{v}</option>)}
            </select>
          </label>
        </MutationDialog>
      )}
      {dialog === "classification" && (
        <MutationDialog
          title="Corrigir classificação"
          description="Muda a categoria (e o preço base de novas compras pelo sistema). Posses e preços já pagos não mudam."
          submit={(reason, key) => adminFetch(`/v1/properties/${id}/classification`, {
            idempotencyKey: key, body: { category, subtype: subtype.trim() || null, reason },
          })}
          onClose={() => setDialog(null)} onDone={d.reload}>
          <label className="field"><span>Categoria</span>
            <select value={category} onChange={(e) => setCategory(e.target.value)}>
              {CATEGORIES.map((c) => <option key={c} value={c}>{CATEGORY_LABEL[c]}</option>)}
            </select>
          </label>
          <label className="field"><span>Subtipo (opcional)</span>
            <input value={subtype} onChange={(e) => setSubtype(e.target.value)} maxLength={40} />
          </label>
        </MutationDialog>
      )}
    </>
  );
}
