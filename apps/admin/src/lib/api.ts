import { adminApiUrl, publishableKey, supabase } from "./supabase";

/** Erro estável do servidor (código de packages/contracts/schemas/error-codes.json). */
export class AdminError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly detail?: unknown,
    readonly correlationId?: string,
  ) {
    super(message);
  }
}

export function newIdempotencyKey(): string {
  return `adm-${crypto.randomUUID()}`;
}

type Options = {
  method?: "GET" | "POST";
  body?: unknown;
  query?: Record<string, string | number | undefined | null>;
  /** Chave de idempotência (mutações econômicas e de elegibilidade exigem). */
  idempotencyKey?: string;
};

export function buildUrl(base: string, path: string, query?: Options["query"]): string {
  const u = new URL(base + path);
  for (const [k, v] of Object.entries(query ?? {})) {
    if (v !== undefined && v !== null && v !== "") u.searchParams.set(k, String(v));
  }
  return u.toString();
}

/** Interpreta a resposta: HTTP de erro ou resultado de operação {ok:false} viram AdminError. */
export function interpret(status: number, body: unknown, correlationId?: string): unknown {
  const obj = (body ?? {}) as { ok?: boolean; error?: { code?: string; message?: string; detail?: unknown } };
  if (status >= 400 || obj.ok === false) {
    const e = obj.error ?? {};
    throw new AdminError(status >= 400 ? status : 409, e.code ?? (status === 401 ? "UNAUTHORIZED" : "INTERNAL"),
      e.message ?? `Falha (${status})`, e.detail, correlationId);
  }
  return body;
}

export async function adminFetch<T = unknown>(path: string, opts: Options = {}): Promise<T> {
  const { data } = await supabase.auth.getSession();
  const token = data.session?.access_token;
  if (!token) throw new AdminError(401, "UNAUTHORIZED", "Sessão expirada; entre novamente");
  const correlationId = crypto.randomUUID();
  const headers: Record<string, string> = {
    authorization: `Bearer ${token}`,
    apikey: publishableKey,
    "x-correlation-id": correlationId,
  };
  if (opts.body !== undefined) headers["content-type"] = "application/json";
  if (opts.idempotencyKey) headers["idempotency-key"] = opts.idempotencyKey;
  let res: Response;
  try {
    res = await fetch(buildUrl(adminApiUrl, path, opts.query), {
      method: opts.method ?? (opts.body !== undefined ? "POST" : "GET"),
      headers,
      body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
    });
  } catch {
    throw new AdminError(0, "NETWORK", "Sem conexão com o servidor. Se era uma mutação, repita: a mesma chave evita duplicidade.");
  }
  const text = await res.text();
  let body: unknown = null;
  try { body = text ? JSON.parse(text) : null; } catch { body = null; }
  return interpret(res.status, body, correlationId) as T;
}
