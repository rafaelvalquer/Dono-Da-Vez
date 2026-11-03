// Console administrativo (/v1). Exige JWT com MFA (aal2); papéis verificados no banco
// (game.admin_roles) — nunca em metadados editáveis do usuário. Toda mutação é auditada.
import { authenticate, type Actor } from "../_shared/auth.ts";
import { call, db } from "../_shared/db.ts";
import { ApiError } from "../_shared/errors.ts";
import { canonicalJson, type Ctx, errorResponse, json, log, logicalPath, readJson, Router, sha256Hex } from "../_shared/http.ts";
import { idempotencyKey, parse, query, uuid } from "../_shared/validate.ts";
import { optionalEnv } from "../_shared/env.ts";
import { z } from "../_shared/deps.ts";

const sql = () => db("DV_ADMIN_DB_URL");
const reason = z.string().trim().min(3).max(500);

// Rate limit por instância (baixo volume); auditoria e papéis são autoridade no banco.
const hits = new Map<string, { n: number; reset: number }>();
function rateLimit(actor: Actor) {
  const now = Date.now();
  const h = hits.get(actor.userId);
  if (!h || h.reset < now) { hits.set(actor.userId, { n: 1, reset: now + 60_000 }); return; }
  if (++h.n > 120) throw new ApiError(429, "RATE_LIMITED", "Muitas requisições");
}

async function admin(ctx: Ctx): Promise<Actor> {
  const actor = await authenticate(ctx.req);
  if (actor.aal !== "aal2") throw new ApiError(403, "MFA_REQUIRED", "Ative e confirme o segundo fator (MFA) para acessar o painel");
  rateLimit(actor);
  return actor;
}

async function mutationKey(ctx: Ctx, routeKey: string, body: unknown) {
  const key = parse(idempotencyKey, ctx.req.headers.get("idempotency-key"));
  return { key, hash: await sha256Hex(`${routeKey}\n${canonicalJson(body)}`) };
}

// deno-lint-ignore no-explicit-any
async function one(ctx: Ctx, q: () => Promise<any>) {
  const rows = await call(q, ctx.correlationId);
  return rows[0].r;
}

const r = new Router();

r.on("GET", "/v1/whoami", async (ctx) => {
  const a = await admin(ctx);
  return json(await one(ctx, () => sql()`select admin_api.whoami(${a.userId}::uuid) as r`));
});
r.on("GET", "/v1/ops", async (ctx) => {
  const a = await admin(ctx);
  return json(await one(ctx, () => sql()`select admin_api.ops_metrics(${a.userId}::uuid) as r`));
});
r.on("GET", "/v1/search", async (ctx) => {
  const a = await admin(ctx);
  const q = parse(z.object({ q: z.string().min(2).max(100) }), query(ctx.url));
  return json(await one(ctx, () => sql()`select admin_api.search(${a.userId}::uuid, ${q.q}) as r`));
});
r.on("GET", "/v1/users/:id", async (ctx) => {
  const a = await admin(ctx);
  const id = parse(uuid, ctx.params.id);
  return json(await one(ctx, () => sql()`select admin_api.user_detail(${a.userId}::uuid, ${id}::uuid) as r`));
});
r.on("POST", "/v1/users/:id/status", async (ctx) => {
  const a = await admin(ctx);
  const id = parse(uuid, ctx.params.id);
  const b = parse(z.object({ status: z.enum(["active", "suspended"]), reason }), await readJson(ctx.req));
  return json(await one(ctx, () => sql()`select admin_api.set_user_status(${a.userId}::uuid, ${id}::uuid, ${b.status}, ${b.reason}) as r`));
});
r.on("POST", "/v1/users/:id/roles", async (ctx) => {
  const a = await admin(ctx);
  const id = parse(uuid, ctx.params.id);
  const b = parse(z.object({ role: z.enum(["support", "geo", "finance", "superadmin"]), grant: z.boolean(), reason }), await readJson(ctx.req));
  return json(await one(ctx, () => sql()`select admin_api.set_role(${a.userId}::uuid, ${id}::uuid, ${b.role}, ${b.grant}, ${b.reason}) as r`));
});
r.on("GET", "/v1/properties/:id", async (ctx) => {
  const a = await admin(ctx);
  const id = parse(uuid, ctx.params.id);
  return json(await one(ctx, () => sql()`select admin_api.property_detail(${a.userId}::uuid, ${id}::uuid) as r`));
});
r.on("POST", "/v1/properties/:id/eligibility", async (ctx) => {
  const a = await admin(ctx);
  const id = parse(uuid, ctx.params.id);
  const b = parse(z.object({ eligibility: z.enum(["eligible", "suspended", "under_review", "pending_classification"]), reason }), await readJson(ctx.req));
  const m = await mutationKey(ctx, `POST /v1/properties/${id}/eligibility`, b);
  return json(await one(ctx, () => sql()`
    select admin_api.set_property_eligibility(${a.userId}::uuid, ${m.key}, ${m.hash}, ${id}::uuid, ${b.eligibility}, ${b.reason}) as r`));
});
r.on("POST", "/v1/properties/:id/classification", async (ctx) => {
  const a = await admin(ctx);
  const id = parse(uuid, ctx.params.id);
  const b = parse(z.object({
    category: z.enum(["house_small", "house_large", "commercial", "residential_building"]),
    subtype: z.string().max(40).nullable().default(null), reason,
  }), await readJson(ctx.req));
  const m = await mutationKey(ctx, `POST /v1/properties/${id}/classification`, b);
  return json(await one(ctx, () => sql()`
    select admin_api.set_property_classification(${a.userId}::uuid, ${m.key}, ${m.hash}, ${id}::uuid, ${b.category}, ${b.subtype}, ${b.reason}) as r`));
});
r.on("GET", "/v1/review-cases", async (ctx) => {
  const a = await admin(ctx);
  const q = parse(z.object({ status: z.enum(["open", "resolved", "dismissed"]).default("open") }), query(ctx.url));
  return json(await one(ctx, () => sql()`select admin_api.list_review_cases(${a.userId}::uuid, ${q.status}, 200) as r`));
});
r.on("POST", "/v1/review-cases/:id/resolve", async (ctx) => {
  const a = await admin(ctx);
  const id = parse(uuid, ctx.params.id);
  const b = parse(z.object({
    action: z.enum(["dismiss", "restore_eligibility", "keep_suspended", "verify_entrance", "compensate_and_close"]),
    note: reason, amount: z.string().regex(/^\d{1,13}(\.\d{1,2})?$/).optional(),
  }), await readJson(ctx.req));
  const m = await mutationKey(ctx, `POST /v1/review-cases/${id}/resolve`, b);
  return json(await one(ctx, () => sql()`
    select admin_api.resolve_review_case(${a.userId}::uuid, ${m.key}, ${m.hash}, ${id}::uuid, ${b.action}, ${b.note}, ${b.amount ?? null}::numeric) as r`));
});
r.on("GET", "/v1/proposals", async (ctx) => {
  const a = await admin(ctx);
  const q = parse(z.object({ status: z.string().max(40).optional(), user: uuid.optional() }), query(ctx.url));
  return json(await one(ctx, () => sql()`select admin_api.list_proposals(${a.userId}::uuid, ${q.status ?? null}, ${q.user ?? null}::uuid, 200) as r`));
});
r.on("POST", "/v1/proposals/:id/cancel", async (ctx) => {
  const a = await admin(ctx);
  const id = parse(uuid, ctx.params.id);
  const b = parse(z.object({ reason }), await readJson(ctx.req));
  const m = await mutationKey(ctx, `POST /v1/proposals/${id}/cancel`, b);
  return json(await one(ctx, () => sql()`select admin_api.cancel_proposal(${a.userId}::uuid, ${m.key}, ${m.hash}, ${id}::uuid, ${b.reason}) as r`));
});
r.on("POST", "/v1/wallets/:id/adjustments", async (ctx) => {
  const a = await admin(ctx);
  const id = parse(uuid, ctx.params.id);
  const b = parse(z.object({
    amount: z.string().regex(/^-?\d{1,13}(\.\d{1,8})?$/), reason, reference_transaction_id: uuid.optional(),
  }), await readJson(ctx.req));
  const m = await mutationKey(ctx, `POST /v1/wallets/${id}/adjustments`, b);
  return json(await one(ctx, () => sql()`
    select admin_api.compensating_adjustment(${a.userId}::uuid, ${m.key}, ${m.hash}, ${id}::uuid, ${b.amount}::numeric,
                                             ${b.reason}, ${b.reference_transaction_id ?? null}::uuid) as r`));
});
r.on("POST", "/v1/wallets/:id/unfreeze", async (ctx) => {
  const a = await admin(ctx);
  const id = parse(uuid, ctx.params.id);
  const b = parse(z.object({ reason }), await readJson(ctx.req));
  const m = await mutationKey(ctx, `POST /v1/wallets/${id}/unfreeze`, b);
  return json(await one(ctx, () => sql()`select admin_api.unfreeze_wallet(${a.userId}::uuid, ${m.key}, ${m.hash}, ${id}::uuid, ${b.reason}) as r`));
});
r.on("GET", "/v1/alerts", async (ctx) => {
  const a = await admin(ctx);
  const q = parse(z.object({ status: z.enum(["open", "acknowledged", "resolved"]).default("open") }), query(ctx.url));
  return json(await one(ctx, () => sql()`select admin_api.list_alerts(${a.userId}::uuid, ${q.status}) as r`));
});
r.on("POST", "/v1/alerts/:id", async (ctx) => {
  const a = await admin(ctx);
  const id = parse(uuid, ctx.params.id);
  const b = parse(z.object({ status: z.enum(["acknowledged", "resolved"]), note: reason }), await readJson(ctx.req));
  return json(await one(ctx, () => sql()`select admin_api.resolve_alert(${a.userId}::uuid, ${id}::uuid, ${b.status}, ${b.note}) as r`));
});
r.on("GET", "/v1/risk-reviews", async (ctx) => {
  const a = await admin(ctx);
  const q = parse(z.object({ status: z.enum(["pending", "approved", "rejected"]).default("pending") }), query(ctx.url));
  return json(await one(ctx, () => sql()`select admin_api.list_risk_reviews(${a.userId}::uuid, ${q.status}) as r`));
});
r.on("POST", "/v1/risk-reviews/:id", async (ctx) => {
  const a = await admin(ctx);
  const id = parse(uuid, ctx.params.id);
  const b = parse(z.object({ decision: z.enum(["approved", "rejected"]), note: reason }), await readJson(ctx.req));
  return json(await one(ctx, () => sql()`select admin_api.decide_risk_review(${a.userId}::uuid, ${id}::uuid, ${b.decision}, ${b.note}) as r`));
});
r.on("GET", "/v1/import-batches", async (ctx) => {
  const a = await admin(ctx);
  const q = parse(z.object({ region: z.string().max(64).optional() }), query(ctx.url));
  return json(await one(ctx, () => sql()`select admin_api.list_import_batches(${a.userId}::uuid, ${q.region ?? null}) as r`));
});
for (const action of ["approve", "reject", "publish"] as const) {
  r.on("POST", `/v1/import-batches/:id/${action}`, async (ctx) => {
    const a = await admin(ctx);
    const id = parse(uuid, ctx.params.id);
    const b = parse(z.object({ note: reason }), await readJson(ctx.req));
    return json(await one(ctx, () =>
      action === "approve" ? sql()`select admin_api.approve_import_batch(${a.userId}::uuid, ${id}::uuid, ${b.note}) as r`
      : action === "reject" ? sql()`select admin_api.reject_import_batch(${a.userId}::uuid, ${id}::uuid, ${b.note}) as r`
      : sql()`select admin_api.publish_import_batch(${a.userId}::uuid, ${id}::uuid, ${b.note}) as r`));
  });
}
r.on("GET", "/v1/regions", async (ctx) => {
  const a = await admin(ctx);
  return json(await one(ctx, () => sql()`select admin_api.list_regions(${a.userId}::uuid) as r`));
});
r.on("POST", "/v1/regions/:id/release", async (ctx) => {
  const a = await admin(ctx);
  const id = parse(uuid, ctx.params.id);
  const b = parse(z.object({ status: z.enum(["draft", "qa", "released", "paused"]), reason }), await readJson(ctx.req));
  return json(await one(ctx, () => sql()`select admin_api.set_region_release(${a.userId}::uuid, ${id}::uuid, ${b.status}, ${b.reason}) as r`));
});
r.on("GET", "/v1/config", async (ctx) => {
  const a = await admin(ctx);
  return json(await one(ctx, () => sql()`select admin_api.config_versions(${a.userId}::uuid) as r`));
});
r.on("POST", "/v1/config", async (ctx) => {
  const a = await admin(ctx);
  const b = parse(z.object({ patch: z.record(z.unknown()), effective_at: z.string().datetime().optional(), note: reason }), await readJson(ctx.req));
  return json(await one(ctx, () => sql()`
    select admin_api.publish_config(${a.userId}::uuid, ${JSON.stringify(b.patch)}::jsonb, ${b.effective_at ?? null}::timestamptz, ${b.note}) as r`));
});
r.on("GET", "/v1/reports", async (ctx) => {
  const a = await admin(ctx);
  const q = parse(z.object({ status: z.enum(["open", "in_progress", "resolved", "rejected"]).default("open") }), query(ctx.url));
  return json(await one(ctx, () => sql()`select admin_api.list_reports(${a.userId}::uuid, ${q.status}) as r`));
});
r.on("POST", "/v1/reports/:id", async (ctx) => {
  const a = await admin(ctx);
  const id = parse(z.coerce.number().int().positive(), ctx.params.id);
  const b = parse(z.object({ status: z.enum(["open", "in_progress", "resolved", "rejected"]), resolution: z.string().max(2000).optional() }), await readJson(ctx.req));
  return json(await one(ctx, () => sql()`select admin_api.update_report(${a.userId}::uuid, ${id}::bigint, ${b.status}, ${b.resolution ?? null}) as r`));
});
r.on("GET", "/v1/economy", async (ctx) => {
  const a = await admin(ctx);
  const q = parse(z.object({ days: z.coerce.number().int().min(1).max(365).default(30) }), query(ctx.url));
  return json(await one(ctx, () => sql()`select admin_api.economy_reports(${a.userId}::uuid, ${q.days}::int) as r`));
});
r.on("POST", "/v1/reconcile", async (ctx) => {
  const a = await admin(ctx);
  return json(await one(ctx, () => sql()`select admin_api.run_reconciliation(${a.userId}::uuid) as r`));
});
r.on("GET", "/v1/audit", async (ctx) => {
  const a = await admin(ctx);
  const q = parse(z.object({ before: z.coerce.number().int().positive().optional() }), query(ctx.url));
  return json(await one(ctx, () => sql()`select admin_api.audit_log(${a.userId}::uuid, ${q.before ?? null}::bigint, 100) as r`));
});

function cors(origin: string | null): Record<string, string> {
  const allowed = (optionalEnv("DV_ADMIN_ORIGINS") ?? "http://127.0.0.1:5173,http://localhost:5173").split(",");
  if (!origin || !allowed.includes(origin)) return {};
  return {
    "access-control-allow-origin": origin,
    "access-control-allow-headers": "authorization, content-type, idempotency-key, x-correlation-id, apikey, x-client-info",
    "access-control-allow-methods": "GET, POST, OPTIONS",
    "access-control-max-age": "600",
    vary: "Origin",
  };
}

Deno.serve(async (req) => {
  const url = new URL(req.url);
  const corsHeaders = cors(req.headers.get("origin"));
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: corsHeaders });
  const correlationId = crypto.randomUUID();
  const ctx: Ctx = { req, url, correlationId, params: {}, started: performance.now() };
  let res: Response;
  try {
    const m = r.match(req.method, logicalPath(url));
    if (m === null) throw new ApiError(404, "NOT_FOUND", "Rota não encontrada");
    if (m === "method") throw new ApiError(405, "VALIDATION_FAILED", "Método não permitido");
    ctx.params = m.params;
    res = await m.handler(ctx);
  } catch (e) {
    res = e instanceof ApiError ? errorResponse(e, correlationId) : errorResponse(new ApiError(500, "INTERNAL", "Erro interno"), correlationId);
    if (!(e instanceof ApiError)) log("error", "admin.unhandled", { correlation_id: correlationId });
  }
  for (const [k, v] of Object.entries(corsHeaders)) res.headers.set(k, v);
  log("info", "admin.request", { correlation_id: correlationId, method: req.method, status: res.status,
                                 duration_ms: Math.round(performance.now() - ctx.started) });
  return res;
});
