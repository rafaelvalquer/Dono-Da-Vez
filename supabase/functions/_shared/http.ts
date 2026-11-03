import { ApiError, statusFor } from "./errors.ts";

export type Json = Record<string, unknown> | unknown[];

export interface Ctx {
  req: Request;
  url: URL;
  correlationId: string;
  params: Record<string, string>;
  started: number;
}

export function json(body: unknown, status = 200, headers: HeadersInit = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", ...headers },
  });
}

export function errorBody(code: string, message: string, detail: Record<string, unknown> = {}, extra: Record<string, unknown> = {}) {
  return { ok: false, ...extra, error: { code, message, detail } };
}

export function errorResponse(e: ApiError, correlationId: string): Response {
  return json(errorBody(e.code, e.message, e.detail, { correlation_id: correlationId }), e.status);
}

// Resultado de função SQL de operação: {ok:false, error:{...}} vira HTTP de erro com o mesmo corpo.
export function opResponse(result: Record<string, unknown>, correlationId: string): Response {
  if (result && result.ok === false) {
    const err = result.error as { code: string } | undefined;
    const status = err ? statusFor(err.code) : 409;
    return json({ ...result, correlation_id: correlationId }, status);
  }
  return json({ ...result, correlation_id: correlationId }, 200);
}

// Log estruturado: nunca incluir token, e-mail ou coordenadas brutas.
export function log(level: "info" | "warn" | "error", msg: string, fields: Record<string, unknown> = {}) {
  console.log(JSON.stringify({ level, msg, ts: new Date().toISOString(), ...fields }));
}

export async function readJson(req: Request, maxBytes = 16_384): Promise<Record<string, unknown>> {
  const text = await req.text();
  if (text.length > maxBytes) throw new ApiError(413, "VALIDATION_FAILED", "Corpo da requisição muito grande");
  if (!text) return {};
  try {
    const v = JSON.parse(text);
    if (typeof v !== "object" || v === null || Array.isArray(v)) throw new Error();
    return v;
  } catch {
    throw new ApiError(400, "VALIDATION_FAILED", "JSON inválido");
  }
}

export function canonicalJson(value: unknown): string {
  if (value === null || typeof value !== "object") return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(",")}]`;
  const obj = value as Record<string, unknown>;
  return `{${Object.keys(obj).sort().filter((k) => obj[k] !== undefined).map((k) => `${JSON.stringify(k)}:${canonicalJson(obj[k])}`).join(",")}}`;
}

export async function sha256Hex(text: string): Promise<string> {
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

// Roteador mínimo: "METHOD /path/:param".
export type Handler = (ctx: Ctx) => Promise<Response>;
interface Route { method: string; parts: string[]; handler: Handler }

export class Router {
  private routes: Route[] = [];
  on(method: string, path: string, handler: Handler) {
    this.routes.push({ method, parts: path.split("/").filter(Boolean), handler });
    return this;
  }
  match(method: string, path: string): { handler: Handler; params: Record<string, string> } | "method" | null {
    const parts = path.split("/").filter(Boolean);
    let pathMatched = false;
    for (const r of this.routes) {
      if (r.parts.length !== parts.length) continue;
      const params: Record<string, string> = {};
      let ok = true;
      for (let i = 0; i < parts.length; i++) {
        if (r.parts[i].startsWith(":")) params[r.parts[i].slice(1)] = decodeURIComponent(parts[i]);
        else if (r.parts[i] !== parts[i]) { ok = false; break; }
      }
      if (!ok) continue;
      pathMatched = true;
      if (r.method === method) return { handler: r.handler, params };
    }
    return pathMatched ? "method" : null;
  }
}

// Caminho lógico a partir de "/v1": funciona em /functions/v1/api/v1/... e em /api/v1/...
export function logicalPath(url: URL): string {
  const idx = url.pathname.indexOf("/v1/");
  const tail = url.pathname.lastIndexOf("/v1/");
  if (idx === -1) return url.pathname;
  // /functions/v1/api/v1/map → usa a última ocorrência de /v1/
  return url.pathname.slice(tail === idx ? idx : tail);
}
