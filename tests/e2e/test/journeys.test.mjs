// Jornadas HTTP completas pela Edge Function `api`: cadastro → saldo inicial → mapa → presença → compra;
// proposta com reserva → aviso ao dono → aceite com transferência; perfil e denúncia.
import assert from "node:assert/strict";
import { after, before, describe, it } from "node:test";
import { newKey } from "../lib/client.mjs";
import { CLEAN, startStack } from "../lib/stack.mjs";

let stack;
before(async () => { stack = await startStack(); }, { timeout: 300_000 });
after(async () => { await stack?.close(); });

const ok = (r, label = "") => assert.ok(r.status === 200 && r.body?.ok !== false, `${label} ${r.status} ${JSON.stringify(r.body)}`);

describe("jornada do jogador", () => {
  it("health responde sem sessão", async () => {
    const r = await stack.api.call("GET", "/v1/health");
    assert.equal(r.status, 200);
    assert.equal(r.body.status, "ok");
    assert.ok(r.headers.get("x-correlation-id"));
  });

  it("cadastro → saldo inicial uma vez → mapa → ficha → presença → compra → carteira, portfólio e histórico", async () => {
    const ana = await stack.player("ana", { bootstrap: false });
    const first = await ana.bootstrap();
    assert.equal(first.wallet.balance, "5000.00");
    assert.equal(first.update_required, false);
    assert.ok(first.regions.some((r) => r.code === "local-demo"));
    assert.equal((await ana.bootstrap()).wallet.balance, "5000.00", "bootstrap repetido não credita de novo");

    const prop = await stack.newProperty(1);
    const map = await ana.get("/v1/map", { bbox: "-46.6930,-23.5590,-46.6870,-23.5530", zoom: 18, limit: 500 });
    assert.equal(map.status, 200, JSON.stringify(map.body));
    assert.equal(map.body.mode, "features");

    const detail = await ana.get(`/v1/properties/${prop.id}`);
    assert.equal(detail.status, 200);
    assert.equal(detail.body.actions.purchase.allowed, true);
    assert.equal(detail.body.actions.purchase.price, "1000.00");

    const near = await ana.get("/v1/nearby", { lat: prop.lat, lon: prop.lon, radius: 80 });
    assert.ok(near.body.items.some((i) => i.id === prop.id || i.property_id === prop.id), "imóvel aparece entre os próximos");

    const k = newKey("buy");
    const buy = await ana.buy(prop, { key: k });
    ok(buy, "compra");
    assert.ok(buy.body.operation_id);

    const wallet = await ana.wallet();
    assert.deepEqual([wallet.balance, wallet.reserved, wallet.available], ["4000.00", "0.00", "4000.00"]);
    const pf = await ana.get("/v1/portfolio");
    assert.equal(pf.body.totals.count, 1);
    assert.equal(pf.body.totals.daily_income, "10.00");
    assert.equal(pf.body.items[0].property.id, prop.id);
    const hist = await ana.get("/v1/history", { kind: "purchases" });
    assert.equal(hist.body.items[0].kind, "purchase");
    const op = await ana.get(`/v1/operations/${k}`);
    assert.equal(op.status, 200);
    assert.equal(op.body.status, "succeeded");

    const again = await ana.get(`/v1/properties/${prop.id}`);
    assert.ok(again.body.mine);
    assert.deepEqual(await stack.invariants(), CLEAN);
  });

  it("proposta reserva saldo, avisa o dono e o aceite transfere com renda liquidada", async () => {
    const seller = await stack.player("vend");
    const buyer = await stack.player("comp");
    const prop = await stack.newProperty(1);
    const bought = await seller.buy(prop);
    ok(bought, "compra do vendedor");
    await stack.endProtection(prop.id);

    const prop1 = await buyer.propose(prop, bought.body.ownership_id, "1100.00");
    ok(prop1, "proposta");
    const proposalId = prop1.body.proposal.id;
    const reserved = await buyer.wallet();
    assert.deepEqual([reserved.balance, reserved.reserved, reserved.available], ["5000.00", "1100.00", "3900.00"]);

    const inbox = await seller.get("/v1/notifications");
    assert.ok(inbox.body.items.some((n) => n.type === "proposal.received"));
    const received = await seller.get("/v1/proposals", { direction: "received", status: "open" });
    assert.deepEqual(received.body.items.map((p) => p.id), [proposalId]);

    const acc = await seller.post(`/v1/proposals/${proposalId}/accept`, {}, { key: newKey("acc") });
    ok(acc, "aceite");

    const sw = await seller.wallet();
    const bw = await buyer.wallet();
    assert.ok(Number(sw.balance) >= 5100 && Number(sw.balance) < 5100.1, `vendedor ${sw.balance}`);
    assert.deepEqual([bw.balance, bw.reserved], ["3900.00", "0.00"]);
    assert.equal((await buyer.get("/v1/portfolio")).body.items[0].property.id, prop.id);
    assert.equal((await seller.get("/v1/portfolio")).body.totals.count, 0);
    assert.equal(await stack.countTrades(prop.id), 2);
    assert.deepEqual(await stack.invariants(), CLEAN);
  });

  it("perfil, avisos lidos e denúncia", async () => {
    const p = await stack.player("perfil");
    const nick = `e2e_${Date.now().toString(36)}`;
    const upd = await p.patch("/v1/profile", { nickname: nick, avatar_id: "avatar_03" });
    assert.equal(upd.status, 200, JSON.stringify(upd.body));
    assert.equal((await p.get("/v1/profile")).body.profile.nickname, nick);
    const read = await p.post("/v1/notifications/read", {});
    assert.equal(read.status, 200);
    const rep = await p.post("/v1/reports", { category: "map_error", message: "Imóvel desenhado no lugar errado" });
    assert.equal(rep.status, 201, JSON.stringify(rep.body));
  });
});
