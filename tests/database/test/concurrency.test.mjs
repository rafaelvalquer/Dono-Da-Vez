// Concorrência real (duas conexões): dois compradores, retry com a mesma chave, aceitar vs cancelar,
// coleta simultânea. PGlite tem uma única conexão, então este arquivo exige Postgres real
// (DV_TEST_DATABASE_URL, ex.: `supabase start` no CI) e é pulado sem ele.
import assert from "node:assert/strict";
import { after, before, describe, it } from "node:test";
import { extraConnection, openDb } from "../lib/db.mjs";
import { CLEAN, Game, key } from "../lib/game.mjs";

const enabled = Boolean(process.env.DV_TEST_DATABASE_URL);
let db, c1, c2, g, g1, g2;

before(async () => {
  if (!enabled) return;
  db = await openDb();
  c1 = await extraConnection();
  c2 = await extraConnection();
  g = new Game(db);
  g1 = new Game(c1);
  g2 = new Game(c2);
});
after(async () => {
  await Promise.all([db?.close(), c1?.close(), c2?.close()]);
});

describe("concorrência", { skip: enabled ? false : "requer DV_TEST_DATABASE_URL (Postgres real)" }, () => {
  it("dois compradores simultâneos: uma titularidade e uma cobrança", async () => {
    const a = await g.player("cc_a");
    const b = await g.player("cc_b");
    const prop = await g.newProperty(1);
    const [pa, pb] = [await g.proofId(a, prop), await g.proofId(b, prop)];
    const results = await Promise.all([g1.purchase(a, prop, { proof: pa }), g2.purchase(b, prop, { proof: pb })]);
    assert.equal(results.filter((r) => r.ok).length, 1, JSON.stringify(results));
    const loser = results.find((r) => !r.ok);
    assert.equal(loser.error.code, "PROPERTY_CHANGED");
    const [{ n }] = await g.q("select count(*)::int n from game.trades where property_id = $1", [prop]);
    assert.equal(n, 1);
    const balances = [await g.wallet(a), await g.wallet(b)].map((w) => w.balance).sort();
    assert.deepEqual(balances, ["4000.00000000", "5000.00000000"]);
    assert.deepEqual(await g.invariants(), CLEAN);
  });

  it("mesma chave em duas conexões: a segunda espera e devolve o resultado da primeira", async () => {
    const a = await g.player("cc_key");
    const prop = await g.newProperty(1);
    const proof = await g.proofId(a, prop);
    const k = key("same");
    const [r1, r2] = await Promise.all([g1.purchase(a, prop, { k, proof }), g2.purchase(a, prop, { k, proof })]);
    assert.equal(r1.operation_id, r2.operation_id);
    assert.equal((await g.wallet(a)).balance, "4000.00000000");
  });

  it("aceitar vs cancelar ao mesmo tempo: exatamente um vence e a reserva fecha", async () => {
    for (let i = 0; i < 5; i++) {
      const seller = await g.player(`cc_s${i}`);
      const buyer = await g.player(`cc_b${i}`);
      const prop = await g.newProperty(1);
      await g.purchase(seller, prop);
      await g.endProtection(prop);
      const p = await g.propose(buyer, prop, 1100);
      const [acc, can] = await Promise.all([g1.accept(seller, p.proposal.id), g2.cancel(buyer, p.proposal.id)]);
      assert.equal([acc, can].filter((r) => r.ok).length, 1, JSON.stringify([acc, can]));
      const [h] = await g.q("select status from game.wallet_holds where proposal_id = $1", [p.proposal.id]);
      assert.equal(h.status, acc.ok ? "consumed" : "released");
    }
    assert.deepEqual(await g.invariants(), CLEAN);
  });

  it("coleta simultânea com chaves diferentes não credita a mesma renda duas vezes", async () => {
    const a = await g.player("cc_inc");
    const prop = await g.newProperty(1);
    const r = await g.purchase(a, prop);
    await g.ageOwnership(r.ownership_id, 24);
    const [x, y] = await Promise.all([g1.collect(a, [r.ownership_id]), g2.collect(a, [r.ownership_id])]);
    const total = Number(x.collected) + Number(y.collected);
    assert.ok(total >= 10 && total < 10.01, `total ${total}`);
    assert.deepEqual(await g.invariants(), CLEAN);
  });
});
