// Falhas de rede (plano de testes §17 "Falhas"): a resposta se perde depois do commit, o cliente desiste por
// timeout, o app repete com a mesma Idempotency-Key. Nenhum caso pode cobrar, reservar ou transferir duas vezes.
import assert from "node:assert/strict";
import { after, before, describe, it } from "node:test";
import { newKey } from "../lib/client.mjs";
import { startFaultProxy } from "../lib/proxy.mjs";
import { CLEAN, startStack } from "../lib/stack.mjs";

let stack, proxy, flaky;
before(async () => {
  stack = await startStack();
  proxy = await startFaultProxy(stack.apiUrl);
  flaky = stack.api.withBase(proxy.baseUrl);
}, { timeout: 300_000 });
after(async () => {
  await proxy?.close();
  await stack?.close();
});

describe("falhas de rede e retry idempotente", () => {
  it("compra: conexão cai depois do commit; o retry com a mesma chave devolve o resultado gravado e cobra uma vez", async () => {
    const p = await stack.player("drop_buy");
    const prop = await stack.newProperty(1);
    const proofId = await p.proofId(prop);
    const k = newKey("buy");

    const committed = proxy.arm({ method: "POST", path: "/v1/purchases", action: "drop" });
    await assert.rejects(p.via(flaky).buy(prop, { key: k, proofId }), "cliente não recebe resposta");
    const server = await committed;
    assert.equal(server.status, 200);
    assert.equal(server.body.ok, true);

    const retry = await p.buy(prop, { key: k, proofId });
    assert.equal(retry.status, 200, JSON.stringify(retry.body));
    assert.equal(retry.body.replayed, true);
    assert.equal(retry.body.operation_id, server.body.operation_id);
    assert.equal((await p.wallet()).balance, "4000.00");
    assert.equal(await stack.countTrades(prop.id), 1);
    assert.deepEqual(await stack.invariants(), CLEAN);
  });

  it("compra: timeout do cliente com o servidor já confirmado; /v1/operations/:key recupera o resultado", async () => {
    const p = await stack.player("slow_buy");
    const prop = await stack.newProperty(1);
    const proofId = await p.proofId(prop);
    const k = newKey("buy");

    const committed = proxy.arm({ method: "POST", path: "/v1/purchases", action: "delay", ms: 2000 });
    await assert.rejects(p.via(flaky).buy(prop, { key: k, proofId, signal: AbortSignal.timeout(300) }));
    await committed;

    const op = await p.get(`/v1/operations/${k}`);
    assert.equal(op.status, 200);
    assert.equal(op.body.found, true);
    assert.equal(op.body.status, "succeeded");
    assert.equal(op.body.response.ok, true);

    const retry = await p.buy(prop, { key: k, proofId });
    assert.equal(retry.body.replayed, true);
    assert.equal((await p.wallet()).balance, "4000.00");
  });

  it("mesma chave com outro conteúdo é recusada e não executa nada", async () => {
    const p = await stack.player("conflict");
    const a = await stack.newProperty(1);
    const b = await stack.newProperty(1);
    const k = newKey("buy");
    assert.equal((await p.buy(a, { key: k })).status, 200);
    const r = await p.buy(b, { key: k });
    assert.equal(r.body.error.code, "IDEMPOTENCY_CONFLICT", JSON.stringify(r.body));
    assert.ok(r.status >= 400);
    assert.equal((await p.wallet()).balance, "4000.00");
    assert.equal(await stack.countTrades(b.id), 0);
  });

  it("proposta: resposta perdida; o retry não cria segunda reserva", async () => {
    const seller = await stack.player("drop_ps");
    const buyer = await stack.player("drop_pb");
    const prop = await stack.newProperty(1);
    const bought = await seller.buy(prop);
    await stack.endProtection(prop.id);
    const proofId = await buyer.proofId(prop);
    const k = newKey("prop");

    const committed = proxy.arm({ method: "POST", path: "/v1/proposals", action: "drop" });
    await assert.rejects(buyer.via(flaky).propose(prop, bought.body.ownership_id, "1200.00", { key: k, proofId }));
    const server = await committed;
    assert.equal(server.body.ok, true, JSON.stringify(server.body));

    const retry = await buyer.propose(prop, bought.body.ownership_id, "1200.00", { key: k, proofId });
    assert.equal(retry.body.replayed, true);
    assert.equal(retry.body.proposal.id, server.body.proposal.id);
    const w = await buyer.wallet();
    assert.equal(w.reserved, "1200.00");
    const sent = await buyer.get("/v1/proposals", { direction: "sent", status: "open" });
    assert.equal(sent.body.items.length, 1);
  });

  it("aceite: resposta perdida; o retry não transfere de novo", async () => {
    const seller = await stack.player("drop_as");
    const buyer = await stack.player("drop_ab");
    const prop = await stack.newProperty(1);
    const bought = await seller.buy(prop);
    await stack.endProtection(prop.id);
    const proposal = await buyer.propose(prop, bought.body.ownership_id, "1100.00");
    const id = proposal.body.proposal.id;
    const k = newKey("acc");

    const committed = proxy.arm({ method: "POST", path: `/v1/proposals/${id}/accept`, action: "drop" });
    await assert.rejects(seller.via(flaky).post(`/v1/proposals/${id}/accept`, {}, { key: k }));
    assert.equal((await committed).body.ok, true);

    const retry = await seller.post(`/v1/proposals/${id}/accept`, {}, { key: k });
    assert.equal(retry.status, 200);
    assert.equal(retry.body.replayed, true);
    // Nova chave para a mesma intenção: a proposta já foi aceita, então é recusada sem efeito.
    const fresh = await seller.post(`/v1/proposals/${id}/accept`, {}, { key: newKey("acc") });
    assert.equal(fresh.body.ok, false);
    assert.equal(await stack.countTrades(prop.id), 2);
    const bw = await buyer.wallet();
    assert.deepEqual([bw.balance, bw.reserved], ["3900.00", "0.00"]);
    assert.deepEqual(await stack.invariants(), CLEAN);
  });

  it("coleta de renda: resposta perdida; o retry não credita duas vezes", async () => {
    const p = await stack.player("drop_col");
    const prop = await stack.newProperty(1);
    const bought = await p.buy(prop);
    const own = bought.body.ownership_id;
    await stack.admin(`update game.ownerships set acquired_at = acquired_at - interval '24 hours',
                         income_checkpoint_at = income_checkpoint_at - interval '24 hours' where id = '${own}'`);
    const k = newKey("col");

    const committed = proxy.arm({ method: "POST", path: "/v1/income/collect", action: "drop" });
    await assert.rejects(p.via(flaky).post("/v1/income/collect", { ownership_ids: [own] }, { key: k }));
    const server = await committed;
    assert.equal(server.body.ok, true, JSON.stringify(server.body));

    const retry = await p.post("/v1/income/collect", { ownership_ids: [own] }, { key: k });
    assert.equal(retry.body.replayed, true);
    const balance = Number((await p.wallet()).balance);
    assert.ok(balance >= 4010 && balance < 4010.01, `saldo ${balance}`);
    assert.deepEqual(await stack.invariants(), CLEAN);
  });
});
