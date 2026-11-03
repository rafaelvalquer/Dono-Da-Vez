// Uso: node simulate.mjs [--days 30,90,180] [--scenario baseline|abuse|all] [--seed 42] [--out out]
// Gera out/<cenário>-<dias>d.json e out/report.md com inflação, concentração e efeito de abuso.
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";
import { loadGameConfig } from "./sim/config.mjs";
import { SCENARIOS, checkInvariants, simulate } from "./sim/model.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const { values } = parseArgs({
  options: {
    days: { type: "string", default: "30,90,180" },
    scenario: { type: "string", default: "all" },
    seed: { type: "string", default: "42" },
    out: { type: "string", default: join(here, "out") },
  },
});

const config = loadGameConfig();
const horizons = values.days.split(",").map(Number);
const scenarios = values.scenario === "all" ? Object.keys(SCENARIOS) : [values.scenario];
const seed = Number(values.seed);
const coins = (cents) => (cents / 100).toLocaleString("pt-BR", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const pct = (v) => (v == null ? "—" : `${(v * 100).toFixed(1)}%`);

mkdirSync(values.out, { recursive: true });
const rows = [];
let failed = false;

for (const scenario of scenarios) {
  const run = simulate({ days: Math.max(...horizons), seed, scenario, config });
  const errors = checkInvariants(run);
  if (errors.length) { failed = true; console.error(`[${scenario}] invariantes violadas:\n  ${errors.slice(0, 10).join("\n  ")}`); }
  for (const d of horizons) {
    const m = run.daily[d - 1];
    const week = run.daily.slice(Math.max(0, d - 7), d);
    const supplyGrowth = week.length > 1 ? (week.at(-1).money_supply / week[0].money_supply) ** (1 / (week.length - 1)) - 1 : 0;
    const perActive = m.active ? m.money_supply / m.active : 0;
    const row = { scenario, ...m, day: d, supply_growth_7d_per_day: supplyGrowth, supply_per_active: Math.round(perActive) };
    rows.push(row);
    writeFileSync(join(values.out, `${scenario}-${d}d.json`), JSON.stringify({ config, scenario: SCENARIOS[scenario], seed, metrics: row, counters: run.counters, series: run.daily.slice(0, d) }, null, 2));
  }
  console.log(`[${scenario}] ${run.trades.length} negociações; invariantes: ${errors.length ? "FALHA" : "ok"}; contadores ${JSON.stringify(run.counters)}`);
}

const header = "| Cenário | Dia | Jogadores | Ativos | Massa monetária | Massa/ativo | Crescimento/dia (7d) | Emitido (renda) | Tesouro | Imóveis com dono | Top 10 renda | Gini riqueza | Preço/base (vol.) | Abusador |";
const md = [
  `# Simulação econômica — semente ${seed}`,
  "",
  `Parâmetros lidos de \`supabase/migrations/20261103120100_config_catalog.sql\` (saldo inicial ${coins(config.initialBalance)}, renda ${config.dailyIncomeRate * 100}%/dia,`,
  `+${Math.round((config.forcedPremium - 1) * 100)}% na compra sem aceite, inatividade ${config.inactivityDays} dias, bloqueio de risco ≥ ${config.riskBlockScore}).`,
  "",
  ...Object.entries(SCENARIOS).filter(([k]) => scenarios.includes(k)).map(([k, s]) => `- **${k}**: ${s.description}.`),
  "",
  header,
  "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|",
  ...rows.map((r) => `| ${r.scenario} | ${r.day} | ${r.players} | ${r.active} | ${coins(r.money_supply)} | ${coins(r.supply_per_active)} | ${pct(r.supply_growth_7d_per_day)} | ${coins(r.issued_income)} | ${coins(r.treasury)} | ${r.owned_properties} | ${pct(r.top10_income_share)} | ${r.gini_wealth.toFixed(3)} | ${r.avg_voluntary_price_ratio?.toFixed(2) ?? "—"} | ${pct(r.abuser_wealth_share)} |`),
  "",
  "Leitura: a renda de 1%/dia sobre o preço pago é a principal fonte de emissão; o tesouro (compras do sistema) é o",
  "único sumidouro. Crescimento diário da massa por jogador ativo acima de ~1% indica inflação a acompanhar no relatório",
  "diário (`game_internal.economy_daily_report`). A decisão de expansão regional e de novos parâmetros é do produto.",
];
writeFileSync(join(values.out, "report.md"), md.join("\n") + "\n");
console.log(md.join("\n"));
if (failed) process.exitCode = 1;
