// Pilha emulada para rodar a Edge Function `api` real sem Docker: PGlite com as migrações e o seed, exposto
// por pglite-socket; Deno executando supabase/functions/api/index.ts; tokens HS256 assinados aqui como faz o
// Auth local. Serve só para teste: tudo escuta em 127.0.0.1 e nada aponta para serviços remotos.
import { spawn } from "node:child_process";
import { createHmac, randomBytes, randomUUID } from "node:crypto";
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { repoRoot } from "./paths.mjs";

const migrationsDir = join(repoRoot, "supabase", "migrations");
const ISSUER = "http://127.0.0.1/auth/v1";

function jwtHs256(payload, secret) {
  const enc = (o) => Buffer.from(JSON.stringify(o)).toString("base64url");
  const head = `${enc({ alg: "HS256", typ: "JWT" })}.${enc(payload)}`;
  return `${head}.${createHmac("sha256", secret).update(head).digest("base64url")}`;
}

/** Claims no formato do Supabase Auth (amr marca o login, usado na reautenticação recente). */
export function authClaims(userId, { iat = Math.floor(Date.now() / 1000), ttl = 3600, aal = "aal1", authTime = iat, role = "authenticated", issuer = ISSUER } = {}) {
  return {
    iss: issuer, aud: "authenticated", sub: userId, role, iat, exp: iat + ttl, aal,
    amr: [{ method: "password", timestamp: authTime }], session_id: randomUUID(),
  };
}

export function signToken(claims, secret) {
  return jwtHs256(claims, secret);
}

async function createDatabase() {
  const { PGlite } = await import("@electric-sql/pglite");
  const { pgcrypto } = await import("@electric-sql/pglite/contrib/pgcrypto");
  const { postgis } = await import("@electric-sql/pglite-postgis");
  const pg = await PGlite.create({ extensions: { postgis, pgcrypto } });
  const files = [
    join(repoRoot, "tests", "database", "lib", "supabase-shim.sql"),
    ...readdirSync(migrationsDir).filter((f) => f.endsWith(".sql")).sort().map((f) => join(migrationsDir, f)),
    join(repoRoot, "supabase", "seed.sql"),
  ];
  for (const f of files) {
    try {
      await pg.exec(readFileSync(f, "utf8"));
    } catch (e) {
      e.message = `${f}: ${e.message}`;
      throw e;
    }
  }
  // O multiplexador do pglite-socket compartilha uma única sessão e ignora o usuário do login: a sessão fica
  // com o papel da Edge Function (dv_backend), o mesmo privilégio mínimo de produção.
  await pg.exec("set role dv_backend");
  return pg;
}

function startDeno(env) {
  const bin = process.env.DENO_BIN || "deno";
  const entry = join(repoRoot, "supabase", "functions", "api", "index.ts");
  const child = spawn(bin, ["run", "--no-prompt", "--allow-net", "--allow-env", "--allow-read", "--allow-sys", entry], {
    cwd: join(repoRoot, "supabase", "functions"),
    env: { ...process.env, ...env, DENO_SERVE_ADDRESS: "tcp:127.0.0.1:0", NO_COLOR: "1" },
    stdio: ["ignore", "pipe", "pipe"],
  });
  const logs = [];
  const url = new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`Deno não iniciou em 180 s:\n${logs.join("\n")}`)), 180_000);
    const onLine = (chunk) => {
      for (const line of String(chunk).split(/\r?\n/).filter(Boolean)) {
        logs.push(line);
        if (logs.length > 500) logs.shift();
        const m = /Listening on (http:\/\/[^\s/]+)/.exec(line);
        if (m) { clearTimeout(timer); resolve(m[1]); }
      }
    };
    child.stdout.on("data", onLine);
    child.stderr.on("data", onLine);
    child.on("error", (e) => { clearTimeout(timer); reject(new Error(`não foi possível executar "${bin}" (defina DENO_BIN): ${e.message}`)); });
    child.on("exit", (code) => { clearTimeout(timer); reject(new Error(`Deno saiu com código ${code}:\n${logs.join("\n")}`)); });
  });
  return { child, url, logs };
}

export async function startEmulatedStack() {
  const { PGLiteSocketServer } = await import("@electric-sql/pglite-socket");
  const pg = await createDatabase();
  const server = new PGLiteSocketServer({ db: pg, port: 0, host: "127.0.0.1", maxConnections: 8 });
  await server.start();
  const dbAddr = server.getServerConn();
  const secret = randomBytes(32).toString("hex");

  const deno = startDeno({
    APP_ENV: "local",
    SUPABASE_URL: "http://127.0.0.1:9", // JWKS inacessível: só tokens HS256 deste emulador são aceitos
    DV_JWT_ISSUER: ISSUER,
    DV_JWT_SECRET: secret,
    DV_BACKEND_DB_URL: `postgresql://dv_backend:emulated@${dbAddr}/postgres`,
  });
  let apiUrl;
  try {
    apiUrl = await deno.url;
  } catch (e) {
    await server.stop();
    await pg.close();
    throw e;
  }

  return {
    mode: "emulated",
    apiUrl,
    logs: () => deno.logs.slice(),
    /** SQL privilegiado (relógio simulado, fixtures). Uma única chamada: o multiplexador não intercala. */
    async admin(statement) {
      const results = await pg.exec(`set role postgres; ${statement}; set role dv_backend`);
      return results[results.length - 2]?.rows ?? [];
    },
    async createUser({ email, nickname }) {
      const id = randomUUID();
      await this.admin(`insert into auth.users (id, email, email_confirmed_at, raw_user_meta_data)
                        values ('${id}', ${lit(email)}, now(), ${lit(JSON.stringify({ nickname }))}::jsonb)`);
      return { userId: id, token: signToken(authClaims(id), secret) };
    },
    token(userId, opts) {
      return signToken(authClaims(userId, opts), secret);
    },
    async close() {
      deno.child.kill();
      await new Promise((r) => (deno.child.exitCode !== null ? r() : deno.child.once("exit", r)));
      await server.stop();
      await pg.close();
    },
  };
}

/** Literal SQL para valores gerados pelos próprios testes (standard_conforming_strings ligado). */
export function lit(v) {
  if (v === null || v === undefined) return "null";
  if (typeof v === "number") {
    if (!Number.isFinite(v)) throw new Error("número inválido");
    return String(v);
  }
  if (typeof v === "boolean") return v ? "true" : "false";
  return `'${String(v).replaceAll("'", "''")}'`;
}
