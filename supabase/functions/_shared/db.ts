import { postgres } from "./deps.ts";
import { ApiError, TRANSIENT_SQLSTATES, statusFor } from "./errors.ts";
import { log } from "./http.ts";

type Sql = ReturnType<typeof postgres>;
const pools = new Map<string, Sql>();

// Conexão com papel restrito (dv_backend / dv_admin_backend). prepare:false para o pooler
// em modo transação. Nunca usar a service role do Supabase para operações do jogo.
export function db(urlEnv: string): Sql {
  let sql = pools.get(urlEnv);
  if (!sql) {
    const url = Deno.env.get(urlEnv);
    if (!url) throw new Error(`variável de ambiente ausente: ${urlEnv}`);
    sql = postgres(url, {
      prepare: false,
      max: 3,
      idle_timeout: 20,
      connect_timeout: 10,
      connection: { application_name: "dono-da-vez-edge", statement_timeout: 8000, lock_timeout: 5000 },
    });
    pools.set(urlEnv, sql);
  }
  return sql;
}

interface PgError extends Error { code?: string; detail?: string; hint?: string }

// Converte erro do Postgres em ApiError. DV000 = erro de domínio (MESSAGE=código, HINT=mensagem).
export function toApiError(e: unknown, correlationId: string): ApiError {
  const err = e as PgError;
  if (err?.code === "DV000") {
    let detail: Record<string, unknown> = {};
    try { detail = err.detail ? JSON.parse(err.detail) : {}; } catch { /* detalhe não-JSON */ }
    delete detail.buyer_id; delete detail.seller_id;
    return new ApiError(statusFor(err.message), err.message, err.hint ?? err.message, detail);
  }
  if (err?.code && TRANSIENT_SQLSTATES.has(err.code)) {
    return new ApiError(503, "TRANSIENT", "Serviço ocupado; tente novamente em instantes");
  }
  if (err?.code === "42501") {
    log("error", "db.permission_denied", { correlation_id: correlationId });
    return new ApiError(500, "INTERNAL", "Erro interno");
  }
  log("error", "db.error", { correlation_id: correlationId, sqlstate: err?.code, name: err?.name });
  return new ApiError(500, "INTERNAL", "Erro interno");
}

// Executa a chamada com retry limitado em deadlock/serialização. A mesma Idempotency-Key
// garante que uma operação já confirmada não é repetida.
export async function call<T>(fn: () => Promise<T>, correlationId: string, attempts = 3): Promise<T> {
  let last: unknown;
  for (let i = 0; i < attempts; i++) {
    try {
      return await fn();
    } catch (e) {
      last = e;
      const code = (e as PgError)?.code;
      if (!code || !TRANSIENT_SQLSTATES.has(code) || i === attempts - 1) break;
      await new Promise((r) => setTimeout(r, 50 * 2 ** i + Math.random() * 50));
    }
  }
  throw toApiError(last, correlationId);
}

export function uuidArrayLiteral(ids: string[]): string {
  return `{${ids.join(",")}}`;
}
