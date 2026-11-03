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
    await g.ageOwnership(ownershipId, 12); // 12 h de posse → 5,00 de renda
    const p = await g.propose(buyer, prop, 1300);
    const before = await g.wallet(seller);
    const a = await g.accept(seller, p.proposal.id);
    assert.equal(a.ok, true, JSON.stringify(a));
    const settled = Number(a.income_settled_to_seller);
    assert.ok(settled >= 5 && settled < 5.01, `renda liquidada ${settled}`);
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
