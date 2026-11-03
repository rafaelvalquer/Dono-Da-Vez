import { useState } from "react";
import { MutationDialog } from "../components/MutationDialog";
import { Badge, DataTable, ErrorBox, JsonView, Loading, Tabs } from "../components/ui";
import { adminFetch } from "../lib/api";
import { dateTime, money, shortId } from "../lib/format";
import { href, useLoad } from "../lib/hooks";
import { allowed, type Role } from "../lib/roles";

type Row = Record<string, unknown>;
type PropertySummary = { id: string; address_label: string | null; category: string | null } | null;

function PropertyLink({ p }: { p: PropertySummary }) {
  if (!p) return <>—</>;
  return <a href={href("properties", p.id)}>{p.address_label ?? shortId(p.id)}</a>;
}

// ---------------------------------------------------------------------------
// Casos de revisão geográfica
// ---------------------------------------------------------------------------
type ReviewCase = Row & { id: string; reason: string; status: string; created_at: string; evidence: unknown; property: PropertySummary };
const CASE_ACTIONS = [
  ["dismiss", "Descartar (sem mudança)"],
  ["restore_eligibility", "Liberar imóvel (requer classificação resolvida)"],
  ["keep_suspended", "Manter suspenso"],
  ["verify_entrance", "Confirmar entrada verificada"],
  ["compensate_and_close", "Compensar dono e devolver ao sistema (financeiro)"],
] as const;

export function ReviewCases() {
  const [status, setStatus] = useState<"open" | "resolved" | "dismissed">("open");
  const list = useLoad(() => adminFetch<ReviewCase[]>("/v1/review-cases", { query: { status } }), [status]);
  const [open, setOpen] = useState<ReviewCase | null>(null);
  const [action, setAction] = useState<(typeof CASE_ACTIONS)[number][0]>("dismiss");
  const [amount, setAmount] = useState("");
  const amountOk = action !== "compensate_and_close" || /^\d{1,13}(\.\d{1,2})?$/.test(amount.trim());

  return (
    <>
      <h1>Revisão geográfica</h1>
      <Tabs value={status} onChange={setStatus} options={[["open", "Abertos"], ["resolved", "Resolvidos"], ["dismissed", "Descartados"]]} />
      {list.error ? <ErrorBox error={list.error} onRetry={list.reload} /> : list.loading && !list.data ? <Loading /> : (
        <DataTable rows={list.data} empty="Nenhum caso." columns={[
          { key: "created_at", label: "Aberto", render: (c) => dateTime(c.created_at) },
          { key: "reason", label: "Motivo" },
          { key: "property", label: "Imóvel", render: (c) => <PropertyLink p={c.property} /> },
          { key: "evidence", label: "Evidência", render: (c) => <details><summary>ver</summary><JsonView value={c.evidence} /></details> },
          { key: "actions", label: "", render: (c) => c.status === "open"
            ? <button type="button" onClick={() => { setOpen(c); setAction("dismiss"); setAmount(""); }}>Resolver</button>
            : <Badge value={c.status} /> },
        ]} />
      )}
      {open && (
        <MutationDialog
          title="Resolver caso"
          description={<>Motivo: <strong>{open.reason}</strong>. Nenhuma ação apaga posse ou histórico; compensação é lançada no razão.</>}
          reasonLabel="Nota (fica na auditoria)"
          canSubmit={amountOk}
          submit={(note, key) => adminFetch(`/v1/review-cases/${open.id}/resolve`, {
            idempotencyKey: key,
            body: { action, note, ...(action === "compensate_and_close" ? { amount: amount.trim() } : {}) },
          })}
          onClose={() => setOpen(null)} onDone={list.reload}>
          <fieldset className="field">
            <legend>Ação</legend>
            {CASE_ACTIONS.map(([v, label]) => (
              <label key={v} className="check"><input type="radio" name="action" checked={action === v} onChange={() => setAction(v)} /> {label}</label>
            ))}
          </fieldset>
          {action === "compensate_and_close" && (
            <label className="field"><span>Compensação ao dono (moedas, 2 casas)</span>
              <input value={amount} onChange={(e) => setAmount(e.target.value)} inputMode="decimal" required />
            </label>
          )}
        </MutationDialog>
      )}
    </>
  );
}

// ---------------------------------------------------------------------------
// Relatos de jogadores (protocolo DV-000123)
// ---------------------------------------------------------------------------
type Report = Row & { id: number; protocol: string; category: string; message: string; status: string; created_at: string;
                      reporter_id: string; reporter_nickname: string; property_id: string | null; resolution: string | null };
const REPORT_STATUS = ["open", "in_progress", "resolved", "rejected"] as const;
type ReportStatus = (typeof REPORT_STATUS)[number];

export function Reports() {
  const [status, setStatus] = useState<ReportStatus>("open");
  const list = useLoad(() => adminFetch<Report[]>("/v1/reports", { query: { status } }), [status]);
  const [open, setOpen] = useState<Report | null>(null);
  const [next, setNext] = useState<ReportStatus>("in_progress");

  return (
    <>
      <h1>Relatos</h1>
      <Tabs value={status} onChange={setStatus} options={[["open", "Abertos"], ["in_progress", "Em andamento"], ["resolved", "Resolvidos"], ["rejected", "Recusados"]]} />
      {list.error ? <ErrorBox error={list.error} onRetry={list.reload} /> : list.loading && !list.data ? <Loading /> : (
        <DataTable rows={list.data} empty="Nenhum relato." columns={[
          { key: "protocol", label: "Protocolo", render: (r) => <code>{r.protocol}</code> },
          { key: "created_at", label: "Quando", render: (r) => dateTime(r.created_at) },
          { key: "category", label: "Categoria" },
          { key: "reporter_nickname", label: "Jogador", render: (r) => <a href={href("users", r.reporter_id)}>{r.reporter_nickname}</a> },
          { key: "property_id", label: "Imóvel", render: (r) => r.property_id ? <a href={href("properties", r.property_id)}>{shortId(r.property_id)}</a> : "—" },
          { key: "message", label: "Mensagem", render: (r) => <span className="pre">{r.message}</span> },
          { key: "actions", label: "", render: (r) => <button type="button" onClick={() => { setOpen(r); setNext(r.status === "open" ? "in_progress" : "resolved"); }}>Atualizar</button> },
        ]} />
      )}
      {open && (
        <MutationDialog
          title={`Atualizar ${open.protocol}`}
          description="O jogador recebe aviso da mudança de status. A resolução é visível para ele."
          reasonLabel="Resolução / resposta ao jogador"
          submit={(resolution) => adminFetch(`/v1/reports/${open.id}`, { body: { status: next, resolution } })}
          onClose={() => setOpen(null)} onDone={list.reload}>
          <label className="field"><span>Novo status</span>
            <select value={next} onChange={(e) => setNext(e.target.value as ReportStatus)}>
              {REPORT_STATUS.map((s) => <option key={s} value={s}>{s}</option>)}
            </select>
          </label>
        </MutationDialog>
      )}
    </>
  );
}

// ---------------------------------------------------------------------------
// Revisões de risco (negociações bloqueadas por pontuação)
// ---------------------------------------------------------------------------
type RiskReview = Row & { id: string; buyer_id: string; seller_id: string; buyer_nickname: string; seller_nickname: string;
                          property: PropertySummary; context: string; amount: string | number | null; score: number;
                          reasons: string[]; status: string; created_at: string; note: string | null };

export function RiskReviews() {
  const [status, setStatus] = useState<"pending" | "approved" | "rejected">("pending");
  const list = useLoad(() => adminFetch<RiskReview[]>("/v1/risk-reviews", { query: { status } }), [status]);
  const [open, setOpen] = useState<{ review: RiskReview; decision: "approved" | "rejected" } | null>(null);

  return (
    <>
      <h1>Revisões de risco</h1>
      <p className="muted small">Aprovar libera o par para repetir a negociação; nada é executado automaticamente.</p>
      <Tabs value={status} onChange={setStatus} options={[["pending", "Pendentes"], ["approved", "Aprovadas"], ["rejected", "Recusadas"]]} />
      {list.error ? <ErrorBox error={list.error} onRetry={list.reload} /> : list.loading && !list.data ? <Loading /> : (
        <DataTable rows={list.data} empty="Nenhuma revisão." columns={[
          { key: "created_at", label: "Quando", render: (r) => dateTime(r.created_at) },
          { key: "context", label: "Contexto" },
          { key: "buyer", label: "Comprador", render: (r) => <a href={href("users", r.buyer_id)}>{r.buyer_nickname}</a> },
          { key: "seller", label: "Vendedor", render: (r) => <a href={href("users", r.seller_id)}>{r.seller_nickname}</a> },
          { key: "property", label: "Imóvel", render: (r) => <PropertyLink p={r.property} /> },
          { key: "amount", label: "Valor", numeric: true, render: (r) => money(r.amount) },
          { key: "score", label: "Pontos", numeric: true },
          { key: "reasons", label: "Motivos", render: (r) => r.reasons.join(", ") },
          { key: "actions", label: "", render: (r) => r.status === "pending" ? (
            <div className="row">
              <button type="button" onClick={() => setOpen({ review: r, decision: "approved" })}>Aprovar</button>
              <button type="button" className="danger" onClick={() => setOpen({ review: r, decision: "rejected" })}>Recusar</button>
            </div>
          ) : <Badge value={r.status} /> },
        ]} />
      )}
      {open && (
        <MutationDialog
          title={open.decision === "approved" ? "Aprovar negociação" : "Recusar negociação"}
          danger={open.decision === "rejected"}
          reasonLabel="Nota (fica na auditoria)"
          submit={(note) => adminFetch(`/v1/risk-reviews/${open.review.id}`, { body: { decision: open.decision, note } })}
          onClose={() => setOpen(null)} onDone={list.reload} />
      )}
    </>
  );
}

// ---------------------------------------------------------------------------
// Alertas de conciliação
// ---------------------------------------------------------------------------
type Alert = Row & { id: string; kind: string; user_id: string | null; subject_id: string | null; details: unknown;
                     status: string; created_at: string; note: string | null };

export function Alerts({ roles }: { roles: Role[] }) {
  const [status, setStatus] = useState<"open" | "acknowledged" | "resolved">("open");
  const list = useLoad(() => adminFetch<Alert[]>("/v1/alerts", { query: { status } }), [status]);
  const [open, setOpen] = useState<{ alert: Alert; next: "acknowledged" | "resolved" } | null>(null);
  const [reconcile, setReconcile] = useState(false);
  const [lastRun, setLastRun] = useState<unknown>(null);
  const finance = allowed(roles, ["finance"]);

  return (
    <>
      <h1>Conciliação</h1>
      <div className="row">
        <Tabs value={status} onChange={setStatus} options={[["open", "Abertos"], ["acknowledged", "Reconhecidos"], ["resolved", "Resolvidos"]]} />
        {finance && <button type="button" onClick={() => setReconcile(true)}>Executar conciliação agora</button>}
      </div>
      {lastRun != null && <details open><summary>Última execução</summary><JsonView value={lastRun} /></details>}
      {list.error ? <ErrorBox error={list.error} onRetry={list.reload} /> : list.loading && !list.data ? <Loading /> : (
        <DataTable rows={list.data} empty="Nenhum alerta." columns={[
          { key: "created_at", label: "Quando", render: (a) => dateTime(a.created_at) },
          { key: "kind", label: "Tipo" },
          { key: "user_id", label: "Jogador", render: (a) => a.user_id ? <a href={href("users", a.user_id)}>{shortId(a.user_id)}</a> : "—" },
          { key: "details", label: "Detalhes", render: (a) => <details><summary>ver</summary><JsonView value={a.details} /></details> },
          { key: "actions", label: "", render: (a) => finance && a.status !== "resolved" ? (
            <div className="row">
              {a.status === "open" && <button type="button" onClick={() => setOpen({ alert: a, next: "acknowledged" })}>Reconhecer</button>}
              <button type="button" onClick={() => setOpen({ alert: a, next: "resolved" })}>Resolver</button>
            </div>
          ) : <Badge value={a.status} /> },
        ]} />
      )}
      <p className="muted small">Carteira com divergência fica congelada; corrija com ajuste compensatório e descongele na ficha do jogador.</p>
      {open && (
        <MutationDialog
          title={open.next === "resolved" ? "Resolver alerta" : "Reconhecer alerta"}
          reasonLabel="Nota (fica na auditoria)"
          submit={(note) => adminFetch(`/v1/alerts/${open.alert.id}`, { body: { status: open.next, note } })}
          onClose={() => setOpen(null)} onDone={list.reload} />
      )}
      {reconcile && (
        <MutationDialog
          title="Executar conciliação"
          description="Compara saldos, razão e reservas de todas as carteiras. Divergências abrem alertas e congelam a carteira."
          requireReason={false}
          submit={() => adminFetch("/v1/reconcile", { method: "POST", body: {} })}
          onClose={() => setReconcile(false)} onDone={(r) => { setLastRun(r); list.reload(); }} />
      )}
    </>
  );
}
