// Lê os parâmetros e o catálogo da própria migração (fonte única), para o simulador não divergir do banco.
// Versões posteriores da configuração entram como blocos $patch$ em migrações mais novas, aplicados em ordem.
import { readdirSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), "..", "..", "..");
const CATALOG = join(repoRoot, "supabase", "migrations", "20261103120100_config_catalog.sql");

/** Patches de configuração das migrações posteriores ao catálogo, na ordem de aplicação (nome do arquivo). */
export function configPatches(path = CATALOG) {
  const dir = dirname(path);
  const base = path.slice(dir.length + 1);
  return readdirSync(dir).filter((f) => f.endsWith(".sql") && f > base).sort()
    .flatMap((f) => [...readFileSync(join(dir, f), "utf8").matchAll(/\$patch\$([\s\S]*?)\$patch\$/g)]
      .map((m) => ({ file: f, values: JSON.parse(m[1]) })));
}

export function loadGameConfig(path = CATALOG, { patches = true } = {}) {
  const sql = readFileSync(path, "utf8");
  const json = sql.match(/\$json\$([\s\S]*?)\$json\$/);
  if (!json) throw new Error("bloco $json$ da configuração v1 não encontrado");
  const cfg = JSON.parse(json[1]);
  if (patches) for (const p of configPatches(path)) Object.assign(cfg, p.values);
  const categories = [...sql.matchAll(/\((\d+),\s*'([a-z_]+)',\s*'[^']+',\s*([\d.]+),\s*'[a-z_]+'\)/g)]
    .map((m) => ({ id: Number(m[1]), code: m[2], basePrice: Math.round(Number(m[3]) * 100) }));
  if (categories.length === 0) throw new Error("catálogo de categorias não encontrado");
  return {
    initialBalance: Math.round(Number(cfg.initial_balance) * 100),
    dailyIncomeRate: Number(cfg.daily_income_rate),
    forcedPremium: Number(cfg.forced_premium),
    responseWindowDays: cfg.proposal_response_window_s / 86400,
    forcedWindowDays: cfg.forced_purchase_window_s / 86400,
    inactivityDays: cfg.owner_inactivity_s / 86400,
    protectionDays: cfg.transfer_protection_s / 86400,
    cooldownDays: cfg.proposal_cooldown_s / 86400,
    maxOpenProposals: cfg.max_open_proposals_per_buyer,
    riskBlockScore: cfg.risk_block_score,
    // Bônus de inauguração: só compras do sistema, fixado na compra (como no banco).
    openingBonusRate: Number(cfg.opening_bonus_rate ?? 0),
    openingBonusDays: (cfg.opening_bonus_s ?? 0) / 86400,
    categories,
  };
}
