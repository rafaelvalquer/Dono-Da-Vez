// Aplica todas as migrações + seed em um PGlite vazio e informa o resultado.
import { migrationFiles, openDb } from "./db.mjs";

const t0 = Date.now();
const db = await openDb();
const [{ n }] = await db.query("select count(*)::int as n from pg_proc p join pg_namespace s on s.oid = p.pronamespace where s.nspname in ('game','game_api','game_internal','admin_api','geo','geo_api')");
console.log(`ok: ${migrationFiles().length} migrações + seed aplicadas (${db.kind}), ${n} funções, ${Date.now() - t0} ms`);
await db.close();
