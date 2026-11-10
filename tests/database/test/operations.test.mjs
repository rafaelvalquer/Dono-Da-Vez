// Leituras do app, jobs, exclusão de conta, console administrativo e importação geográfica.
// Plano de testes §17 (Importação, Falhas, Operação) e checklist §19.
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { after, before, describe, it } from "node:test";
import { openDb } from "../lib/db.mjs";
import { CLEAN, DEMO, Game, key } from "../lib/game.mjs";

let db, g;
before(async () => { db = await openDb(); g = new Game(db); });
after(async () => { await db?.close(); });

describe("leituras do app", () => {
  it("mapa: feições na viewport, clusters de longe, área grande recusada, fora de região indisponível", async () => {
    const a = await g.player("map_a");
    const f = await g.api("map_features", a, -46.6912, -23.5575, -46.6885, -23.5550, 17, null, 50);
    assert.equal(f.mode, "features");
    assert.ok(f.features.length >= 5);
    const props = f.features[0].properties;
    for (const k of ["id", "state", "category", "height_m", "height_source", "lat", "lon"]) assert.ok(k in props, k);
    const c = await g.api("map_features", a, -46.75, -23.60, -46.65, -23.50, 13, null, null);
    assert.equal(c.mode, "clusters");
    assert.ok(c.features.every((x) => x.properties.cluster === true));
    await assert.rejects(g.api("map_features", a, -46.75, -23.60, -46.65, -23.50, 16, null, null),
      (e) => e.message === "AREA_TOO_LARGE");
    const none = await g.api("map_features", a, 10, 10, 10.01, 10.01, 17, null, null);
    assert.equal(none.region_status, "unavailable");
  });

  it("próximos: ordenados por distância ao ponto de interação, dentro do raio", async () => {
    const a = await g.player("near_a");
    const n = await g.api("nearby", a, -23.5562, -46.6905, 80);
    assert.ok(n.items.length >= 1);
    const d = n.items.map((i) => i.distance_m);
    assert.deepEqual(d, [...d].sort((x, y) => x - y));
    assert.ok(d.every((x) => x <= 80));
  });

  it("ficha: ações coerentes para visitante, dono e proponente", async () => {
    const owner = await g.player("det_o");
    const visitor = await g.player("det_v");
    const prop = await g.newProperty(1);
    let d = await g.api("property_detail", visitor, prop);
    assert.equal(d.actions.purchase.allowed, true);
    assert.equal(d.actions.purchase.price, "1000.00");
    await g.purchase(owner, prop);
    d = await g.api("property_detail", visitor, prop);
    assert.equal(d.actions.purchase.reason, "owned");
    assert.equal(d.actions.forced_inactivity.reason, "protection_active");
    d = await g.api("property_detail", owner, prop);
    assert.equal(d.actions.propose.reason, "mine");
    assert.ok(d.mine);
  });

  it("carteira, histórico, portfólio e notificações refletem a compra", async () => {
    const a = await g.player("lists_a");
    const prop = await g.newProperty(2);
    await g.purchase(a, prop);
    const w = await g.api("wallet", a, null, 50);
    assert.equal(w.wallet.balance, "2500.00");
    assert.ok(w.entries.some((e) => e.transaction_kind === "system_purchase"));
    const h = await g.api("history", a, "purchases", null, 50);
    assert.equal(h.items[0].kind, "purchase");
    const p = await g.api("portfolio", a, null, 50);
    assert.equal(p.totals.count, 1);
    assert.equal(p.totals.daily_income, "25.00");
    const n = await g.api("list_notifications", a, null, 50);
    assert.ok(n.unread >= 1);
    const m = await g.api("mark_notifications_read", a, n.items.map((i) => i.id));
    assert.equal(m.updated, n.items.length);
  });

  it("perfil: apelido único (sem diferenciar maiúsculas), avatar e preferências", async () => {
    const a = await g.player("prof_a");
    const b = await g.player("prof_b");
    const r = await g.api("update_profile", a, "Corretor_1", "avatar_03", JSON.stringify({ proposals: false }));
    assert.equal(r.profile.nickname, "Corretor_1");
    assert.equal(r.profile.notification_prefs.proposals, false);
    await assert.rejects(g.api("update_profile", b, "corretor_1", null, null), (e) => e.message === "NICKNAME_TAKEN");
  });

  it("relato gera protocolo DV-000000", async () => {
    const a = await g.player("rep_a");
    const r = await g.api("create_report", a, "map_error", DEMO.houseSmall2, "Imóvel aparece na quadra errada");
    assert.match(r.protocol, /^DV-\d{6}$/);
  });
});

describe("jobs e falhas", () => {
  it("outbox: entrega reivindicada uma vez; falha volta para nova tentativa; token inválido é revogado", async () => {
    const a = await g.player("push_a");
    await g.api("register_device", a, `tok_${"a".repeat(40)}`, "android");
    await g.api("record_activity", a, "foreground");
    const seller = await g.player("push_s");
    const prop = await g.newProperty(1);
    await g.purchase(seller, prop);
    await g.api("register_device", seller, `tok_${"s".repeat(40)}`, "android");
    await g.endProtection(prop);
    await g.propose(a, prop, 1100);
    const claim = async () => g.as("dv_backend", async () => (await g.one("select game_api.outbox_claim(50, 60) as r")).r);
    const first = await claim();
    const mine = first.filter((e) => e.user_id === seller);
    assert.ok(mine.length >= 1);
    assert.equal((await claim()).filter((e) => e.event_id === mine[0].event_id).length, 0, "não reivindica de novo durante o lease");
    await g.as("dv_backend", () => g.q("select game_api.outbox_complete($1, false, 'timeout', $2)", [mine[0].id, [`tok_${"s".repeat(40)}`]]));
    const [tok] = await g.q("select revoked_at is not null as revoked from game.device_tokens where user_id = $1", [seller]);
    assert.equal(tok.revoked, true);
    // Push é aviso: a central de notificações (fonte de verdade) já registrou o evento.
    const n = await g.api("list_notifications", seller, null, 50);
    assert.ok(n.items.some((i) => i.type === "proposal.received"));
  });

  it("job de expiração é idempotente e cron atrasado não credita/duplica nada", async () => {
    const seller = await g.player("job_s");
    const buyer = await g.player("job_b");
    const prop = await g.newProperty(1);
    await g.purchase(seller, prop);
    await g.endProtection(prop);
    const p = await g.propose(buyer, prop, 1100);
    await g.expireNow(p.proposal.id, 3600); // cron "atrasado" 1 h
    await g.q("select game_internal.expire_due_proposals()");
    await g.q("select game_internal.expire_due_proposals()");
    const [row] = await g.q("select status, closed_at = expires_at as closed_at_deadline from game.proposals where id = $1", [p.proposal.id]);
    assert.equal(row.status, "expired");
    assert.equal(row.closed_at_deadline, true, "encerramento registrado no prazo, não na execução do job");
    const [{ n }] = await g.q("select count(*)::int n from game.wallet_holds where proposal_id = $1 and status = 'released'", [p.proposal.id]);
    assert.equal(n, 1);
    assert.deepEqual(await g.invariants(), CLEAN);
  });

  it("limpeza de presença e relatório econômico rodam sem erro", async () => {
    const [{ r: purge }] = await g.q("select game_internal.purge_presence() as r");
    assert.ok("coords_purged" in purge);
    const [{ r: eco }] = await g.q("select game_internal.economy_daily_report(current_date) as r");
    assert.ok(Number(eco.total_issued) > 0);
    assert.ok(eco.top10_income_share <= 1);
  });
});

describe("exclusão de conta", () => {
  it("cancela propostas enviadas, devolve imóveis ao sistema e encerra saldo sem quebrar o razão", async () => {
    const leaving = await g.player("del_a");
    const seller = await g.player("del_s");
    const keepProp = await g.newProperty(1);
    await g.purchase(seller, keepProp);
    await g.endProtection(keepProp);
    const owned = await g.newProperty(1);
    await g.purchase(leaving, owned);
    await g.propose(leaving, keepProp, 1100);
    const r = await g.api("request_account_deletion", leaving, key("del"), "h");
    assert.equal(r.ok, true, JSON.stringify(r));
    assert.equal((await g.wallet(leaving)).reserved, "0.00000000");
    let done = false;
    for (let i = 0; i < 5 && !done; i++) {
      const [{ r: job }] = await g.q("select game_internal.process_account_deletions(20) as r");
      done = job.user_id === leaving && job.closed;
    }
    assert.ok(done);
    assert.equal((await g.currentOwnership(owned)), undefined);
    const [prof] = await g.q("select status, nickname from game.profiles where id = $1", [leaving]);
    assert.equal(prof.status, "deleted");
    assert.match(prof.nickname, /^removido_/);
    assert.equal((await g.wallet(leaving)).balance, "0.00000000");
    const [{ n }] = await g.q("select count(*)::int n from game.outbox where topic = 'auth.delete_user' and user_id = $1", [leaving]);
    assert.equal(n, 1);
    assert.deepEqual(await g.invariants(), CLEAN);
  });
});

describe("console administrativo", () => {
  let admin, target;
  const call = (sql, params) => g.as("dv_admin_backend", async () => (await g.one(`select ${sql} as r`, params)).r);
  const h = (s) => createHash("sha256").update(s).digest("hex");

  before(async () => {
    admin = await g.player("adm_root");
    target = await g.player("adm_target");
    await g.q("insert into game.admin_roles (user_id, role, granted_by) values ($1, 'superadmin', $1)", [admin]);
  });

  it("leituras: whoami, ops, busca, detalhe de jogador e imóvel, auditoria", async () => {
    assert.deepEqual((await call("admin_api.whoami($1)", [admin])).roles, ["superadmin"]);
    const ops = await call("admin_api.ops_metrics($1)", [admin]);
    assert.ok("alerts_open" in ops);
    const s = await call("admin_api.search($1, $2)", [admin, "adm_target"]);
    assert.equal(s.users[0].id, target);
    const u = await call("admin_api.user_detail($1, $2)", [admin, target]);
    assert.equal(u.wallet.balance, "5000.00");
    const p = await call("admin_api.property_detail($1, $2)", [admin, DEMO.houseSmall2]);
    assert.equal(p.property.id, DEMO.houseSmall2);
    for (const fn of ["list_review_cases($1)", "list_proposals($1)", "list_alerts($1)", "list_risk_reviews($1)",
                      "list_import_batches($1)", "list_regions($1)", "list_reports($1)", "economy_reports($1)", "config_versions($1)"]) {
      await call(`admin_api.${fn}`, [admin]);
    }
  });

  it("ajuste compensatório: idempotente, auditado e lançado no razão", async () => {
    const k = key("adj");
    const r1 = await call("admin_api.compensating_adjustment($1, $2, $3, $4, $5::numeric, $6, null)", [admin, k, h("adj"), target, "25.50", "compensação por erro de mapa"]);
    assert.equal(r1.ok, true, JSON.stringify(r1));
    const r2 = await call("admin_api.compensating_adjustment($1, $2, $3, $4, $5::numeric, $6, null)", [admin, k, h("adj"), target, "25.50", "compensação por erro de mapa"]);
    assert.equal(r2.replayed, true);
    assert.equal((await g.wallet(target)).balance, "5025.50000000");
    const [{ n }] = await g.q("select count(*)::int n from game.admin_audit where action like 'wallet.adjust%' and target_id = $1", [target]);
    assert.equal(n, 1);
    assert.deepEqual(await g.invariants(), CLEAN);
  });

  it("suspender elegibilidade bloqueia compra; motivo curto é recusado", async () => {
    const prop = await g.newProperty(1);
    await assert.rejects(call("admin_api.set_property_eligibility($1, $2, $3, $4, 'suspended', 'x')", [admin, key("el"), h("el1"), prop]));
    const r = await call("admin_api.set_property_eligibility($1, $2, $3, $4, 'suspended', $5)", [admin, key("el"), h("el2"), prop, "entrada em área privada"]);
    assert.equal(r.ok, true, JSON.stringify(r));
    assert.equal((await g.purchase(target, prop)).error.code, "PROPERTY_UNAVAILABLE");
  });

  it("nova versão de parâmetros valida chaves e tipos e é imutável", async () => {
    await assert.rejects(call("admin_api.publish_config($1, $2::jsonb, null, 'teste')", [admin, JSON.stringify({ chave_nova: 1 })]),
      (e) => e.message === "VALIDATION_FAILED");
    await assert.rejects(call("admin_api.publish_config($1, $2::jsonb, null, 'teste')", [admin, JSON.stringify({ interaction_radius_m: "90" })]),
      (e) => e.message === "VALIDATION_FAILED");
    const r = await call("admin_api.publish_config($1, $2::jsonb, null, 'raio maior em teste')", [admin, JSON.stringify({ heartbeat_min_interval_s: 90 })]);
    assert.ok(r.version > 2);
    const [{ v }] = await g.q("select (game.cfg()->>'heartbeat_min_interval_s')::int as v");
    assert.equal(v, 90);
  });

  it("zerar o bônus de inauguração numa nova versão vale só para compras novas", async () => {
    const buyer = await g.player("cfg_bonus");
    const earlier = await g.newProperty(1);
    const first = await g.purchase(buyer, earlier);
    assert.equal(first.opening_bonus.daily, "40.00");
    const off = await call("admin_api.publish_config($1, $2::jsonb, null, 'sem bônus em teste')", [admin, JSON.stringify({ opening_bonus_rate: "0" })]);
    assert.equal(off.values.opening_bonus_rate, "0");
    const second = await g.purchase(buyer, await g.newProperty(1));
    assert.equal(second.ok, true, JSON.stringify(second));
    assert.equal(second.opening_bonus, null);
    // A compra anterior mantém o bônus fixado na titularidade.
    const d = await g.api("property_detail", buyer, earlier);
    assert.equal(d.mine.opening_bonus.daily, "40.00");
    await call("admin_api.publish_config($1, $2::jsonb, null, 'bônus de volta')", [admin, JSON.stringify({ opening_bonus_rate: "0.04" })]);
  });

  it("conciliação sob demanda e suspensão de jogador", async () => {
    const rec = await call("admin_api.run_reconciliation($1)", [admin]);
    assert.equal(rec.alerts_opened, 0);
    const s = await call("admin_api.set_user_status($1, $2, 'suspended', 'teste de suspensão')", [admin, target]);
    assert.equal(s.status, "suspended");
    const prop = await g.newProperty(1);
    const r = await g.purchase(target, prop, { proof: null });
    assert.equal(r.ok, false);
  });
});

describe("importação geográfica", () => {
  const region = { code: "test-geo", name: "Região de teste (fictícia)" };
  const poly = { type: "Polygon", coordinates: [[[-46.70, -23.57], [-46.69, -23.57], [-46.69, -23.56], [-46.70, -23.56], [-46.70, -23.57]]] };
  const items = [
    { ref: "osm:way:1001", lon: -46.6950, lat: -23.5650, cat: "house_small" },
    { ref: "osm:way:1002", lon: -46.6940, lat: -23.5650, cat: "commercial" },
    { ref: "osm:node:1003", lon: -46.6930, lat: -23.5650, cat: "house_large" },
  ];

  // Mesmo formato de SourceRef.to_json() do importador (services/geo-importer).
  const sourceRef = (ref) => {
    const [provider, objectType, id] = ref.split(":");
    return { provider, object_type: objectType, source_id: Number(id), source_version: 1, role: "primary" };
  };

  async function importBatch(sourceHash, rows) {
    return g.as("dv_importer", async () => {
      const b = (await g.one("select geo_api.begin_batch($1, 'osm', 'https://example.test/extract.pbf', $2, $3, 1) as r",
        [region.code, sourceHash.slice(0, 12), sourceHash])).r;
      if (b.skipped) return b;
      for (const it of rows) {
        await g.q(`insert into geo.staging_properties (batch_id, canonical_ref, source_refs, category_code, classification_status,
                     classification_confidence, address_label, point, footprint, interaction_point, interaction_quality, height_m,
                     height_source, visual_model, eligibility)
                   values ($1, $2, $3, $4, 'resolved', 0.9, null,
                     extensions.st_setsrid(extensions.st_makepoint($5, $6), 4326),
                     extensions.st_multi(extensions.st_buffer(extensions.st_setsrid(extensions.st_makepoint($5, $6), 4326), 0.00005, 'endcap=square')),
                     extensions.st_setsrid(extensions.st_makepoint($5, $6 - 0.00005), 4326), 'derived_edge', 6, 'stylized', $4, 'eligible')`,
          [b.batch_id, it.ref, JSON.stringify([sourceRef(it.ref)]), it.cat, it.lon, it.lat]);
      }
      await g.q("select geo_api.complete_staging($1, '{}'::jsonb, '{}'::jsonb)", [b.batch_id]);
      const v = (await g.one("select geo_api.validate_batch($1) as r", [b.batch_id])).r;
      const p = v.status === "approved" ? (await g.one("select geo_api.publish_batch($1) as r", [b.batch_id])).r : null;
      return { ...b, validation: v, publication: p };
    });
  }

  const count = async () => (await g.one(`select count(*)::int n from game.properties p join game.regions r on r.id = p.region_id
                                           where r.code = $1 and p.eligibility <> 'removed_from_source'`, [region.code])).n;

  it("lote publicado cria imóveis; mesmo arquivo de novo é ignorado; reimportação não duplica", async () => {
    await g.as("dv_importer", () => g.q("select geo_api.upsert_region($1, $2, $3::jsonb)", [region.code, region.name, JSON.stringify(poly)]));
    const b1 = await importBatch("a".repeat(64), items);
    assert.equal(b1.validation.status, "approved", JSON.stringify(b1.validation));
    assert.equal(await count(), 3);
    const again = await importBatch("a".repeat(64), items);
    assert.equal(again.skipped, true);
    const b2 = await importBatch("b".repeat(64), items);
    assert.equal(b2.validation.diff.new, 0);
    assert.equal(b2.validation.diff.matched, 3);
    assert.equal(await count(), 3);
  });

  it("imóvel com dono que some da fonte não perde titularidade: suspenso + caso de revisão", async () => {
    await g.q("update game.regions set release_status = 'released' where code = $1", [region.code]);
    const owner = await g.player("geo_owner");
    const [target] = await g.q(`select p.id from game.properties p join geo.source_features sf on sf.property_id = p.id
                                 where sf.object_type = 'way' and sf.source_id = 1001`);
    assert.equal((await g.purchase(owner, target.id)).ok, true);
    const walletBefore = await g.wallet(owner);
    // Remoção de 1/3 > 5%: lote fica em validated e exige aprovação humana antes de publicar.
    const b3 = await importBatch("c".repeat(64), items.slice(1));
    assert.equal(b3.validation.status, "validated");
    await g.q("select geo_api.approve_batch($1, null, 'revisado')", [b3.batch_id]);
    await g.as("dv_importer", () => g.q("select geo_api.publish_batch($1)", [b3.batch_id]));
    const own = await g.currentOwnership(target.id);
    assert.equal(own.owner_id, owner);
    const [prop] = await g.q("select eligibility from game.properties where id = $1", [target.id]);
    assert.equal(prop.eligibility, "suspended");
    const [{ n }] = await g.q("select count(*)::int n from geo.review_cases where property_id = $1 and reason = 'removed_owned'", [target.id]);
    assert.equal(n, 1);
    assert.deepEqual(await g.wallet(owner), walletBefore);
    assert.deepEqual(await g.invariants(), CLEAN);
  });
});

describe("importação geográfica: categoria estimada e anexos (regras v2)", () => {
  const region = { code: "test-geo-v2", name: "Região de teste v2 (fictícia)" };
  const poly = { type: "Polygon", coordinates: [[[-46.68, -23.57], [-46.67, -23.57], [-46.67, -23.56], [-46.68, -23.56], [-46.68, -23.57]]] };
  const ref = (k, role = "primary") => {
    const [provider, objectType, id] = k.split(":");
    return { provider, object_type: objectType, source_id: Number(id), source_version: 1, role };
  };
  const undefinedRow = (k, lon) => ({ k, lon, cat: null, status: "undefined", elig: "pending_classification", refs: [ref(k)] });

  async function stage(sourceHash, rows) {
    return g.as("dv_importer", async () => {
      const b = (await g.one("select geo_api.begin_batch($1, 'osm', 'https://example.test/extract.pbf', $2, $3, 2) as r",
        [region.code, sourceHash.slice(0, 12), sourceHash])).r;
      for (const it of rows) {
        await g.q(`insert into geo.staging_properties (batch_id, canonical_ref, source_refs, category_code, classification_status,
                     classification_confidence, point, footprint, interaction_point, interaction_quality, height_m,
                     height_source, visual_model, eligibility, eligibility_reason)
                   values ($1, $2, $3, $4, $5, 0.55,
                     extensions.st_setsrid(extensions.st_makepoint($6, -23.565), 4326),
                     extensions.st_multi(extensions.st_buffer(extensions.st_setsrid(extensions.st_makepoint($6, -23.565), 4326), 0.00005, 'endcap=square')),
                     extensions.st_setsrid(extensions.st_makepoint($6, -23.56505), 4326), 'derived_edge', 5, 'source',
                     coalesce($4, 'generic'), $7, case when $7 = 'eligible' then null else $5 end)`,
          [b.batch_id, it.k, JSON.stringify(it.refs), it.cat, it.status, it.lon, it.elig]);
      }
      await g.q("select geo_api.complete_staging($1, '{}'::jsonb, '{}'::jsonb)", [b.batch_id]);
      return { ...b, validation: (await g.one("select geo_api.validate_batch($1) as r", [b.batch_id])).r };
    });
  }
  const publish = (batch) => g.as("dv_importer", async () => (await g.one("select geo_api.publish_batch($1) as r", [batch])).r);
  const propOf = async (sourceId) => (await g.q(`select p.*, sf.role from game.properties p join geo.source_features sf on sf.property_id = p.id
                                                 where sf.object_type = 'way' and sf.source_id = $1`, [sourceId]))[0];

  it("categoria estimada é negociável; anexo sem dono vira parte do vizinho; anexo com dono vai para revisão", async () => {
    await g.as("dv_importer", () => g.q("select geo_api.upsert_region($1, $2, $3::jsonb)", [region.code, region.name, JSON.stringify(poly)]));
    // Lote v1: casa e anexos ainda indefinidos; uma casa declarada (2005) e seu anexo (2004) já negociáveis.
    const a = await stage("d".repeat(64), [
      undefinedRow("osm:way:2001", -46.6790), undefinedRow("osm:way:2002", -46.6789),
      { k: "osm:way:2004", lon: -46.6750, cat: "house_small", status: "resolved", elig: "eligible", refs: [ref("osm:way:2004")] },
      { k: "osm:way:2005", lon: -46.6749, cat: "house_small", status: "resolved", elig: "eligible", refs: [ref("osm:way:2005")] },
    ]);
    assert.equal(a.validation.status, "approved", JSON.stringify(a.validation));
    await publish(a.batch_id);
    await g.q("update game.regions set release_status = 'released' where code = $1", [region.code]);
    const house = await propOf(2001), annex = await propOf(2002), ownedAnnex = await propOf(2004);
    assert.equal(house.eligibility, "pending_classification");
    const owner = await g.player("geo_v2_owner");
    assert.equal((await g.purchase(owner, ownedAnnex.id)).ok, true);
    const walletBefore = await g.wallet(owner);

    // Lote v2: 2001 vira casa estimada com o anexo 2002; 2005 tenta absorver 2004, que tem dono.
    const b = await stage("e".repeat(64), [
      { k: "osm:way:2001", lon: -46.6790, cat: "house_small", status: "inferred", elig: "eligible",
        refs: [ref("osm:way:2001"), ref("osm:way:2002", "part")] },
      { k: "osm:way:2005", lon: -46.6749, cat: "house_small", status: "resolved", elig: "eligible",
        refs: [ref("osm:way:2005"), ref("osm:way:2004", "part")] },
    ]);
    assert.equal(b.validation.diff.absorbed, 1);
    assert.equal(b.validation.diff.removed, 0);
    assert.equal(b.validation.diff.merge_candidates, 1);   // só o anexo com dono
    assert.equal(b.validation.status, "validated");        // fusão com dono exige aprovação humana
    await g.q("select geo_api.approve_batch($1, null, 'revisado')", [b.batch_id]);
    const pub = await publish(b.batch_id);
    assert.equal(pub.absorbed, 1);

    // Casa estimada: mesmo UUID, agora negociável; o anexo aponta para ela como "part".
    const houseAfter = await propOf(2001);
    assert.equal(houseAfter.id, house.id);
    assert.deepEqual([houseAfter.classification_status, houseAfter.eligibility], ["inferred", "eligible"]);
    const annexRef = await propOf(2002);
    assert.deepEqual([annexRef.id, annexRef.role], [house.id, "part"]);
    const [old] = await g.q("select eligibility, eligibility_reason from game.properties where id = $1", [annex.id]);
    assert.deepEqual([old.eligibility, old.eligibility_reason], ["removed_from_source", "anexo juntado a outro imóvel"]);
    const [ch] = await g.q("select after from geo.property_changes where property_id = $1 and change_kind = 'absorbed'", [annex.id]);
    assert.equal(ch.after.absorbed_into, house.id);

    // Anexo com dono: nada muda na posse, na carteira nem no imóvel; abre caso de fusão.
    assert.equal((await g.currentOwnership(ownedAnnex.id)).owner_id, owner);
    assert.equal((await propOf(2004)).id, ownedAnnex.id);
    assert.equal((await propOf(2004)).eligibility, "eligible");
    const [{ n }] = await g.q(`select count(*)::int n from geo.review_cases where batch_id = $1 and reason = 'merge'`, [b.batch_id]);
    assert.equal(n, 1);
    assert.deepEqual(await g.wallet(owner), walletBefore);

    // Categoria estimada pode ser comprada como qualquer outra.
    const buyer = await g.player("geo_v2_buyer");
    assert.equal((await g.purchase(buyer, house.id)).ok, true);
    assert.deepEqual(await g.invariants(), CLEAN);
  });

  it("restrição do banco: estimada sem categoria continua fora do mercado", async () => {
    const [{ id }] = await g.q("select id from game.properties where classification_status = 'inferred' limit 1");
    await assert.rejects(g.q("update game.properties set category_id = null where id = $1", [id]));
  });
});
