import { jose } from "./deps.ts";
import { ApiError } from "./errors.ts";
import { env, optionalEnv } from "./env.ts";

export interface Actor {
  userId: string;
  aal: string;
  issuedAt: number;
  /** Último login efetivo (claim amr do Supabase); refresh de token não altera. */
  authenticatedAt: number;
  sessionId?: string;
}

let jwks: ReturnType<typeof jose.createRemoteJWKSet> | undefined;

function issuer(): string {
  return optionalEnv("DV_JWT_ISSUER") ?? `${env("SUPABASE_URL")}/auth/v1`;
}

// Verifica o JWT do Supabase Auth. actor_id vem sempre do "sub" verificado — o cliente
// nunca escolhe o user_id. Chaves assimétricas via JWKS; HS256 apenas em local/legado.
export async function authenticate(req: Request): Promise<Actor> {
  const header = req.headers.get("authorization") ?? "";
  const m = /^Bearer\s+(.+)$/i.exec(header);
  if (!m) throw new ApiError(401, "UNAUTHORIZED", "Sessão ausente; entre novamente");
  const token = m[1];
  const opts = { issuer: issuer(), audience: "authenticated" };
  let payload: jose.JWTPayload;
  try {
    // HS256 só com segredo configurado (local/legado); demais algoritmos sempre via JWKS do projeto.
    const secret = optionalEnv("DV_JWT_SECRET");
    if (secret && jose.decodeProtectedHeader(token).alg === "HS256") {
      ({ payload } = await jose.jwtVerify(token, new TextEncoder().encode(secret), { ...opts, algorithms: ["HS256"] }));
    } else {
      jwks ??= jose.createRemoteJWKSet(new URL(`${env("SUPABASE_URL")}/auth/v1/.well-known/jwks.json`), {
        cooldownDuration: 30_000,
        cacheMaxAge: 600_000,
      });
      ({ payload } = await jose.jwtVerify(token, jwks, { ...opts, algorithms: ["RS256", "ES256"] }));
    }
  } catch (e) {
    const expired = (e as { code?: string })?.code === "ERR_JWT_EXPIRED";
    throw new ApiError(401, "UNAUTHORIZED", expired ? "Sessão expirada; entre novamente" : "Sessão inválida");
  }
  const sub = payload.sub;
  if (payload.role !== "authenticated" || typeof sub !== "string" || !/^[0-9a-f-]{36}$/i.test(sub)) {
    throw new ApiError(401, "UNAUTHORIZED", "Sessão inválida");
  }
  return {
    userId: sub,
    aal: typeof payload.aal === "string" ? payload.aal : "aal1",
    issuedAt: typeof payload.iat === "number" ? payload.iat : 0,
    authenticatedAt: authTime(payload),
    sessionId: typeof payload.session_id === "string" ? payload.session_id : undefined,
  };
}

function authTime(payload: jose.JWTPayload): number {
  const amr = (payload as { amr?: Array<{ timestamp?: number }> }).amr;
  const times = Array.isArray(amr) ? amr.map((a) => a?.timestamp).filter((t): t is number => typeof t === "number") : [];
  return times.length ? Math.max(...times) : 0;
}

// Reautenticação recente (exclusão de conta): login com senha nos últimos N segundos.
// Usa o claim amr (momento da autenticação), pois o iat muda a cada refresh do token.
export function requireRecentAuth(actor: Actor, maxAgeSeconds = 300) {
  const age = Math.floor(Date.now() / 1000) - actor.authenticatedAt;
  if (age > maxAgeSeconds) {
    throw new ApiError(401, "REAUTH_REQUIRED", "Confirme sua senha novamente para continuar", { max_age_s: maxAgeSeconds });
  }
}
