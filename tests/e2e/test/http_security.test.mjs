// Segurança pela borda HTTP (plano de testes §17 "Segurança"): sem sessão, token forjado ou vencido, user_id
// no corpo, mutação sem Idempotency-Key, acesso a dados de terceiros, limite de taxa e reautenticação.
import assert from "node:assert/strict";
import { createHmac, randomUUID } from "node:crypto";
import { after, before, describe, it } from "node:test";
import { newKey } from "../lib/client.mjs";
import { startStack } from "../lib/stack.mjs";

let stack;
before(async () => { stack = await startStack(); }, { timeout: 300_000 });
after(async () => { await stack?.close(); });

function forged(sub) {
  const enc = (o) => Buffer.from(JSON.stringify(o)).toString("base64url");
  const now = Math.floor(Date.now() / 1000);
  const head = `${enc({ alg: "HS256", typ: "JWT" })}.${enc({ sub, role: "authenticated", aud: "authenticated", iat: now, exp: now + 600 })}`;
  return `${head}.${createHmac("sha256", "segredo-que-o-servidor-nao-conhece-0123456789").update(head).digest("base64url")}`;
}

const emulatedOnly = () => (process.env.DV_E2E_API_URL ? "tokens customizados só no modo emulado" : false);

describe("autenticação", () => {
  it("sem sessão, token inválido ou forjado: 401 sem efeito", async () => {
    const victim = await stack.player("vitima");
    for (const token of [undefined, "abc.def.ghi", forged(victim.userId)]) {
      const r = await stack.api.call("GET", "/v1/wallet", { token });
      assert.equal(r.status, 401, `token ${token}`);
      assert.equal(r.body.error.code, "UNAUTHORIZED");
    }
    const prop = await stack.newProperty(1);
    const r = await stack.api.call("POST", "/v1/purchases", { token: forged(victim.userId), body: { property_id: prop.id, proof_id: randomUUID() }, key: newKey() });
    assert.equal(r.status, 401);
    assert.equal((await victim.wallet()).balance, "5000.00");
  });

  it("token vencido ou sem papel authenticated: 401", { skip: emulatedOnly() }, async () => {
    const p = await stack.player("venc");
    const expired = stack.token(p.userId, { iat: Math.floor(Date.now() / 1000) - 7200, ttl: 3600 });
    const r = await stack.api.call("GET", "/v1/wallet", { token: expired });
    assert.equal(r.status, 401);
    assert.match(r.body.error.message, /expirada/);
    const anon = await stack.api.call("GET", "/v1/wallet", { token: stack.token(p.userId, { role: "anon" }) });
    assert.equal(anon.status, 401);
  });

  it("exclusão de conta exige login recente (amr), não apenas token renovado", { skip: emulatedOnly() }, async () => {
    const p = await stack.player("reauth");
    // Token renovado agora (iat recente), mas a última autenticação com senha foi há 1 h.
    const token = stack.token(p.userId, { authTime: Math.floor(Date.now() / 1000) - 3600 });
    const r = await stack.api.call("POST", "/v1/account/deletion", { token, body: { confirm: "EXCLUIR" }, key: newKey("del") });
    assert.equal(r.status, 401);
    assert.equal(r.body.error.code, "REAUTH_REQUIRED");
    const [row] = await stack.admin(`select status from game.profiles where id = '${p.userId}'`);
    assert.equal(row.status, "active", "conta segue ativa");
  });
});

describe("o ator vem do JWT", () => {
  it("user_id de outro jogador no corpo é ignorado: a compra é de quem está autenticado", async () => {
    const victim = await stack.player("alvo");
    const attacker = await stack.player("atac");
    const prop = await stack.newProperty(1);
    const proof_id = await attacker.proofId(prop);
    const r = await attacker.post("/v1/purchases", { property_id: prop.id, proof_id, user_id: victim.userId, actor_id: victim.userId }, { key: newKey() });
    assert.equal(r.status, 200, JSON.stringify(r.body));
    assert.equal((await victim.wallet()).balance, "5000.00");
    assert.equal((await victim.get("/v1/portfolio")).body.totals.count, 0);
    assert.equal((await attacker.wallet()).balance, "4000.00");
  });

  it("prova de presença de outro jogador não serve", async () => {
    const a = await stack.player("prova_a");
    const b = await stack.player("prova_b");
    const prop = await stack.newProperty(1);
    const proofOfA = await a.proofId(prop);
    const r = await b.buy(prop, { proofId: proofOfA });
    assert.equal(r.body.ok, false);
    assert.equal((await b.wallet()).balance, "5000.00");
  });

  it("terceiros: não age sobre proposta alheia, não lê operação alheia, ficha não expõe ids", async () => {
    const seller = await stack.player("ter_s");
    const buyer = await stack.player("ter_b");
    const intruder = await stack.player("ter_i");
    const prop = await stack.newProperty(1);
    const bought = await seller.buy(prop, { key: newKey("seller") });
    await stack.endProtection(prop.id);
    const k = newKey("prop");
    const proposal = await buyer.propose(prop, bought.body.ownership_id, "1100.00", { key: k });
    const id = proposal.body.proposal.id;

    for (const action of ["accept", "reject", "cancel"]) {
      const r = await intruder.post(`/v1/proposals/${id}/${action}`, {}, { key: newKey(action) });
      assert.equal(r.status, 404, action);
      assert.equal(r.body.error.code, "PROPOSAL_NOT_FOUND");
    }
    assert.equal((await intruder.get(`/v1/operations/${k}`)).status, 404);
    assert.equal((await intruder.get("/v1/proposals", { direction: "received" })).body.items.length, 0);

    const detail = await intruder.get(`/v1/properties/${prop.id}`);
    const text = JSON.stringify(detail.body);
    assert.ok(!text.includes(seller.userId) && !text.includes(buyer.userId), "ficha vazou id de jogador");
  });
});

describe("contrato da borda", () => {
  it("mutação econômica sem Idempotency-Key é recusada antes de tocar o banco", async () => {
    const p = await stack.player("semchave");
    const prop = await stack.newProperty(1);
    const proof_id = await p.proofId(prop);
    const r = await p.post("/v1/purchases", { property_id: prop.id, proof_id });
    assert.equal(r.status, 422);
    assert.equal(r.body.error.code, "VALIDATION_FAILED");
    assert.equal((await p.wallet()).balance, "5000.00");
  });

  it("validação, rota inexistente, método errado e corpo grande", async () => {
    const p = await stack.player("contrato");
    assert.equal((await p.post("/v1/purchases", { property_id: "x" }, { key: newKey() })).status, 422);
    assert.equal((await p.post("/v1/proposals", { property_id: randomUUID(), ownership_id: randomUUID(), amount: "10.001", proof_id: randomUUID() }, { key: newKey() })).status, 422);
    assert.equal((await p.get("/v1/nao-existe")).status, 404);
    assert.equal((await p.api.call("DELETE", "/v1/wallet", { token: p.token })).status, 405);
    const big = await p.post("/v1/reports", { category: "bug", message: "x".repeat(20_000) });
    assert.equal(big.status, 413);
  });

  it("limite de taxa por jogador devolve 429 com retry_after_s", async () => {
    const p = await stack.player("taxa");
    let limited;
    for (let i = 0; i < 40 && !limited; i++) {
      const r = await p.patch("/v1/profile", { avatar_id: "avatar_01" });
      if (r.status === 429) limited = r;
    }
    assert.ok(limited, "esperava 429 dentro de 40 mutações por minuto");
    assert.equal(limited.body.error.code, "RATE_LIMITED");
    assert.ok(limited.body.error.detail.retry_after_s > 0);
  });
});
