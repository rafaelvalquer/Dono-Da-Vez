import { useState } from "react";
import { MutationDialog } from "../components/MutationDialog";
import { Badge, DataTable, ErrorBox, JsonView, Loading, Section } from "../components/ui";
import { adminFetch } from "../lib/api";
import { dateTime, shortId } from "../lib/format";
import { useLoad } from "../lib/hooks";
import { allowed, type Role } from "../lib/roles";

// ---------------------------------------------------------------------------
// Lotes de importação OSM: staged → validated → approved → published
// ---------------------------------------------------------------------------
type Batch = Record<string, unknown> & {
  id: string; region_code: string; provider: string; source_version: string | null; source_hash: string; rules_version: number;
  status: string; counts: Record<string, number>; report: unknown; errors: unknown[]; started_at: string; published_at: string | null;
};
type BatchAction = "approve" | "reject" | "publish";

const ACTION_TEXT: Record<BatchAction, { title: string; description: string; danger?: boolean }> = {
  approve: { title: "Aprovar lote", description: "Confirma que o relatório de diferenças foi revisado. Publicar é um passo separado." },
  reject: { title: "Rejeitar lote", description: "O lote não será publicado; o catálogo atual segue ativo.", danger: true },
  publish: { title: "Publicar lote", description: "Aplica criações, alterações e suspensões ao catálogo. Nunca altera carteiras, preços pagos ou posses." },
};

export function Imports() {
  const [region, setRegion] = useState("");
  const list = useLoad(() => adminFetch<Batch[]>("/v1/import-batches", { query: { region: region.trim() || undefined } }), [region]);
  const [open, setOpen] = useState<{ batch: Batch; action: BatchAction } | null>(null);

  return (
    <>
      <h1>Importações</h1>
      <div className="row">
        <input value={region} onChange={(e) => setRegion(e.target.value)} placeholder="Filtrar por código de região" />
      </div>
      {list.error ? <ErrorBox error={list.error} onRetry={list.reload} /> : list.loading && !list.data ? <Loading /> : (
        <DataTable rows={list.data} empty="Nenhum lote." columns={[
          { key: "started_at", label: "Início", render: (b) => dateTime(b.started_at) },
          { key: "region_code", label: "Região" },
          { key: "source_version", label: "Fonte", render: (b) => <span title={b.source_hash}>{b.provider} {b.source_version ?? shortId(b.source_hash)}</span> },
          { key: "rules_version", label: "Regras", numeric: true },
          { key: "counts", label: "Contagens", render: (b) => Object.entries(b.counts ?? {}).map(([k, n]) => `${k}: ${n}`).join(" · ") || "—" },
          { key: "report", label: "Relatório", render: (b) => <details><summary>ver</summary><JsonView value={{ report: b.report, errors: b.errors }} /></details> },
          { key: "status", label: "Status", render: (b) => <Badge value={b.status} /> },
          { key: "actions", label: "", render: (b) => (
            <div className="row">
              {b.status === "validated" && <button type="button" onClick={() => setOpen({ batch: b, action: "approve" })}>Aprovar</button>}
              {b.status === "approved" && <button type="button" className="primary" onClick={() => setOpen({ batch: b, action: "publish" })}>Publicar</button>}
              {["staged", "validated", "approved"].includes(b.status) && <button type="button" className="danger" onClick={() => setOpen({ batch: b, action: "reject" })}>Rejeitar</button>}
            </div>
          ) },
        ]} />
      )}
      {open && (
        <MutationDialog
          title={ACTION_TEXT[open.action].title}
          description={ACTION_TEXT[open.action].description}
          danger={ACTION_TEXT[open.action].danger}
          reasonLabel="Nota (fica na auditoria)"
          submit={(note) => adminFetch(`/v1/import-batches/${open.batch.id}/${open.action}`, { body: { note } })}
          onClose={() => setOpen(null)} onDone={list.reload} />
      )}
    </>
  );
}

// ---------------------------------------------------------------------------
// Regiões: liberação por etapas (draft → qa → released; paused para incidentes)
// ---------------------------------------------------------------------------
type Region = {
  id: string; code: string; name: string; release_status: string; import_status: string; active_dataset_version: string | null;
  area_km2: number; properties: Record<string, number> | null;
};
type RegionsResponse = { regions: Region[]; region_interest: { cell: string; players: number }[] };
const RELEASE = ["draft", "qa", "released", "paused"] as const;

export function Regions({ roles }: { roles: Role[] }) {
  const data = useLoad(() => adminFetch<RegionsResponse>("/v1/regions"), []);
  const [open, setOpen] = useState<Region | null>(null);
  const [next, setNext] = useState<(typeof RELEASE)[number]>("qa");
  const geo = allowed(roles, ["geo"]);

  if (data.loading && !data.data) return <Loading />;
  if (data.error || !data.data) return <ErrorBox error={data.error} onRetry={data.reload} />;
  return (
    <>
      <h1>Regiões</h1>
      <Section title="Regiões">
        <DataTable rows={data.data.regions} columns={[
          { key: "code", label: "Código" },
          { key: "name", label: "Nome" },
          { key: "area_km2", label: "Área (km²)", numeric: true },
          { key: "active_dataset_version", label: "Dataset ativo", render: (r) => r.active_dataset_version ?? "—" },
          { key: "properties", label: "Imóveis", render: (r) => Object.entries(r.properties ?? {}).map(([k, n]) => `${k}: ${n}`).join(" · ") || "—" },
          { key: "import_status", label: "Importação", render: (r) => <Badge value={r.import_status} /> },
          { key: "release_status", label: "Liberação", render: (r) => <Badge value={r.release_status} /> },
          { key: "actions", label: "", render: (r) => geo
            ? <button type="button" onClick={() => { setOpen(r); setNext(r.release_status === "released" ? "paused" : "released"); }}>Alterar</button>
            : null },
        ]} />
      </Section>
      <Section title="Interesse fora das regiões liberadas">
        <p className="muted small">Células agregadas (sem localização individual) onde jogadores abriram o app fora de região liberada.</p>
        <DataTable rows={data.data.region_interest} rowKey={(r) => r.cell} columns={[
          { key: "cell", label: "Célula" },
          { key: "players", label: "Jogadores", numeric: true },
        ]} />
      </Section>
      {open && (
        <MutationDialog
          title={`Liberação de ${open.name}`}
          description="Liberar exige lote publicado e o checklist de QA de campo (docs/operations.md › Liberação de região). Pausar esconde a região do mapa sem apagar posses."
          danger={next === "paused"}
          submit={(reason) => adminFetch(`/v1/regions/${open.id}/release`, { body: { status: next, reason } })}
          onClose={() => setOpen(null)} onDone={data.reload}>
          <label className="field"><span>Novo status</span>
            <select value={next} onChange={(e) => setNext(e.target.value as (typeof RELEASE)[number])}>
              {RELEASE.map((s) => <option key={s} value={s}>{s}</option>)}
            </select>
          </label>
        </MutationDialog>
      )}
    </>
  );
}
