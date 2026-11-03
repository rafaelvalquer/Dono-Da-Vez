import { useState } from "react";
import { MutationDialog } from "../components/MutationDialog";
import { Badge, DataTable, ErrorBox, KeyValues, Loading, Section } from "../components/ui";
import { adminFetch } from "../lib/api";
import { adjustmentError, dateTime, money, shortId } from "../lib/format";
import { href, useLoad } from "../lib/hooks";
import { allowed, type Role } from "../lib/roles";

type Detail = {
  profile: { id: string; nickname: string; status: string; created_at: string; email_confirmed: boolean;
             deletion_requested_at: string | null; inactivity?: Record<string, unknown> } | null;
  wallet: { balance: string; reserved: string; available: string; frozen: boolean; frozen_reason?: string | null } | null;
  ledger: { entry_id: number; amount: string; entry_type: string; transaction_id: string; kind: string; created_at: string }[];
  ownerships: { ownership_id: string; property_id: string; purchase_price: string; acquired_at: string; ended_at: string | null; end_reason: string | null }[];
  proposals: { id: string; property_id: string; amount: string; status: string; buyer_id: string; seller_id: string; created_at: string; expires_at: string }[];
  risk: { score: number; reasons: string[]; context: string; created_at: string }[];
  roles: string[];
};

type Dialog = "suspend" | "activate" | "adjust" | "unfreeze" | "role" | null;
const ROLES: Role[] = ["support", "geo", "finance", "superadmin"];

export function UserDetail({ id, roles }: { id: string; roles: Role[] }) {
  const d = useLoad(() => adminFetch<Detail>(`/v1/users/${encodeURIComponent(id)}`), [id]);
  const [dialog, setDialog] = useState<Dialog>(null);
  const [amount, setAmount] = useState("");
  const [refTx, setRefTx] = useState("");
  const [role, setRole] = useState<Role>("support");
  const [grant, setGrant] = useState(true);

  if (d.loading && !d.data) return <Loading />;
  if (d.error || !d.data) return <ErrorBox error={d.error} onRetry={d.reload} />;
  const { profile, wallet } = d.data;
  if (!profile) return <ErrorBox error={new Error("Jogador não encontrado.")} />;
  const isFinance = allowed(roles, ["finance"]);
  const amountErr = amount ? adjustmentError(amount) : "Informe o valor";

  return (
    <>
      <h1>{profile.nickname} <Badge value={profile.status} /></h1>
      <p className="muted small"><code>{profile.id}</code></p>
      <div className="grid">
        <Section title="Conta" actions={<>
          {allowed(roles, ["support"]) && (profile.status === "active"
            ? <button type="button" className="danger" onClick={() => setDialog("suspend")}>Suspender</button>
            : profile.status === "suspended" && <button type="button" onClick={() => setDialog("activate")}>Reativar</button>)}
          {allowed(roles, []) && <button type="button" onClick={() => setDialog("role")}>Papéis</button>}
        </>}>
          <KeyValues items={[
            ["Criado", dateTime(profile.created_at)],
            ["E-mail confirmado", profile.email_confirmed ? "sim" : "não"],
            ["Exclusão solicitada", dateTime(profile.deletion_requested_at)],
            ["Última atividade qualificada", dateTime(profile.inactivity?.last_qualified_activity_at)],
            ["Papéis administrativos", d.data.roles.length ? d.data.roles.join(", ") : "—"],
          ]} />
        </Section>
        <Section title="Carteira" actions={isFinance && wallet && <>
          <button type="button" onClick={() => setDialog("adjust")}>Ajuste compensatório</button>
          {wallet.frozen && <button type="button" onClick={() => setDialog("unfreeze")}>Descongelar</button>}
        </>}>
          {wallet ? (
            <KeyValues items={[
              ["Saldo", money(wallet.balance)],
              ["Reservado", money(wallet.reserved)],
              ["Disponível", money(wallet.available)],
              ["Congelada", wallet.frozen ? <Badge value="frozen" /> : "não"],
              ...(wallet.frozen ? [["Motivo do congelamento", wallet.frozen_reason ?? "—"] as [string, string]] : []),
            ]} />
          ) : <p className="muted">Sem carteira.</p>}
          <p className="muted small">Saldo nunca é editado diretamente: correções são lançamentos compensatórios no razão.</p>
        </Section>
      </div>

      <Section title="Razão (últimos 100 lançamentos)">
        <DataTable rows={d.data.ledger} rowKey={(e) => String(e.entry_id)} columns={[
          { key: "created_at", label: "Quando", render: (e) => dateTime(e.created_at) },
          { key: "kind", label: "Transação" },
          { key: "entry_type", label: "Lançamento" },
          { key: "amount", label: "Valor", numeric: true, render: (e) => money(e.amount) },
          { key: "transaction_id", label: "Id", render: (e) => <code title={e.transaction_id}>{shortId(e.transaction_id)}</code> },
        ]} />
      </Section>
      <Section title="Posses">
        <DataTable rows={d.data.ownerships} rowKey={(o) => o.ownership_id} columns={[
          { key: "property_id", label: "Imóvel", render: (o) => <a href={href("properties", o.property_id)}>{shortId(o.property_id)}</a> },
          { key: "purchase_price", label: "Preço de compra", numeric: true, render: (o) => money(o.purchase_price) },
          { key: "acquired_at", label: "Desde", render: (o) => dateTime(o.acquired_at) },
          { key: "ended_at", label: "Até", render: (o) => o.ended_at ? `${dateTime(o.ended_at)} (${o.end_reason ?? "—"})` : "atual" },
        ]} />
      </Section>
      <Section title="Propostas" actions={<a href={href("proposals", profile.id)}>ver todas</a>}>
        <DataTable rows={d.data.proposals} columns={[
          { key: "created_at", label: "Criada", render: (p) => dateTime(p.created_at) },
          { key: "role", label: "Papel", render: (p) => (p.buyer_id === profile.id ? "comprador" : "vendedor") },
          { key: "property_id", label: "Imóvel", render: (p) => <a href={href("properties", p.property_id)}>{shortId(p.property_id)}</a> },
          { key: "amount", label: "Valor", numeric: true, render: (p) => money(p.amount) },
          { key: "status", label: "Status", render: (p) => <Badge value={p.status} /> },
        ]} />
      </Section>
      <Section title="Sinais de risco">
        <DataTable rows={d.data.risk} rowKey={(r, i) => `${r.created_at}-${i}`} columns={[
          { key: "created_at", label: "Quando", render: (r) => dateTime(r.created_at) },
          { key: "context", label: "Contexto" },
          { key: "score", label: "Pontuação", numeric: true },
          { key: "reasons", label: "Motivos", render: (r) => r.reasons.join(", ") },
        ]} />
      </Section>

      {(dialog === "suspend" || dialog === "activate") && (
        <MutationDialog
          title={dialog === "suspend" ? "Suspender jogador" : "Reativar jogador"}
          description={dialog === "suspend" ? "O jogador perde acesso ao jogo; posses e saldo ficam preservados." : undefined}
          danger={dialog === "suspend"}
          submit={(reason) => adminFetch(`/v1/users/${id}/status`, { body: { status: dialog === "suspend" ? "suspended" : "active", reason } })}
          onClose={() => setDialog(null)} onDone={d.reload} />
      )}
      {dialog === "adjust" && (
        <MutationDialog
          title="Ajuste compensatório"
          description="Lança crédito (positivo) ou débito (negativo) contra a conta de ajustes. Irreversível: um erro exige outro ajuste."
          canSubmit={!amountErr}
          submit={(reason, key) => adminFetch(`/v1/wallets/${id}/adjustments`, {
            idempotencyKey: key,
            body: { amount: amount.trim().replace(",", "."), reason, ...(refTx.trim() ? { reference_transaction_id: refTx.trim() } : {}) },
          })}
          onClose={() => { setDialog(null); setAmount(""); setRefTx(""); }} onDone={d.reload}>
          <label className="field"><span>Valor (ex.: 25.00 ou -10.50)</span>
            <input value={amount} onChange={(e) => setAmount(e.target.value)} inputMode="decimal" required />
          </label>
          {amount && amountErr && <p className="bad-text small">{amountErr}</p>}
          <label className="field"><span>Transação de referência (opcional)</span>
            <input value={refTx} onChange={(e) => setRefTx(e.target.value)} placeholder="uuid da transação corrigida" />
          </label>
        </MutationDialog>
      )}
      {dialog === "unfreeze" && (
        <MutationDialog
          title="Descongelar carteira"
          description="Só descongele após a conciliação confirmar que saldo, razão e reservas batem; o servidor recusa se não baterem."
          submit={(reason, key) => adminFetch(`/v1/wallets/${id}/unfreeze`, { idempotencyKey: key, body: { reason } })}
          onClose={() => setDialog(null)} onDone={d.reload} />
      )}
      {dialog === "role" && (
        <MutationDialog
          title="Conceder ou revogar papel"
          submit={(reason) => adminFetch(`/v1/users/${id}/roles`, { body: { role, grant, reason } })}
          onClose={() => setDialog(null)} onDone={d.reload}>
          <label className="field"><span>Papel</span>
            <select value={role} onChange={(e) => setRole(e.target.value as Role)}>
              {ROLES.map((r) => <option key={r} value={r}>{r}</option>)}
            </select>
          </label>
          <label className="check"><input type="checkbox" checked={grant} onChange={(e) => setGrant(e.target.checked)} /> Conceder (desmarque para revogar)</label>
        </MutationDialog>
      )}
    </>
  );
}
