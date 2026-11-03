import { useState } from "react";
import { MutationDialog } from "../components/MutationDialog";
import { Badge, DataTable, ErrorBox, Loading, Tabs } from "../components/ui";
import { adminFetch } from "../lib/api";
import { dateTime, money, shortId } from "../lib/format";
import { href, useLoad } from "../lib/hooks";

type Proposal = Record<string, unknown> & {
  id: string; property_id: string; buyer_id: string; seller_id: string; amount: number | string; forced_price: number | string;
  status: string; created_at: string; expires_at: string; eligible_until: string | null; close_reason: string | null;
};

const STATUS: [string, string][] = [
  ["pending", "Pendentes"], ["accepted", "Aceitas"], ["rejected", "Recusadas"], ["expired", "Expiradas"],
  ["executed_without_acceptance", "Compra sem aceite"], ["cancelled", "Canceladas"], ["", "Todas"],
];

export function Proposals({ userId }: { userId?: string }) {
  const [status, setStatus] = useState("pending");
  const list = useLoad(() => adminFetch<Proposal[]>("/v1/proposals", { query: { status, user: userId } }), [status, userId]);
  const [cancel, setCancel] = useState<Proposal | null>(null);

  return (
    <>
      <h1>Propostas</h1>
      {userId && <p className="muted small">Filtrando pelo jogador <a href={href("users", userId)}><code>{userId}</code></a> · <a href={href("proposals")}>limpar</a></p>}
      <Tabs value={status} onChange={setStatus} options={STATUS} />
      {list.error ? <ErrorBox error={list.error} onRetry={list.reload} /> : list.loading && !list.data ? <Loading /> : (
        <DataTable rows={list.data} empty="Nenhuma proposta." columns={[
          { key: "created_at", label: "Criada", render: (p) => dateTime(p.created_at) },
          { key: "property_id", label: "Imóvel", render: (p) => <a href={href("properties", p.property_id)}>{shortId(p.property_id)}</a> },
          { key: "buyer_id", label: "Comprador", render: (p) => <a href={href("users", p.buyer_id)}>{shortId(p.buyer_id)}</a> },
          { key: "seller_id", label: "Vendedor", render: (p) => <a href={href("users", p.seller_id)}>{shortId(p.seller_id)}</a> },
          { key: "amount", label: "Valor", numeric: true, render: (p) => money(p.amount) },
          { key: "expires_at", label: "Prazo", render: (p) => dateTime(p.expires_at) },
          { key: "status", label: "Status", render: (p) => <Badge value={p.status} /> },
          { key: "actions", label: "", render: (p) => p.status === "pending"
            ? <button type="button" className="danger" onClick={() => setCancel(p)}>Cancelar</button>
            : p.close_reason ? <span className="muted small">{p.close_reason}</span> : null },
        ]} />
      )}
      {cancel && (
        <MutationDialog
          title="Cancelar proposta"
          description={<>Libera a reserva de {money(cancel.amount)} do comprador e avisa as partes. Use para fraude ou erro operacional.</>}
          danger
          submit={(reason, key) => adminFetch(`/v1/proposals/${cancel.id}/cancel`, { idempotencyKey: key, body: { reason } })}
          onClose={() => setCancel(null)} onDone={list.reload} />
      )}
    </>
  );
}
