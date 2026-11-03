// Conta (saldo inicial único) e compra inicial (efeito integral ou nenhum). Plano de testes §17.
import assert from "node:assert/strict";
import { after, before, describe, it } from "node:test";
import { openDb } from "../lib/db.mjs";
import { CLEAN, DEMO, Game, key } from "../lib/game.mjs";

let db, g;
before(async () => { db = await openDb(); g = new Game(db); });
after(async () => { await db?.close(); });

describe("conta", () => {
  it("credita o saldo inicial uma única vez, mesmo com bootstrap repetido", async () => {
    const a = await g.player("conta_a");
    await g.api("bootstrap", a, 1);
    await g.api("bootstrap", a, 1);
    assert.equal((await g.wallet(a)).balance, "5000.00000000");
    const [{ n }] = await g.q(`select count(*)::int n from game.ledger_transactions t join game.operations o on o.id = t.operation_id
                                where t.kind = 'initial_grant' and o.actor_id = $1`, [a]);
    assert.equal(n, 1);
  });

  it("não credita antes da confirmação do e-mail; credita depois", async () => {
    const id = await g.player("conta_b", { confirmed: false });
    await g.api("bootstrap", id, 1);
    assert.equal((await g.wallet(id)).balance, "0.00000000");
    await g.q(`update auth.users set email_confirmed_at = now() where id = $1`, [id]);
    await g.api("bootstrap", id, 1);
    assert.equal((await g.wallet(id)).balance, "5000.00000000");
  });

  it("recadastro com o mesmo e-mail não recebe novo saldo inicial", async () => {
    const email = `repetido-${Date.now()}@example.test`;
    const first = await g.player("conta_c", { email });
    assert.equal((await g.wallet(first)).balance, "5000.00000000");
    // Simula exclusão do usuário no Auth e novo cadastro com o mesmo e-mail.
    await g.q(`update auth.users set email = 'apagado-' || id || '@example.test' where id = $1`, [first]);
    const second = await g.player("conta_c2", { email });
    assert.equal((await g.wallet(second)).balance, "0.00000000");
  });

  it("bootstrap informa atualização obrigatória por versão mínima", async () => {
    const a = await g.player("conta_d");
    const r = await g.api("bootstrap", a, 0);
    assert.equal(r.update_required, true);
    assert.ok(r.config.categories.length === 4);
  });
});

describe("compra inicial", () => {
  it("compra válida: titularidade, cobrança e razão balanceado", async () => {
    const a = await g.player("compra_a");
    const r = await g.purchase(a, DEMO.houseSmall1);
    assert.equal(r.ok, true, JSON.stringify(r));
    assert.equal(r.purchase_price, "1000.00");
    assert.equal(r.daily_income, "10.00");
    assert.equal((await g.wallet(a)).balance, "4000.00000000");
    const own = await g.currentOwnership(DEMO.houseSmall1);
    assert.equal(own.owner_id, a);
    assert.deepEqual(await g.invariants(), CLEAN);
  });

  it("segundo comprador recebe PROPERTY_CHANGED (owned) e não é cobrado", async () => {
    const b = await g.player("compra_b");
    const r = await g.purchase(b, DEMO.houseSmall1);
    assert.equal(r.ok, false);
    assert.equal(r.error.code, "PROPERTY_CHANGED");
    assert.equal(r.error.detail.reason, "owned");
    assert.equal((await g.wallet(b)).balance, "5000.00000000");
  });

  it("repetir com a mesma chave devolve a mesma resposta sem cobrar de novo", async () => {
    const a = await g.player("compra_c");
    const prop = await g.newProperty(1);
    const k = key("retry");
    const proof = await g.proofId(a, prop);
    const r1 = await g.purchase(a, prop, { k, proof });
    const r2 = await g.purchase(a, prop, { k, proof });
    assert.equal(r1.ok, true);
    assert.deepEqual(r2, { ...r1, replayed: true });
    assert.equal((await g.wallet(a)).balance, "4000.00000000");
  });

  it("mesma chave com corpo diferente é recusada", async () => {
    const a = await g.player("compra_d");
    const p1 = await g.newProperty(1);
    const p2 = await g.newProperty(1);
    const k = key("reuse");
    assert.equal((await g.purchase(a, p1, { k })).ok, true);
    await assert.rejects(g.purchase(a, p2, { k }), (e) => e.code === "DV000" && e.message === "IDEMPOTENCY_CONFLICT");
  });

  it("sem saldo: INSUFFICIENT_FUNDS e nada muda", async () => {
    const a = await g.player("compra_e");
    const r = await g.purchase(a, DEMO.tower1); // 10.000 > 5.000
    assert.equal(r.ok, false);
    assert.equal(r.error.code, "INSUFFICIENT_FUNDS");
    assert.equal(await g.currentOwnership(DEMO.tower1), undefined);
    assert.equal((await g.wallet(a)).balance, "5000.00000000");
  });

  it("fora do raio: prova recusada com OUT_OF_RANGE", async () => {
    const a = await g.player("compra_f");
    const prop = await g.newProperty(1);
    const r = await g.proof(a, prop, { offsetM: 120 });
    assert.equal(r.ok, false);
    assert.equal(r.error.code, "OUT_OF_RANGE");
    assert.ok(r.error.detail.distance_m > 80);
  });

  it("GPS impreciso, antigo ou simulado (quando proibido) é recusado", async () => {
    const a = await g.player("compra_g");
    const prop = await g.newProperty(1);
    assert.equal((await g.proof(a, prop, { accuracy: 45 })).error.code, "LOCATION_INACCURATE");
    assert.equal((await g.proof(a, prop, { fixAgeMs: 20_000 })).error.code, "LOCATION_STALE");
    assert.equal((await g.proof(a, prop, { speedMps: 10 })).error.code, "SPEED_LIMIT");
    // O seed local permite mock; em homologação/produção a config v1 proíbe.
    const next = `(select max(version) + 1 from game.game_config)`;
    await g.q(`insert into game.game_config (version, values, note)
               select ${next}, (game.cfg() - '_version') || '{"allow_mock_location": false}', 'teste'`);
    try {
      assert.equal((await g.proof(a, prop, { mock: true })).error.code, "LOCATION_SUSPICIOUS");
    } finally {
      // game_config é imutável; uma versão mais nova restaura o comportamento local.
      await g.q(`insert into game.game_config (version, values, note)
                 select ${next}, (game.cfg() - '_version') || '{"allow_mock_location": true}', 'teste: restaura'`);
    }
  });

  it("prova de outro imóvel, de outro jogador, reutilizada ou expirada não serve", async () => {
    const a = await g.player("compra_h");
    const b = await g.player("compra_i");
    const p1 = await g.newProperty(1);
    const p2 = await g.newProperty(1);
    const proofP1 = await g.proofId(a, p1);
    let r = await g.purchase(a, p2, { proof: proofP1 });
    assert.equal(r.error.code, "PRESENCE_REQUIRED");
    r = await g.purchase(b, p1, { proof: proofP1 });
    assert.equal(r.error.code, "PRESENCE_REQUIRED");
    assert.equal((await g.purchase(a, p1, { proof: proofP1 })).ok, true);
    const p3 = await g.newProperty(1);
    const used = await g.proofId(a, p3);
    await g.q(`update game.presence_proofs set expires_at = clock_timestamp() - interval '1 second', recorded_at = clock_timestamp() - interval '40 seconds' where id = $1`, [used]);
    r = await g.purchase(a, p3, { proof: used });
    assert.equal(r.error.code, "LOCATION_STALE");
    r = await g.purchase(a, p1, { proof: proofP1 });
    assert.equal(r.ok, false);
  });

  it("imóvel suspenso ou região não liberada: PROPERTY_UNAVAILABLE / REGION_UNAVAILABLE", async () => {
    const a = await g.player("compra_j");
    const susp = await g.newProperty(1, { eligibility: "suspended" });
    const r = await g.purchase(a, susp);
    assert.equal(r.error.code, "PROPERTY_UNAVAILABLE");
    const prop = await g.newProperty(1);
    const proof = await g.proofId(a, prop);
    await g.q(`update game.regions set release_status = 'paused' where code = 'local-demo'`);
    try {
      const r2 = await g.purchase(a, prop, { proof });
      assert.equal(r2.error.code, "REGION_UNAVAILABLE");
    } finally {
      await g.q(`update game.regions set release_status = 'released' where code = 'local-demo'`);
    }
    assert.equal((await g.wallet(a)).balance, "5000.00000000");
  });

  it("versão esperada desatualizada: PROPERTY_CHANGED", async () => {
    const a = await g.player("compra_k");
    const prop = await g.newProperty(1);
    const r = await g.purchase(a, prop, { expectedVersion: 999 });
    assert.equal(r.error.code, "PROPERTY_CHANGED");
  });

  it("invariantes do razão após todos os cenários", async () => {
    assert.deepEqual(await g.invariants(), CLEAN);
  });
});
