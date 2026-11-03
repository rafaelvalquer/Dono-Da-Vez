import { useState } from "react";
import { DataTable, ErrorBox, Loading, Section, Tabs } from "../components/ui";
import { adminFetch } from "../lib/api";
import { money } from "../lib/format";
import { useLoad } from "../lib/hooks";

type Metrics = {
  money_supply: string; total_issued: string; treasury: string; issued_today_income: string; issued_today_initial: string;
  daily_income_run_rate: string; top10_income_share: number | null; owners: number; owned_properties: number;
  trades: Record<string, number> | null; avg_voluntary_price_ratio: number | null; active_players: number; new_players: number;
  risk_signals: number; risk_reviews_pending: number; pair_cycles: number;
};
type Day = { date: string; metrics: Metrics } & Record<string, unknown>;

/** Mini gráfico de linha (SVG puro, sem dependência) para a série de massa monetária. */
function Sparkline({ values, label }: { values: number[]; label: string }) {
  if (values.length < 2) return null;
  const w = 600, h = 80, min = Math.min(...values), max = Math.max(...values), span = max - min || 1;
  const pts = values.map((v, i) => `${(i / (values.length - 1)) * w},${h - ((v - min) / span) * (h - 8) - 4}`).join(" ");
  return (
    <svg className="spark" viewBox={`0 0 ${w} ${h}`} role="img" aria-label={label} preserveAspectRatio="none">
      <polyline points={pts} fill="none" stroke="currentColor" strokeWidth="2" vectorEffect="non-scaling-stroke" />
    </svg>
  );
}

const pct = (v: number | null) => (v == null ? "—" : `${(v * 100).toFixed(1)}%`);

export function Economy() {
  const [days, setDays] = useState("30");
  const data = useLoad(() => adminFetch<Day[]>("/v1/economy", { query: { days } }), [days]);

  return (
    <>
      <h1>Economia</h1>
      <p className="muted small">Relatório diário agregado do dia anterior (job dv-economy-report, 00:05 UTC). Compare a emissão com as projeções do simulador (tests/economy).</p>
      <Tabs value={days} onChange={setDays} options={[["7", "7 dias"], ["30", "30 dias"], ["90", "90 dias"], ["180", "180 dias"]]} />
      {data.error ? <ErrorBox error={data.error} onRetry={data.reload} /> : data.loading && !data.data ? <Loading /> : (
        <>
          <Section title="Massa monetária (saldo total dos jogadores)">
            <Sparkline label="Evolução da massa monetária" values={[...(data.data ?? [])].reverse().map((d) => Number(d.metrics.money_supply))} />
          </Section>
          <Section title="Por dia">
            <DataTable rows={data.data} rowKey={(d) => d.date} empty="Sem relatórios no período." columns={[
              { key: "date", label: "Data" },
              { key: "supply", label: "Massa", numeric: true, render: (d) => money(d.metrics.money_supply) },
              { key: "income", label: "Renda emitida", numeric: true, render: (d) => money(d.metrics.issued_today_income) },
              { key: "initial", label: "Saldo inicial", numeric: true, render: (d) => money(d.metrics.issued_today_initial) },
              { key: "run", label: "Renda/dia (taxa)", numeric: true, render: (d) => money(d.metrics.daily_income_run_rate) },
              { key: "top10", label: "Top 10 renda", numeric: true, render: (d) => pct(d.metrics.top10_income_share) },
              { key: "owned", label: "Imóveis com dono", numeric: true, render: (d) => d.metrics.owned_properties },
              { key: "trades", label: "Negociações", render: (d) => Object.entries(d.metrics.trades ?? {}).map(([k, n]) => `${k}: ${n}`).join(" · ") || "—" },
              { key: "ratio", label: "Preço/base (vol.)", numeric: true, render: (d) => d.metrics.avg_voluntary_price_ratio ?? "—" },
              { key: "active", label: "Ativos", numeric: true, render: (d) => d.metrics.active_players },
              { key: "new", label: "Novos", numeric: true, render: (d) => d.metrics.new_players },
              { key: "risk", label: "Sinais de risco", numeric: true, render: (d) => `${d.metrics.risk_signals} (${d.metrics.pair_cycles} ciclos)` },
            ]} />
          </Section>
        </>
      )}
    </>
  );
}
