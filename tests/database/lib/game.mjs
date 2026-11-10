// Atalhos de teste sobre as funções reais (game_api.*), chamadas com o papel dv_backend — o mesmo da
// Edge Function. Manipulações de relógio (deslocar timestamps) são feitas como superusuário e só existem aqui.
import { createHash, randomUUID } from "node:crypto";

export const DEMO = {
  houseSmall1: "22222222-0000-4000-8000-000000000001",
  houseSmall2: "22222222-0000-4000-8000-000000000002",
  houseLarge: "22222222-0000-4000-8000-000000000003",
  commercial1: "22222222-0000-4000-8000-000000000004",
  commercial2: "22222222-0000-4000-8000-000000000005",
  tower1: "22222222-0000-4000-8000-000000000006",
  tower2: "22222222-0000-4000-8000-000000000007",
};

export const key = (label = "t") => `${label}-${randomUUID()}`;
const hash = (s) => createHash("sha256").update(s).digest("hex");

let seq = 0;

export class Game {
  constructor(db) {
    this.db = db;
  }

  async q(sql, params) {
    return this.db.query(sql, params);
  }

  async one(sql, params) {
    const rows = await this.db.query(sql, params);
    return rows[0];
  }

  /** Executa `fn` com outro papel na mesma sessão (como a conexão da Edge Function). */
  async as(role, fn) {
    if (!/^[a-z_]+$/.test(role)) throw new Error("papel inválido");
    await this.db.exec(`set role ${role}`);
    try {
      return await fn();
    } finally {
      await this.db.exec("reset role");
    }
  }

  /** Chama game_api.<fn>(...args) como dv_backend e devolve o jsonb. */
  async api(fn, ...args) {
    const ph = args.map((_, i) => `$${i + 1}`).join(", ");
    return this.as("dv_backend", async () => (await this.one(`select game_api.${fn}(${ph}) as r`, args)).r);
  }

  /** Cadastro pelo Auth (trigger cria perfil/carteira) + bootstrap (credita o saldo inicial). */
  async player(nick, { confirmed = true, email } = {}) {
    seq += 1;
    const mail = email ?? `${nick.toLowerCase()}.${seq}.${randomUUID().slice(0, 8)}@example.test`;
    const row = await this.one(
      `insert into auth.users (id, email, email_confirmed_at, raw_user_meta_data) values (gen_random_uuid(), $1, $2, $3) returning id`,
      [mail, confirmed ? new Date().toISOString() : null, JSON.stringify({ nickname: nick })],
    );
    if (confirmed) await this.api("bootstrap", row.id, 1);
    return row.id;
  }

  /** Imóvel extra no polígono da região demo local (fixture determinística; nunca fora do banco de teste). */
  async newProperty(categoryId = 1, { eligibility = "eligible" } = {}) {
    seq += 1;
    const lon = -46.6925 + (seq % 18) * 0.0003;
    const lat = -23.5585 + Math.floor(seq / 18) * 0.0003;
    const row = await this.one(
      `insert into game.properties (region_id, category_id, classification_status, classification_confidence, address_label,
                                    point, footprint, interaction_point, interaction_quality, eligibility)
       values ('11111111-1111-4111-8111-111111111111', $1, 'resolved', 0.9, $2,
               extensions.st_setsrid(extensions.st_makepoint($3, $4), 4326),
               extensions.st_multi(extensions.st_buffer(extensions.st_setsrid(extensions.st_makepoint($3, $4), 4326), 0.00005, 'endcap=square')),
               extensions.st_setsrid(extensions.st_makepoint($3, $4 - 0.00005), 4326), 'derived_edge', $5)
       returning id`,
      [categoryId, `Teste ${seq} (fictício)`, lon, lat, eligibility],
    );
    return row.id;
  }

  async interactionPoint(propertyId) {
    return this.one(
      `select extensions.st_y(interaction_point) as lat, extensions.st_x(interaction_point) as lon from game.properties where id = $1`,
      [propertyId],
    );
  }

  /**
   * Prova de presença. Por padrão "envelhece" provas anteriores do jogador (rate limit e velocidade
   * entre amostras não interferem em testes que não tratam disso).
   */
  async proof(actor, propertyId, { offsetM = 0, accuracy = 5, fixAgeMs = 1000, speedMps = 0, mock = false, fresh = true } = {}) {
    if (fresh) {
      // recorded_at só recua (expires_at > recorded_at continua válido; provas não usadas seguem válidas).
      await this.q(`update game.presence_proofs set recorded_at = recorded_at - interval '2 hours' where actor_id = $1`, [actor]);
    }
    const p = await this.interactionPoint(propertyId);
    const lat = p.lat + offsetM / 111_320;
    return this.api("create_presence_proof", actor, propertyId, lat, p.lon, accuracy, fixAgeMs, speedMps,
      `nonce-${randomUUID()}`, mock, null);
  }

  async proofId(actor, propertyId, opts) {
    const r = await this.proof(actor, propertyId, opts);
    if (!r.ok) throw new Error(`prova recusada: ${JSON.stringify(r.error)}`);
    return r.proof_id;
  }

  async purchase(actor, propertyId, { k = key("buy"), proof, expectedVersion = null } = {}) {
    const proofId = proof === undefined ? await this.proofId(actor, propertyId) : proof;
    return this.api("purchase_property", actor, k, hash(`buy:${propertyId}:${k}`), propertyId, proofId, expectedVersion);
  }

  async propose(actor, propertyId, amount, { k = key("prop"), ownershipId = null, proof } = {}) {
    const proofId = proof === undefined ? await this.proofId(actor, propertyId) : proof;
    return this.api("create_proposal", actor, k, hash(`prop:${propertyId}:${amount}:${k}`), propertyId, ownershipId, amount, proofId);
  }

  async accept(actor, proposalId, k = key("acc")) {
    return this.api("accept_proposal", actor, k, hash(`acc:${proposalId}`), proposalId, null);
  }

  async reject(actor, proposalId, k = key("rej")) {
    return this.api("reject_proposal", actor, k, hash(`rej:${proposalId}`), proposalId, null);
  }

  async cancel(actor, proposalId, k = key("can")) {
    return this.api("cancel_proposal", actor, k, hash(`can:${proposalId}`), proposalId, null);
  }

  async forced(actor, propertyId, reason, proposalId = null, { k = key("frc"), proof } = {}) {
    const proofId = proof === undefined ? await this.proofId(actor, propertyId) : proof;
    return this.api("forced_purchase", actor, k, hash(`frc:${propertyId}:${reason}:${k}`), propertyId, reason, proposalId, proofId, null);
  }

  async collect(actor, ownershipIds, k = key("col")) {
    return this.api("collect_income", actor, k, hash(`col:${ownershipIds.join(",")}`), ownershipIds);
  }

  async wallet(user) {
    return this.one(`select balance::text, reserved::text, (balance - reserved)::text as available, frozen from game.wallets where user_id = $1`, [user]);
  }

  async currentOwnership(propertyId) {
    return this.one(`select o.* from game.properties p join game.ownerships o on o.id = p.current_ownership_id where p.id = $1`, [propertyId]);
  }

  // --- Relógio simulado -----------------------------------------------------------------------
  /** Recua a renda acumulada de uma titularidade em `hours` (equivale a passar o tempo, inclusive na inauguração). */
  async ageOwnership(ownershipId, hours) {
    await this.q(`update game.ownerships set acquired_at = acquired_at - make_interval(hours => $2::int),
                    income_checkpoint_at = income_checkpoint_at - make_interval(hours => $2::int),
                    opening_bonus_until = opening_bonus_until - make_interval(hours => $2::int) where id = $1`, [ownershipId, hours]);
  }

  /** Faz a proposta vencer agora (prazo de resposta esgotado). */
  async expireNow(proposalId, secondsAgo = 1) {
    await this.q(`update game.proposals set created_at = clock_timestamp() - make_interval(secs => $2::int) - interval '24 hours',
                    expires_at = clock_timestamp() - make_interval(secs => $2::int) where id = $1`, [proposalId, secondsAgo]);
  }

  async endProtection(propertyId) {
    await this.q(`update game.properties set last_transfer_at = last_transfer_at - interval '2 days' where id = $1`, [propertyId]);
  }

  async makeInactive(user, days = 15) {
    await this.q(`update game.player_activity set last_qualified_activity_at = clock_timestamp() - make_interval(days => $2::int) where user_id = $1`, [user, days]);
  }

  // --- Invariantes -----------------------------------------------------------------------------
  /** Saldo = razão, reservado = reservas ativas, soma global zero, um dono por imóvel. */
  async invariants() {
    const wallets = await this.q(`
      with led as (select account_id, sum(amount) s from game.ledger_entries group by account_id),
           hol as (select user_id, sum(amount) s from game.wallet_holds where status = 'active' group by user_id)
      select w.user_id from game.wallets w left join led on led.account_id = w.account_id left join hol on hol.user_id = w.user_id
       where w.balance <> coalesce(led.s, 0) or w.reserved <> coalesce(hol.s, 0) or w.balance < 0 or w.reserved < 0 or w.balance < w.reserved`);
    const [{ total }] = await this.q(`select (coalesce(sum(amount), 0) = 0) as total from game.ledger_entries`);
    const txUnbalanced = await this.q(`select transaction_id from game.ledger_entries group by transaction_id having sum(amount) <> 0`);
    const multiOwner = await this.q(`select property_id from game.ownerships where ended_at is null group by property_id having count(*) > 1`);
    const pointer = await this.q(`select p.id from game.properties p where p.current_ownership_id is distinct from
                                   (select o.id from game.ownerships o where o.property_id = p.id and o.ended_at is null)`);
    const orphanHolds = await this.q(`select h.id from game.wallet_holds h join game.proposals p on p.id = h.proposal_id
                                       where h.status = 'active' and p.status <> 'pending'`);
    return { wallets: wallets.length, total, txUnbalanced: txUnbalanced.length, multiOwner: multiOwner.length,
             pointer: pointer.length, orphanHolds: orphanHolds.length };
  }
}

export const CLEAN = { wallets: 0, total: true, txUnbalanced: 0, multiOwner: 0, pointer: 0, orphanHolds: 0 };
