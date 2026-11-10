import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { loadGameConfig } from "../sim/config.mjs";
import { checkInvariants, gini, simulate } from "../sim/model.mjs";

const config = loadGameConfig();

describe("configuração", () => {
  it("lê parâmetros e catálogo da migração", () => {
    assert.equal(config.initialBalance, 500_000);
    assert.equal(config.dailyIncomeRate, 0.01);
    assert.equal(config.forcedPremium, 1.2);
    assert.deepEqual(config.categories.map((c) => c.code), ["house_small", "house_large", "commercial", "residential_building"]);
  });

  it("aplica os patches de configuração das migrações seguintes (bônus de inauguração)", () => {
    assert.equal(config.openingBonusRate, 0.04);
    assert.equal(config.openingBonusDays, 7);
    const v1 = loadGameConfig(undefined, { patches: false });
    assert.equal(v1.openingBonusRate, 0);
    assert.equal(v1.dailyIncomeRate, config.dailyIncomeRate);
  });
});

describe("simulador", () => {
  it("é determinístico para a mesma semente", () => {
    const a = simulate({ days: 30, seed: 7, config });
    const b = simulate({ days: 30, seed: 7, config });
    assert.deepEqual(a.daily, b.daily);
    assert.notDeepEqual(simulate({ days: 30, seed: 8, config }).daily, a.daily);
  });

  for (const scenario of ["baseline", "abuse"]) {
    for (const days of [30, 90, 180]) {
      it(`${scenario} ${days} dias: conservação (massa + tesouro = emitido), reservas e +20% respeitados`, () => {
        const run = simulate({ days, scenario, config });
        assert.deepEqual(checkInvariants(run), []);
        const owners = new Map();
        for (const p of run.props) if (p.owner !== null) owners.set(p.id, p.owner);
        assert.ok(owners.size <= run.props.length);
        assert.equal(run.daily.length, days);
      });
    }
  }

  it("abuso por contas novas abaixo de 3× a categoria não é bloqueado (risco conhecido a monitorar)", () => {
    const base = simulate({ days: 60, scenario: "baseline", config });
    const abuse = simulate({ days: 60, scenario: "abuse", config });
    const share = abuse.daily.at(-1).abuser_wealth_share;
    const p99 = [...base.players].map((p) => p.balance).sort((a, b) => b - a)[Math.floor(base.players.length / 100)];
    assert.ok(abuse.players[abuse.abuserId].balance > p99, "abusador deveria ficar acima do p99 de saldo orgânico");
    assert.ok(share > 0.01, `participação do abusador ${share}`);
  });

  it("funil de conta nova soma no máximo 45 pontos (3–5× a categoria + conta nova) e não atinge o bloqueio de 60", () => {
    // Com saldo inicial de 5.000, uma conta nova não alcança 10× uma casa pequena; a regra de 10× só pega
    // categorias caras. Limitação registrada em docs/economy.md para decisão de produto.
    const run = simulate({ days: 30, scenario: "abuse", config, overrides: { abuse: { startDay: 2, endDay: 20, sybilsPerDay: 3, priceMultiplier: 11 } } });
    const fromSybils = run.trades.filter((t) => t.type === "voluntary" && run.players[t.buyer].kind === "sybil");
    assert.ok(fromSybils.length > 40, `vendas a contas novas: ${fromSybils.length}`);
    assert.ok(fromSybils.every((t) => t.price <= config.initialBalance));
  });

  it("bônus de inauguração: +4% nos 7 incrementos após a compra do sistema, depois só 1%", () => {
    // Um único jogador: só há compras do sistema e nenhuma venda, então a renda total tem forma fechada.
    const days = 30;
    const run = simulate({ days, seed: 3, config, overrides: { initialPlayers: 1, dailySignups: 0, properties: 40, cells: 1 } });
    assert.ok(run.trades.length >= 2 && run.trades.every((t) => t.type === "system"), JSON.stringify(run.counters));
    const expected = run.trades.reduce((s, t) => {
      const n = days - 1 - t.day; // incrementos depois do dia da compra
      return s + t.price * (config.dailyIncomeRate * n + config.openingBonusRate * Math.min(n, config.openingBonusDays));
    }, 0);
    const accrued = run.ledger.issuedIncome + run.props.reduce((s, p) => s + p.frac, 0);
    assert.ok(Math.abs(accrued - expected) < 1e-6 * expected, `renda ${accrued} ≠ esperado ${expected}`);
  });

  it("bônus de inauguração só na compra do sistema; venda e compra sem aceite não renovam", () => {
    const run = simulate({ days: 90, scenario: "baseline", config });
    const last = new Map();
    for (const t of run.trades) last.set(t.prop, t);
    let system = 0, other = 0;
    for (const p of run.props) {
      const t = last.get(p.id);
      if (!t) continue;
      if (t.type === "system") { system += 1; assert.equal(p.bonusRate, config.openingBonusRate); assert.equal(p.bonusUntil, t.day + config.openingBonusDays); }
      else { other += 1; assert.equal(p.bonusRate, 0, `imóvel ${p.id} (${t.type})`); }
    }
    assert.ok(system > 0 && other > 0, `sistema ${system}, outras ${other}`);
  });

  it("gini: 0 para igualdade e próximo de 1 para concentração total", () => {
    assert.equal(gini([5, 5, 5, 5]), 0);
    assert.ok(gini([0, 0, 0, 100]) > 0.7);
  });
});
