// Seleção da pilha: emulada (padrão, sem Docker) ou local do Supabase (`supabase start` + `functions serve`)
// quando DV_E2E_API_URL está definido. Em ambos os modos os testes falam HTTP com a Edge Function `api` real;
// SQL privilegiado existe só para fixtures e relógio simulado, sempre em banco local descartável.
import { randomInt, randomUUID } from "node:crypto";
import { Api, Player } from "./client.mjs";
import { lit, startEmulatedStack } from "./emulator.mjs";

const LOOPBACK = new Set(["127.0.0.1", "localhost", "[::1]"]);

function localUrl(name) {
  const v = process.env[name];
  if (!v) throw new Error(`${name} é obrigatório quando DV_E2E_API_URL está definido`);
  const host = new URL(v).hostname;
  if (!LOOPBACK.has(host)) {
    throw new Error(`${name} aponta para ${host}: e2e só roda contra a pilha local, nunca homologação ou produção`);
  }
  return v.replace(/\/$/, "");
}

async function startLocalStack() {
  const apiUrl = localUrl("DV_E2E_API_URL");
  const supabaseUrl = localUrl("DV_E2E_SUPABASE_URL");
  const dbUrl = localUrl("DV_E2E_DATABASE_URL");
  const publishable = process.env.DV_E2E_PUBLISHABLE_KEY;
  const serviceKey = process.env.DV_E2E_SERVICE_ROLE_KEY;
  if (!publishable || !serviceKey) throw new Error("DV_E2E_PUBLISHABLE_KEY e DV_E2E_SERVICE_ROLE_KEY (de `supabase status -o env`) são obrigatórios");
  const { default: pg } = await import("pg");
  const client = new pg.Client({ connectionString: dbUrl });
  await client.connect();
  const keyHeaders = (k) => (k.startsWith("sb_") ? { apikey: k } : { apikey: k, authorization: `Bearer ${k}` });

  return {
    mode: "local",
    apiUrl,
    logs: () => [],
    async admin(statement) {
      const r = await client.query(statement);
      return (Array.isArray(r) ? r.at(-1) : r).rows;
    },
    async createUser({ email, nickname }) {
      const password = `e2e-${randomUUID()}`;
      // Auth admin da pilha LOCAL: cria o usuário já confirmado sem disparar e-mail (limite de envio do Auth).
      let r = await fetch(`${supabaseUrl}/auth/v1/admin/users`, {
        method: "POST",
        headers: { ...keyHeaders(serviceKey), "content-type": "application/json" },
        body: JSON.stringify({ email, password, email_confirm: true, user_metadata: { nickname } }),
      });
      if (!r.ok) throw new Error(`Auth admin ${r.status}: ${await r.text()}`);
      const user = await r.json();
      // O token do jogador vem do login normal com a chave publicável, como no app.
      r = await fetch(`${supabaseUrl}/auth/v1/token?grant_type=password`, {
        method: "POST",
        headers: { ...keyHeaders(publishable), "content-type": "application/json" },
        body: JSON.stringify({ email, password }),
      });
      if (!r.ok) throw new Error(`login ${r.status}: ${await r.text()}`);
      return { userId: user.id, token: (await r.json()).access_token };
    },
    token: undefined, // tokens customizados só no modo emulado
    async close() {
      await client.end();
    },
  };
}

let seq = 0;

/** Pilha pronta para os testes, com atalhos de fixture comuns aos dois modos. */
export async function startStack() {
  const base = process.env.DV_E2E_API_URL ? await startLocalStack() : await startEmulatedStack();
  const api = new Api(base.apiUrl);
  return Object.assign(base, {
    api,

    /** Cadastro confirmado + bootstrap (credita o saldo inicial). */
    async player(nickname, { bootstrap = true } = {}) {
      seq += 1;
      const email = `${nickname}.${seq}.${randomUUID().slice(0, 8)}@example.test`;
      const { userId, token } = await base.createUser({ email, nickname });
      const p = new Player(api, { userId, token, email, nickname });
      if (bootstrap) await p.bootstrap();
      return p;
    },

    /** Imóvel fictício extra dentro do polígono da região `local-demo` (só existe no banco de teste). */
    async newProperty(categoryId = 1) {
      const cell = randomInt(0, 18 * 17);
      const lon = -46.6925 + (cell % 18) * 0.0003;
      const lat = -23.5585 + Math.floor(cell / 18) * 0.0003;
      const [row] = await base.admin(`
        insert into game.properties (region_id, category_id, classification_status, classification_confidence, address_label,
                                     point, footprint, interaction_point, interaction_quality, eligibility)
        values ('11111111-1111-4111-8111-111111111111', ${lit(categoryId)}, 'resolved', 0.9, ${lit(`E2E ${randomUUID().slice(0, 8)} (fictício)`)},
                extensions.st_setsrid(extensions.st_makepoint(${lon}, ${lat}), 4326),
                extensions.st_multi(extensions.st_buffer(extensions.st_setsrid(extensions.st_makepoint(${lon}, ${lat}), 4326), 0.00005, 'endcap=square')),
                extensions.st_setsrid(extensions.st_makepoint(${lon}, ${lat - 0.00005}), 4326), 'derived_edge', 'eligible')
        returning id`);
      return { id: row.id, lat: lat - 0.00005, lon };
    },

    /** Encerra a proteção de 24 h após a última transferência (relógio simulado). */
    async endProtection(propertyId) {
      await base.admin(`update game.properties set last_transfer_at = last_transfer_at - interval '2 days' where id = ${lit(propertyId)}`);
    },

    async countTrades(propertyId) {
      const [r] = await base.admin(`select count(*)::int as n from game.trades where property_id = ${lit(propertyId)}`);
      return r.n;
    },

    /** Mesmas invariantes de tests/database: carteiras = razão, transações balanceadas, um dono por imóvel. */
    async invariants() {
      const [r] = await base.admin(`
        select
          (select count(*)::int from game.wallets w
             left join (select account_id, sum(amount) s from game.ledger_entries group by account_id) led on led.account_id = w.account_id
             left join (select user_id, sum(amount) s from game.wallet_holds where status = 'active' group by user_id) hol on hol.user_id = w.user_id
            where w.balance <> coalesce(led.s, 0) or w.reserved <> coalesce(hol.s, 0) or w.balance < w.reserved) as wallets,
          (select count(*)::int from (select 1 from game.ledger_entries group by transaction_id having sum(amount) <> 0) t) as unbalanced,
          (select count(*)::int from (select 1 from game.ownerships where ended_at is null group by property_id having count(*) > 1) t) as multi_owner`);
      return r;
    },
  });
}

export const CLEAN = { wallets: 0, unbalanced: 0, multi_owner: 0 };
