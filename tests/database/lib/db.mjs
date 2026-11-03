// Banco de teste: PGlite (Postgres em WASM, com PostGIS) aplicando as migrações reais, ou um Postgres
// real (ex.: `supabase start` no CI) via DV_TEST_DATABASE_URL — nunca um projeto de produção.
import { readFileSync, readdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
export const repoRoot = join(here, "..", "..", "..");
const migrationsDir = join(repoRoot, "supabase", "migrations");

export function migrationFiles() {
  return readdirSync(migrationsDir).filter((f) => f.endsWith(".sql")).sort();
}

/** Erro de domínio (SQLSTATE DV000): MESSAGE = código estável, HINT = mensagem. */
export function domainCode(e) {
  return e?.code === "DV000" ? e.message : undefined;
}

class PgliteDb {
  constructor(pg) { this.pg = pg; this.kind = "pglite"; }
  async query(sql, params = []) {
    const r = await this.pg.query(sql, params);
    return r.rows;
  }
  async exec(sql) { await this.pg.exec(sql); }
  async close() { await this.pg.close(); }
}

class PgDb {
  constructor(client) { this.client = client; this.kind = "postgres"; }
  async query(sql, params = []) {
    const r = await this.client.query(sql, params);
    return r.rows;
  }
  async exec(sql) { await this.client.query(sql); }
  async close() { await this.client.end(); }
}

async function createPglite() {
  const { PGlite } = await import("@electric-sql/pglite");
  const { pgcrypto } = await import("@electric-sql/pglite/contrib/pgcrypto");
  const { postgis } = await import("@electric-sql/pglite-postgis");
  const pg = await PGlite.create({ extensions: { postgis, pgcrypto } });
  const db = new PgliteDb(pg);
  await db.exec(readFileSync(join(here, "supabase-shim.sql"), "utf8"));
  for (const f of migrationFiles()) {
    try {
      await db.exec(readFileSync(join(migrationsDir, f), "utf8"));
    } catch (e) {
      e.message = `${f}: ${e.message}`;
      throw e;
    }
  }
  await db.exec(readFileSync(join(repoRoot, "supabase", "seed.sql"), "utf8"));
  return db;
}

async function createPg(url) {
  if (/supabase\.co|pooler\.supabase/i.test(url) && !process.env.DV_TEST_ALLOW_REMOTE) {
    throw new Error("DV_TEST_DATABASE_URL aponta para um projeto remoto; testes só rodam em banco local/descartável");
  }
  const { default: pg } = await import("pg");
  const client = new pg.Client({ connectionString: url });
  await client.connect();
  return new PgDb(client);
}

/** Abre o banco de teste já migrado (PGlite: migra do zero a cada chamada). */
export async function openDb() {
  const url = process.env.DV_TEST_DATABASE_URL;
  return url ? createPg(url) : createPglite();
}

/** Conexão adicional para testes de concorrência (somente Postgres real). */
export async function extraConnection() {
  const url = process.env.DV_TEST_DATABASE_URL;
  if (!url) return null;
  return createPg(url);
}
