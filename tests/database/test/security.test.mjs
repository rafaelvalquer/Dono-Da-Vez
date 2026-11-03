// Segurança: alterar user_id, chamar RPC direta, editar carteira, acessar terceiros. Plano de testes §17.
// Todas as tentativas proibidas devem falhar com permissão negada (42501) ou erro de domínio.
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { after, before, describe, it } from "node:test";
import { openDb } from "../lib/db.mjs";
import { DEMO, Game, key } from "../lib/game.mjs";

let db, g, alice, bob;
before(async () => {
  db = await openDb();
  g = new Game(db);
  alice = await g.player("sec_alice");
  bob = await g.player("sec_bob");
});
after(async () => { await db?.close(); });

const denied = (e) => e?.code === "42501";

async function expectDenied(role, sql, params = []) {
  await assert.rejects(g.as(role, () => g.q(sql, params)), denied, `${role}: ${sql}`);
}

describe("papéis da Data API (anon/authenticated/service_role)", () => {
  for (const role of ["anon", "authenticated", "service_role"]) {
    it(`${role} não lê nem altera tabelas do jogo`, async () => {
      await expectDenied(role, "select * from game.wallets");
      await expectDenied(role, "select * from game.ledger_entries");
      await expectDenied(role, "update game.wallets set balance = 999999");
      await expectDenied(role, "select * from geo.source_features");
    });
    it(`${role} não executa funções do jogo, internas ou administrativas`, async () => {
      await expectDenied(role, "select game_api.bootstrap($1, 1)", [alice]);
      await expectDenied(role, "select game_api.purchase_property($1, 'k-12345678', 'h', $2, null)", [alice, DEMO.houseSmall1]);
      await expectDenied(role, "select game.wallet_apply($1, 1000, 0)", [alice]);
      await expectDenied(role, "select game_internal.reconcile()");
      await expectDenied(role, "select admin_api.whoami($1)", [alice]);
      await expectDenied(role, "select geo_api.publish_batch($1)", [randomUUID()]);
    });
  }
});

describe("papéis internos com privilégio mínimo", () => {
  it("dv_backend só executa game_api: sem acesso direto a tabelas nem a funções internas", async () => {
    await expectDenied("dv_backend", "select * from game.wallets");
    await expectDenied("dv_backend", "update game.wallets set balance = balance + 1 where user_id = $1", [alice]);
    await expectDenied("dv_backend", "insert into game.ledger_entries (transaction_id, account_id, amount, entry_type) values ($1, $1, 1, 'income')", [randomUUID()]);
    await expectDenied("dv_backend", "select game.wallet_apply($1, 1000, 0)", [alice]);
    await expectDenied("dv_backend", "select game.grant_initial_balance($1)", [alice]);
    await expectDenied("dv_backend", "select admin_api.whoami($1)", [alice]);
    await expectDenied("dv_backend", "select game_internal.reconcile()");
    await expectDenied("dv_backend", "select geo_api.publish_batch($1)", [randomUUID()]);
  });

  it("dv_admin_backend só executa admin_api", async () => {
    await expectDenied("dv_admin_backend", "select * from game.wallets");
    await expectDenied("dv_admin_backend", "select game_api.bootstrap($1, 1)", [alice]);
    await expectDenied("dv_admin_backend", "select game.wallet_apply($1, 1, 0)", [alice]);
  });

  it("dv_importer nunca toca carteiras, titularidades ou preços", async () => {
    await expectDenied("dv_importer", "select * from game.wallets");
    await expectDenied("dv_importer", "update game.ownerships set purchase_price = 1");
    await expectDenied("dv_importer", "update game.properties set current_ownership_id = null");
    await expectDenied("dv_importer", "select geo_api.approve_batch($1, $2, 'x')", [randomUUID(), alice]);
  });

  it("admin_api exige papel administrativo ativo no banco", async () => {
    const r = g.as("dv_admin_backend", () => g.q("select admin_api.user_detail($1, $2)", [alice, bob]));
    await assert.rejects(r, (e) => e.code === "DV000" && e.message === "FORBIDDEN");
  });
});

describe("integridade estrutural", () => {
  it("RLS habilitada em todas as tabelas de game e geo", async () => {
    const rows = await g.q(`select n.nspname || '.' || c.relname as t from pg_class c join pg_namespace n on n.oid = c.relnamespace
                             where n.nspname in ('game', 'geo') and c.relkind = 'r' and not c.relrowsecurity`);
    assert.deepEqual(rows, []);
  });

  it("toda função SECURITY DEFINER fixa search_path", async () => {
    const rows = await g.q(`select p.oid::regprocedure::text as f from pg_proc p join pg_namespace n on n.oid = p.pronamespace
                             where n.nspname in ('game', 'game_api', 'game_internal', 'admin_api', 'geo', 'geo_api')
                               and p.prosecdef and not exists (select 1 from unnest(coalesce(p.proconfig, '{}')) c where c like 'search_path=%')`);
    assert.deepEqual(rows, []);
  });

  it("nenhuma função de game/geo é executável por PUBLIC", async () => {
    const rows = await g.q(`select p.oid::regprocedure::text as f from pg_proc p join pg_namespace n on n.oid = p.pronamespace
                             where n.nspname in ('game', 'game_api', 'game_internal', 'admin_api', 'geo', 'geo_api')
                               and has_function_privilege('public', p.oid, 'execute')`);
    assert.deepEqual(rows, []);
  });

  it("razão, negociações e configuração são imutáveis (mesmo para o superusuário)", async () => {
    // Gatilhos por linha: garante ao menos uma negociação existente.
    assert.equal((await g.purchase(alice, await g.newProperty(1))).ok, true);
    await assert.rejects(g.q("update game.ledger_entries set amount = amount + 1"));
    await assert.rejects(g.q("delete from game.trades"));
    await assert.rejects(g.q("update game.game_config set note = 'x'"));
  });
});

describe("isolamento entre jogadores", () => {
  it("o ator vem da API: Bob não aceita, recusa nem cancela proposta de outros", async () => {
    const seller = await g.player("sec_seller");
    const prop = await g.newProperty(1);
    assert.equal((await g.purchase(seller, prop)).ok, true);
    await g.endProtection(prop);
    const p = await g.propose(alice, prop, 1100);
    assert.equal((await g.accept(bob, p.proposal.id)).error.code, "PROPOSAL_NOT_FOUND");
    assert.equal((await g.reject(bob, p.proposal.id)).error.code, "PROPOSAL_NOT_FOUND");
    assert.equal((await g.cancel(bob, p.proposal.id)).error.code, "PROPOSAL_NOT_FOUND");
    const list = await g.api("list_proposals", bob, "received", "open", null, 50);
    assert.equal(list.items.length, 0);
    const op = await g.api("get_operation", bob, "nao-existe-12345");
    assert.equal(op.found ?? false, false);
  });

  it("chave de idempotência é por ator: a mesma chave de outro jogador não devolve a resposta dele", async () => {
    const k = key("shared");
    const p1 = await g.newProperty(1);
    const r1 = await g.purchase(alice, p1, { k });
    assert.equal(r1.ok, true);
    const p2 = await g.newProperty(1);
    const r2 = await g.purchase(bob, p2, { k });
    assert.equal(r2.ok, true);
    assert.notEqual(r2.operation_id, r1.operation_id);
  });

  it("detalhe de imóvel não expõe identificadores de outros jogadores", async () => {
    const d = await g.api("property_detail", bob, DEMO.houseSmall2);
    const text = JSON.stringify(d);
    assert.ok(!text.includes(alice));
    const own = await g.newProperty(1);
    assert.equal((await g.purchase(alice, own)).ok, true);
    const d2 = await g.api("property_detail", bob, own);
    assert.ok(!JSON.stringify(d2).includes(alice), "id do dono vazou");
  });
});
