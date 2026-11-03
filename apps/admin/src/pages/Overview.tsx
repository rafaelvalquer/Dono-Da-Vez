import type { ReactNode } from "react";
import { ErrorBox, KeyValues, Loading, Section } from "../components/ui";
import { adminFetch } from "../lib/api";
import { dateTime, money } from "../lib/format";
import { href, useLoad } from "../lib/hooks";

type Ops = {
  server_time: string;
  outbox: Record<string, number> | null;
  outbox_oldest_pending_s: number | null;
  proposals_pending: number;
  proposals_overdue_unmaterialized: number;
  orphan_holds: number;
  alerts_open: number;
  frozen_wallets: number;
  risk_reviews_pending: number;
  review_cases_open: number;
  gps_rejections_24h: Record<string, number> | null;
  presence_proofs_24h: number;
  operations_failed_24h: Record<string, number> | null;
  issued_24h: string;
};

function counts(v: Record<string, number> | null) {
  const entries = Object.entries(v ?? {});
  if (!entries.length) return "—";
  return entries.sort((a, b) => b[1] - a[1]).map(([k, n]) => `${k}: ${n}`).join(" · ");
}

/** Indicadores operacionais; limiares de alerta seguem docs/operations.md. */
export function Overview() {
  const ops = useLoad(() => adminFetch<Ops>("/v1/ops"), []);
  if (ops.loading && !ops.data) return <Loading />;
  if (ops.error || !ops.data) return <ErrorBox error={ops.error} onRetry={ops.reload} />;
  const o = ops.data;
  const warn = (bad: boolean, v: ReactNode) => <span className={bad ? "bad-text" : undefined}>{v}</span>;
  return (
    <>
      <h1>Operação</h1>
      <p className="muted small">Atualizado em {dateTime(o.server_time)} <button type="button" className="link" onClick={ops.reload}>atualizar</button></p>
      <div className="grid">
        <Section title="Filas de atenção">
          <KeyValues items={[
            ["Alertas de conciliação", <a href={href("alerts")}>{warn(o.alerts_open > 0, o.alerts_open)}</a>],
            ["Carteiras congeladas", warn(o.frozen_wallets > 0, o.frozen_wallets)],
            ["Revisões de risco pendentes", <a href={href("risk")}>{o.risk_reviews_pending}</a>],
            ["Casos geográficos abertos", <a href={href("review-cases")}>{o.review_cases_open}</a>],
          ]} />
        </Section>
        <Section title="Propostas e reservas">
          <KeyValues items={[
            ["Propostas pendentes", o.proposals_pending],
            ["Vencidas sem materializar (> 2 min)", warn(o.proposals_overdue_unmaterialized > 0, o.proposals_overdue_unmaterialized)],
            ["Reservas órfãs", warn(o.orphan_holds > 0, o.orphan_holds)],
          ]} />
        </Section>
        <Section title="Notificações (outbox)">
          <KeyValues items={[
            ["Por status", counts(o.outbox)],
            ["Pendente mais antiga", o.outbox_oldest_pending_s == null ? "—" : warn(o.outbox_oldest_pending_s > 300, `${o.outbox_oldest_pending_s} s`)],
          ]} />
        </Section>
        <Section title="Últimas 24 h">
          <KeyValues items={[
            ["Moedas emitidas (renda + inicial)", money(o.issued_24h)],
            ["Provas de presença", o.presence_proofs_24h],
            ["Rejeições de GPS", counts(o.gps_rejections_24h)],
            ["Operações com falha", counts(o.operations_failed_24h)],
          ]} />
        </Section>
      </div>
    </>
  );
}
