// Propostas (reserva sempre reconciliada), compra sem aceite (+20%) e renda. Plano de testes §17.
import assert from "node:assert/strict";
import { after, before, describe, it } from "node:test";
import { openDb } from "../lib/db.mjs";
import { CLEAN, Game } from "../lib/game.mjs";

let db, g;
before(async () => { db = await openDb(); g = new Game(db); });
after(async () => { await db?.close(); });

/** Dono com um imóvel (casa pequena, 1000) e um comprador interessado. */
async function setup(label) {
  const seller = await g.player(`${label}_s`);
  const buyer = await g.player(`${label}_b`);
  const prop = await g.newProperty(1);
  const r = await g.purchase(seller, prop);
  assert.equal(r.ok, true, JSON.stringify(r));
  await g.endProtection(prop);
  return { seller, buyer, prop, ownershipId: r.ownership_id };
}

describe("propostas", () => {
  it("criar reserva o valor; recusar libera a reserva", async () => {
    const { seller, buyer, prop } = await setup("rej");
    const p = await g.propose(buyer, prop, 1200);
    assert.equal(p.ok, true, JSON.stringify(p));
    assert.equal(p.wallet.reserved, "1200.00");
    assert.equal(p.wallet.available, "3800.00");
    const r = await g.reject(seller, p.proposal.id);
    assert.equal(r.ok, true, JSON.stringify(r));
    assert.equal((await g.wallet(buyer)).reserved, "0.00000000");
    assert.deepEqual(await g.invariants(), CLEAN);
  });

  it("cancelar pelo comprador libera a reserva; só o comprador cancela", async () => {
    const { seller, buyer, prop } = await setup("can");
    const p = await g.propose(buyer, prop, 1100);
    assert.equal((await g.cancel(seller, p.proposal.id)).error.code, "PROPOSAL_NOT_FOUND");
    assert.equal((await g.accept(buyer, p.proposal.id)).error.code, "PROPOSAL_NOT_FOUND");
    const c = await g.cancel(buyer, p.proposal.id);
    assert.equal(c.ok, true, JSON.stringify(c));
    assert.equal(c.wallet.reserved, "0.00");
    // Cooldown impede nova proposta imediata ao mesmo imóvel.
    assert.equal((await g.propose(buyer, prop, 1150)).error.code, "PROPOSAL_COOLDOWN");
  });

  it("aceitar transfere: comprador paga a reserva, vendedor recebe preço + renda até o instante", async () => {
    const { seller, buyer, prop, ownershipId } = await setup("acc");
    await g.ageOwnership(ownershipId, 12); // 12 h de posse na semana de inauguração → 5,00 + 20,00 de bônus
    const p = await g.propose(buyer, prop, 1300);
    const before = await g.wallet(seller);
    const a = await g.accept(seller, p.proposal.id);
    assert.equal(a.ok, true, JSON.stringify(a));
    const settled = Number(a.income_settled_to_seller);
    assert.ok(settled >= 25 && settled < 25.01, `renda liquidada ${settled}`);
    const after = await g.wallet(seller);
    // Crédito ao vendedor = preço + renda liquidada (8 casas no fechamento).
    assert.equal((Number(after.balance) - Number(before.balance)).toFixed(8), (1300 + Number(a.income_settled_to_seller)).toFixed(8));
    const buyerW = await g.wallet(buyer);
    assert.equal(buyerW.balance, "3700.00000000");
    assert.equal(buyerW.reserved, "0.00000000");
    const own = await g.currentOwnership(prop);
    assert.equal(own.owner_id, buyer);
    assert.equal(own.purchase_price, "1300.00000000");
    assert.deepEqual(await g.invariants(), CLEAN);
  });

  it("valor acima do disponível ou inválido é recusado sem reservar", async () => {
    const { buyer, prop } = await setup("amt");
    assert.equal((await g.propose(buyer, prop, 6000)).error.code, "INSUFFICIENT_FUNDS");
    assert.equal((await g.propose(buyer, prop, 10.555)).error.code, "INVALID_AMOUNT");
    assert.equal((await g.propose(buyer, prop, 0)).error.code, "INVALID_AMOUNT");
    assert.equal((await g.wallet(buyer)).reserved, "0.00000000");
  });

  it("dono não propõe para si; duas propostas abertas ao mesmo imóvel não", async () => {
    const { seller, buyer, prop } = await setup("dup");
    assert.equal((await g.propose(seller, prop, 1200)).error.code, "SELF_PROPOSAL");
    assert.equal((await g.propose(buyer, prop, 1200)).ok, true);
    assert.equal((await g.propose(buyer, prop, 1250)).error.code, "PROPOSAL_EXISTS");
  });

  it("mudança de dono invalida propostas abertas e libera reservas (superseded)", async () => {
    const { seller, buyer, prop } = await setup("sup");
    const other = await g.player("sup_o");
    const pBuyer = await g.propose(buyer, prop, 1200);
    const pOther = await g.propose(other, prop, 1400);
    assert.equal((await g.accept(seller, pOther.proposal.id)).ok, true);
    const [row] = await g.q(`select status from game.proposals where id = $1`, [pBuyer.proposal.id]);
    assert.equal(row.status, "superseded");
    assert.equal((await g.wallet(buyer)).reserved, "0.00000000");
    assert.equal((await g.accept(seller, pBuyer.proposal.id)).error.code, "PROPOSAL_NOT_PENDING");
    assert.deepEqual(await g.invariants(), CLEAN);
  });

  it("proposta vencida: aceitar/recusar/cancelar falham com PROPOSAL_EXPIRED; job libera a reserva", async () => {
    const { seller, buyer, prop } = await setup("exp");
    const p = await g.propose(buyer, prop, 1200);
    await g.expireNow(p.proposal.id);
    assert.equal((await g.accept(seller, p.proposal.id)).error.code, "PROPOSAL_EXPIRED");
    assert.equal((await g.reject(seller, p.proposal.id)).error.code, "PROPOSAL_EXPIRED");
    assert.equal((await g.cancel(buyer, p.proposal.id)).error.code, "PROPOSAL_EXPIRED");
    const [{ n }] = await g.q(`select game_internal.expire_due_proposals() as n`);
    assert.ok(n >= 1);
    const [row] = await g.q(`select status, eligible_until > now() as window_open from game.proposals where id = $1`, [p.proposal.id]);
    assert.equal(row.status, "expired");
    assert.equal(row.window_open, true);
    assert.equal((await g.wallet(buyer)).reserved, "0.00000000");
    assert.deepEqual(await g.invariants(), CLEAN);
  });
});

describe("compra sem aceite (+20%)", () => {
  it("silêncio: antes do prazo PROPOSAL_PENDING; após o prazo compra por max(oferta, 120%)", async () => {
    const { seller, buyer, prop } = await setup("sil");
    const p = await g.propose(buyer, prop, 1100);
    assert.equal(p.proposal.forced_price, "1200.00");
    assert.equal((await g.forced(buyer, prop, "silence", p.proposal.id)).error.code, "PROPOSAL_PENDING");
    await g.expireNow(p.proposal.id);
    const sellerBefore = await g.wallet(seller);
    const f = await g.forced(buyer, prop, "silence", p.proposal.id);
    assert.equal(f.ok, true, JSON.stringify(f));
    assert.equal(f.purchase_price, "1200.00");
    assert.equal((await g.currentOwnership(prop)).owner_id, buyer);
    // Antigo dono recebe mesmo offline.
    const sellerAfter = await g.wallet(seller);
    assert.ok(Number(sellerAfter.balance) - Number(sellerBefore.balance) >= 1200);
    const [row] = await g.q(`select status from game.proposals where id = $1`, [p.proposal.id]);
    assert.equal(row.status, "executed_without_acceptance");
    assert.deepEqual(await g.invariants(), CLEAN);
  });

  it("silêncio: janela vencida → WINDOW_CLOSED; dono que respondeu antes impede", async () => {
    const { seller, buyer, prop } = await setup("win");
    const p = await g.propose(buyer, prop, 1100);
    await g.expireNow(p.proposal.id, 2 * 86400);
    assert.equal((await g.forced(buyer, prop, "silence", p.proposal.id)).error.code, "WINDOW_CLOSED");

    const s2 = await setup("win2");
    const p2 = await g.propose(s2.buyer, s2.prop, 1100);
    assert.equal((await g.reject(s2.seller, p2.proposal.id)).ok, true);
    assert.equal((await g.forced(s2.buyer, s2.prop, "silence", p2.proposal.id)).error.code, "PROPOSAL_NOT_ELIGIBLE");
    assert.ok(seller);
  });

  it("proteção pós-transferência bloqueia; inatividade exige 14 dias sem atividade qualificada", async () => {
    const seller = await g.player("ina_s");
    const buyer = await g.player("ina_b");
    const prop = await g.newProperty(1);
    assert.equal((await g.purchase(seller, prop)).ok, true);
    assert.equal((await g.forced(buyer, prop, "inactivity")).error.code, "PROTECTION_ACTIVE");
    await g.endProtection(prop);
    assert.equal((await g.forced(buyer, prop, "inactivity")).error.code, "OWNER_ACTIVE");
    await g.makeInactive(seller, 15);
    const f = await g.forced(buyer, prop, "inactivity");
    assert.equal(f.ok, true, JSON.stringify(f));
    assert.equal(f.purchase_price, "1200.00");
    // Recém-transferido: protegido contra nova compra forçada.
    const third = await g.player("ina_t");
    assert.equal((await g.forced(third, prop, "inactivity")).error.code, "PROTECTION_ACTIVE");
    assert.deepEqual(await g.invariants(), CLEAN);
  });
});

describe("renda", () => {
  it("1% ao dia proporcional: 12/24/36 h e coleta sem perda sistemática de frações", async () => {
    const owner = await g.player("renda_a");
    const prop = await g.newProperty(1); // 1000 → 10/dia
    const r = await g.purchase(owner, prop);
    // Passa a semana de inauguração (coberta em "bônus de inauguração") para medir só a renda permanente.
    await g.ageOwnership(r.ownership_id, 24 * 7);
    assert.equal((await g.collect(owner, [r.ownership_id])).ok, true);
    let total = 0;
    for (const hours of [12, 12, 12]) {
      await g.ageOwnership(r.ownership_id, hours);
      const c = await g.collect(owner, [r.ownership_id]);
      assert.equal(c.ok, true, JSON.stringify(c));
      total += Number(c.collected);
    }
    // 36 h = 15,00; tolerância de 1 centavo pelo tempo real entre chamadas.
    assert.ok(total >= 15 && total < 15.02, `coletado ${total}`);
    const [o] = await g.q(`select income_credited::text, income_fraction from game.ownerships where id = $1`, [r.ownership_id]);
    assert.ok(Number(o.income_fraction) < 0.01);
    assert.deepEqual(await g.invariants(), CLEAN);
  });

  it("coleta repetida com a mesma chave não credita duas vezes; titularidade alheia é ignorada", async () => {
    const owner = await g.player("renda_b");
    const intruder = await g.player("renda_c");
    const prop = await g.newProperty(1);
    const r = await g.purchase(owner, prop);
    await g.ageOwnership(r.ownership_id, 24);
    const c1 = await g.collect(owner, [r.ownership_id], "col-fixed-key-1");
    const c2 = await g.collect(owner, [r.ownership_id], "col-fixed-key-1");
    assert.equal(c2.replayed, true);
    assert.equal(c2.collected, c1.collected);
    const stolen = await g.collect(intruder, [r.ownership_id]);
    assert.equal(stolen.collected, "0.00");
    assert.deepEqual(stolen.skipped, [r.ownership_id]);
    assert.deepEqual(await g.invariants(), CLEAN);
  });

  it("venda no limite: renda até o instante vai ao vendedor e a do comprador começa do zero", async () => {
    const { seller, buyer, prop, ownershipId } = await setup("lim");
    await g.ageOwnership(ownershipId, 24);
    const p = await g.propose(buyer, prop, 1000);
    const a = await g.accept(seller, p.proposal.id);
    assert.ok(Number(a.income_settled_to_seller) >= 10);
    const [old] = await g.q(`select ended_at is not null as ended, income_fraction from game.ownerships where id = $1`, [ownershipId]);
    assert.equal(old.ended, true);
    const c = await g.collect(seller, [ownershipId]);
    assert.equal(c.collected, "0.00");
    const fresh = await g.collect(buyer, [a.ownership_id]);
    assert.ok(Number(fresh.collected) < 0.01);
  });
});

describe("bônus de inauguração", () => {
  const collected = async (owner, ownershipId) => {
    const c = await g.collect(owner, [ownershipId]);
    assert.equal(c.ok, true, JSON.stringify(c));
    return Number(c.collected);
  };

  it("o seed local publica o GPS simulado por cima do bônus, sem perdê-lo", async () => {
    const [{ cfg }] = await g.q(`select game.cfg() as cfg`);
    assert.equal(cfg.opening_bonus_rate, "0.04");
    assert.equal(cfg.opening_bonus_s, 604800);
    assert.equal(cfg.allow_mock_location, true);
  });

  it("compra do sistema rende +4% ao dia nos 7 primeiros dias; depois, só 1%", async () => {
    const owner = await g.player("inaug_a");
    const prop = await g.newProperty(1); // 1000 → 10/dia + 40/dia na inauguração
    const r = await g.purchase(owner, prop);
    assert.equal(r.ok, true, JSON.stringify(r));
    assert.equal(r.daily_income, "10.00");
    assert.equal(r.opening_bonus.daily, "40.00");
    const own = await g.currentOwnership(prop);
    assert.equal(Number(own.opening_bonus_rate), 0.04);
    // Tolerância: alguns ms reais entre as chamadas e o arredondamento da coleta para baixo em centavos.
    await g.ageOwnership(r.ownership_id, 24);
    const day1 = await collected(owner, r.ownership_id);
    assert.ok(day1 >= 50 && day1 < 50.02, `dia 1: ${day1}`);
    await g.ageOwnership(r.ownership_id, 24 * 6);
    const days2to7 = await collected(owner, r.ownership_id);
    assert.ok(days2to7 >= 299.99 && days2to7 < 300.02, `dias 2 a 7: ${days2to7}`);
    await g.ageOwnership(r.ownership_id, 24);
    const day8 = await collected(owner, r.ownership_id);
    assert.ok(day8 >= 10 && day8 < 10.02, `dia 8: ${day8}`);
    assert.deepEqual(await g.invariants(), CLEAN);
  });

  it("intervalo que cruza o fim da inauguração paga cada parte pela sua taxa", async () => {
    const owner = await g.player("inaug_b");
    const prop = await g.newProperty(1);
    const r = await g.purchase(owner, prop);
    await g.ageOwnership(r.ownership_id, 156); // 6,5 dias: 6,5 × 50
    const first = await collected(owner, r.ownership_id);
    assert.ok(first >= 325 && first < 325.02, `6,5 dias: ${first}`);
    await g.ageOwnership(r.ownership_id, 24); // 12 h com bônus (25) + 12 h sem (5)
    const crossing = await collected(owner, r.ownership_id);
    assert.ok(crossing >= 29.99 && crossing < 30.02, `travessia: ${crossing}`);
  });

  it("compra de outro jogador não tem bônus: o comprador recebe só 1%", async () => {
    const { seller, buyer, prop } = await setup("inaug_c");
    const p = await g.propose(buyer, prop, 1000);
    const a = await g.accept(seller, p.proposal.id);
    assert.equal(a.ok, true, JSON.stringify(a));
    const own = await g.currentOwnership(prop);
    assert.equal(Number(own.opening_bonus_rate), 0);
    assert.equal(own.opening_bonus_until, null);
    const d = await g.api("property_detail", buyer, prop);
    assert.equal(d.mine.opening_bonus, null);
    await g.ageOwnership(a.ownership_id, 24);
    const day1 = await collected(buyer, a.ownership_id);
    assert.ok(day1 >= 10 && day1 < 10.01, `dia 1: ${day1}`);
    assert.deepEqual(await g.invariants(), CLEAN);
  });

  it("ficha, portfólio, carteira e relatório informam o bônus enquanto ele dura", async () => {
    const a = await g.player("inaug_d");
    const prop = await g.newProperty(1);
    let d = await g.api("property_detail", a, prop);
    assert.equal(d.actions.purchase.daily_income, "10.00");
    assert.deepEqual(d.actions.purchase.opening_bonus, { daily: "40.00", seconds: 604800 });
    const r = await g.purchase(a, prop);
    d = await g.api("property_detail", a, prop);
    assert.equal(d.ownership.owner_daily_income, "10.00");
    assert.equal(d.mine.opening_bonus.daily, "40.00");
    assert.equal(d.actions.purchase.opening_bonus, null);
    let p = await g.api("portfolio", a, null, 50);
    assert.equal(p.totals.daily_income, "10.00");
    assert.equal(p.totals.opening_bonus_daily, "40.00");
    assert.equal(p.items[0].opening_bonus.daily, "40.00");

    await g.ageOwnership(r.ownership_id, 24 * 8); // 7 × 50 + 1 × 10
    p = await g.api("portfolio", a, null, 50);
    assert.equal(p.totals.opening_bonus_daily, "0.00");
    assert.equal(p.items[0].opening_bonus, null);
    assert.ok(Number(p.totals.collectable) >= 359.99 && Number(p.totals.collectable) < 360.02, p.totals.collectable);
    const w = await g.api("wallet", a, null, 50);
    assert.equal(w.wallet.collectable_income, p.totals.collectable);
    const [{ rep }] = await g.q("select game_internal.economy_daily_report() as rep");
    assert.match(rep.opening_bonus_run_rate, /^\d+(\.\d+)?$/);
  });
});

describe("conciliação", () => {
  it("sem divergências após os cenários; adulteração de saldo é detectada e congela a carteira", async () => {
    let [{ r }] = await g.q(`select game_internal.reconcile('teste') as r`);
    assert.equal(r.alerts_opened, 0);
    const victim = await g.player("rec_a");
    await g.q(`update game.wallets set balance = balance + 1 where user_id = $1`, [victim]);
    [{ r }] = await g.q(`select game_internal.reconcile('teste') as r`);
    assert.equal(r.alerts_opened, 1);
    assert.equal((await g.wallet(victim)).frozen, true);
    const prop = await g.newProperty(1);
    const buy = await g.purchase(victim, prop);
    assert.equal(buy.error.code, "WALLET_LOCKED");
    // Desfaz a adulteração (o banco pode ser compartilhado com outros arquivos de teste).
    await g.q(`update game.wallets set balance = balance - 1, frozen = false, frozen_reason = null where user_id = $1`, [victim]);
    await g.q(`update game.reconciliation_alerts set status = 'resolved' where user_id = $1`, [victim]);
  });
});
