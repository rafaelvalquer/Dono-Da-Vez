import type { ReactNode } from "react";
import { AdminError } from "../lib/api";

type Tone = "neutral" | "good" | "warn" | "bad" | "info";

const TONE_BY_VALUE: Record<string, Tone> = {
  active: "good", eligible: "good", released: "good", published: "good", approved: "good", resolved: "good",
  accepted: "good", completed: "good", sent: "good",
  pending: "info", open: "warn", qa: "info", draft: "neutral", staged: "info", validated: "info", running: "info",
  in_progress: "info", acknowledged: "info", under_review: "warn", pending_classification: "warn",
  suspended: "bad", frozen: "bad", failed: "bad", rejected: "bad", paused: "warn", closed: "neutral",
  cancelled: "neutral", expired: "neutral", superseded: "neutral", dismissed: "neutral",
  executed_without_acceptance: "warn",
};

export function Badge({ value, tone }: { value: unknown; tone?: Tone }) {
  const s = String(value ?? "—");
  return <span className={`badge badge-${tone ?? TONE_BY_VALUE[s] ?? "neutral"}`}>{s}</span>;
}

export function Loading() {
  return <p className="muted" role="status">Carregando…</p>;
}

export function ErrorBox({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const e = error instanceof AdminError ? error : null;
  return (
    <div className="error" role="alert">
      <strong>{e ? e.code : "Erro"}</strong>: {error instanceof Error ? error.message : String(error)}
      {e?.correlationId && <div className="muted small">Correlação: <code>{e.correlationId}</code></div>}
      {onRetry && <button type="button" className="link" onClick={onRetry}>Tentar de novo</button>}
    </div>
  );
}

export function Section({ title, actions, children }: { title: string; actions?: ReactNode; children: ReactNode }) {
  return (
    <section className="card">
      <header className="card-head">
        <h2>{title}</h2>
        {actions && <div className="row">{actions}</div>}
      </header>
      {children}
    </section>
  );
}

export function KeyValues({ items }: { items: [string, ReactNode][] }) {
  return (
    <dl className="kv">
      {items.map(([k, v]) => (
        <div key={k}><dt>{k}</dt><dd>{v ?? "—"}</dd></div>
      ))}
    </dl>
  );
}

export function JsonView({ value }: { value: unknown }) {
  return <pre className="json">{JSON.stringify(value, null, 2)}</pre>;
}

export type Column<T> = { key: string; label: string; render?: (row: T) => ReactNode; numeric?: boolean };

export function DataTable<T extends Record<string, unknown>>({ rows, columns, empty = "Nada por aqui.", rowKey }: {
  rows: T[] | undefined;
  columns: Column<T>[];
  empty?: string;
  rowKey?: (row: T, i: number) => string;
}) {
  if (!rows || rows.length === 0) return <p className="muted">{empty}</p>;
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>{columns.map((c) => <th key={c.key} className={c.numeric ? "num" : undefined}>{c.label}</th>)}</tr>
        </thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={rowKey ? rowKey(r, i) : String(r.id ?? i)}>
              {columns.map((c) => (
                <td key={c.key} className={c.numeric ? "num" : undefined}>{c.render ? c.render(r) : String(r[c.key] ?? "—")}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function Tabs<T extends string>({ value, options, onChange }: { value: T; options: [T, string][]; onChange: (v: T) => void }) {
  return (
    <div className="tabs" role="tablist">
      {options.map(([v, label]) => (
        <button key={v} type="button" role="tab" aria-selected={v === value} className={v === value ? "tab active" : "tab"} onClick={() => onChange(v)}>
          {label}
        </button>
      ))}
    </div>
  );
}
