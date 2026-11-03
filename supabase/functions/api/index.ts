// Dono da Vez — API do jogo (/v1). Fluxo de confiança:
// app → JWT verificado → função transacional no PostgreSQL → resposta confirmada.
// Push/eventos apenas avisam; o estado definitivo vem sempre desta API.
import { authenticate, requireRecentAuth, type Actor } from "../_shared/auth.ts";
import { call, db, uuidArrayLiteral } from "../_shared/db.ts";
import { ApiError } from "../_shared/errors.ts";
import {
  canonicalJson, type Ctx, errorResponse, json, log, logicalPath, opResponse, readJson, Router, sha256Hex,
} from "../_shared/http.ts";
import { idempotencyKey, parse, query, schemas, uuid } from "../_shared/validate.ts";

const sql = () => db("DV_BACKEND_DB_URL");

// Limites por janela (rate limit no banco; atividade qualificada registrada junto).
const BUCKETS = {
  read: { limit: 240, window: 60, qualifies: false },
  mutation: { limit: 30, window: 60, qualifies: true },
  presence: { limit: 30, window: 60, qualifies: true },
  heartbeat: { limit: 12, window: 60, qualifies: true },
  report: { limit: 5, window: 3600, qualifies: true },
} as const;
type Bucket = keyof typeof BUCKETS;

async function preflight(actor: Actor, bucket: Bucket, ctx: Ctx) {
  const b = BUCKETS[bucket];
  const [row] = await call(
    () => sql()`select game_api.preflight(${actor.userId}::uuid, ${bucket}, ${b.limit}, ${b.window}, ${b.qualifies}) as r`,
    ctx.correlationId,
  );
  const r = row.r as { allowed: boolean; retry_after_s: number };
  if (!r.allowed) {
    throw new ApiError(429, "RATE_LIMITED", "Muitas requisições; aguarde um instante", { retry_after_s: r.retry_after_s });
  }
}

interface Mutation { key: string; hash: string }

// Toda mutação econômica exige Idempotency-Key; hash do payload detecta reuso indevido.
async function mutation(ctx: Ctx, routeKey: string, body: unknown): Promise<Mutation> {
  const key = ctx.req.headers.get("idempotency-key");
  const parsed = idempotencyKey.safeParse(key);
  if (!parsed.success) throw new ApiError(422, "VALIDATION_FAILED", "Cabeçalho Idempotency-Key ausente ou inválido");
  const hash = await sha256Hex(`${routeKey}\n${canonicalJson(body)}`);
  return { key: parsed.data, hash };
}

// deno-lint-ignore no-explicit-any
async function one(ctx: Ctx, q: () => Promise<any>): Promise<Record<string, unknown>> {
  const rows = await call(q, ctx.correlationId);
  return rows[0].r as Record<string, unknown>;
}

const router = new Router();

router.on("GET", "/v1/health", async (ctx) => {
  const r = await one(ctx, () => sql()`select game_api.health() as r`);
  return json({ status: "ok", ...r });
});

router.on("GET", "/v1/bootstrap", async (ctx) => {
  const actor = await authenticate(ctx.req);
  await preflight(actor, "heartbeat", ctx);
  const q = parse(schemas.bootstrap, query(ctx.url));
  return json(await one(ctx, () => sql()`select game_api.bootstrap(${actor.userId}::uuid, ${q.app_version ?? 0}::int) as r`));
});

router.on("POST", "/v1/activity", async (ctx) => {
  const actor = await authenticate(ctx.req);
  await preflight(actor, "heartbeat", ctx);
  const b = parse(schemas.activity, await readJson(ctx.req));
  return json(await one(ctx, () => sql()`select game_api.record_activity(${actor.userId}::uuid, ${b.source}) as r`));
});

router.on("GET", "/v1/profile", async (ctx) => {
  const actor = await authenticate(ctx.req);
  await preflight(actor, "read", ctx);
  return json(await one(ctx, () => sql()`select game_api.get_profile(${actor.userId}::uuid) as r`));
});

router.on("PATCH", "/v1/profile", async (ctx) => {
  const actor = await authenticate(ctx.req);
  await preflight(actor, "mutation", ctx);
  const b = parse(schemas.profile, await readJson(ctx.req));
  const prefs = b.notification_prefs ? JSON.stringify(b.notification_prefs) : null;
  return json(await one(ctx, () =>
    sql()`select game_api.update_profile(${actor.userId}::uuid, ${b.nickname ?? null}, ${b.avatar_id ?? null}, ${prefs}::jsonb) as r`));
});

router.on("GET", "/v1/map", async (ctx) => {
  const actor = await authenticate(ctx.req);
  await preflight(actor, "read", ctx);
  const q = parse(schemas.map, query(ctx.url));
  const [minLon, minLat, maxLon, maxLat] = q.bbox.split(",").map(Number);
  return json(await one(ctx, () => sql()`
    select game_api.map_features(${actor.userId}::uuid, ${minLon}::float8, ${minLat}::float8, ${maxLon}::float8,
                                 ${maxLat}::float8, ${q.zoom}::numeric, ${q.cursor ?? null}::uuid, ${q.limit ?? null}::int) as r`));
});

router.on("GET", "/v1/nearby", async (ctx) => {
  const actor = await authenticate(ctx.req);
  await preflight(actor, "read", ctx);
  const q = parse(schemas.nearby, query(ctx.url));
  return json(await one(ctx, () =>
    sql()`select game_api.nearby(${actor.userId}::uuid, ${q.lat}::float8, ${q.lon}::float8, ${q.radius ?? 80}::numeric) as r`));
});

router.on("GET", "/v1/properties/:id", async (ctx) => {
  const actor = await authenticate(ctx.req);
  await preflight(actor, "read", ctx);
  const id = parse(uuid, ctx.params.id);
  return json(await one(ctx, () => sql()`select game_api.property_detail(${actor.userId}::uuid, ${id}::uuid) as r`));
});

router.on("POST", "/v1/presence", async (ctx) => {
  const actor = await authenticate(ctx.req);
  await preflight(actor, "presence", ctx);
  const b = parse(schemas.presence, await readJson(ctx.req));
  const r = await one(ctx, () => sql()`
    select game_api.create_presence_proof(${actor.userId}::uuid, ${b.property_id}::uuid, ${b.lat}::float8, ${b.lon}::float8,
             ${b.accuracy_m}::numeric, ${b.fix_age_ms}::int, ${b.speed_mps ?? null}::numeric, ${b.nonce},
             ${b.mock_location}::boolean, ${b.integrity_verdict ?? null}) as r`);
  return opResponse(r, ctx.correlationId);
});

router.on("POST", "/v1/purchases", async (ctx) => {
  const actor = await authenticate(ctx.req);
  await preflight(actor, "mutation", ctx);
  const b = parse(schemas.purchase, await readJson(ctx.req));
  const m = await mutation(ctx, "POST /v1/purchases", b);
  const r = await one(ctx, () => sql()`
    select game_api.purchase_property(${actor.userId}::uuid, ${m.key}, ${m.hash}, ${b.property_id}::uuid,
             ${b.proof_id}::uuid, ${b.expected_version ?? null}::bigint) as r`);
  return opResponse(r, ctx.correlationId);
});

router.on("POST", "/v1/proposals", async (ctx) => {
  const actor = await authenticate(ctx.req);
  await preflight(actor, "mutation", ctx);
  const b = parse(schemas.proposal, await readJson(ctx.req));
  const m = await mutation(ctx, "POST /v1/proposals", b);
  const r = await one(ctx, () => sql()`
    select game_api.create_proposal(${actor.userId}::uuid, ${m.key}, ${m.hash}, ${b.property_id}::uuid,
             ${b.ownership_id}::uuid, ${b.amount}::numeric, ${b.proof_id}::uuid) as r`);
  return opResponse(r, ctx.correlationId);
});

router.on("GET", "/v1/proposals", async (ctx) => {
  const actor = await authenticate(ctx.req);
  await preflight(actor, "read", ctx);
  const q = parse(schemas.proposalList, query(ctx.url));
  return json(await one(ctx, () => sql()`
    select game_api.list_proposals(${actor.userId}::uuid, ${q.direction}, ${q.status}, ${q.cursor ?? null}, ${q.limit ?? 50}::int) as r`));
});

for (const action of ["accept", "reject", "cancel"] as const) {
  router.on("POST", `/v1/proposals/:id/${action}`, async (ctx) => {
    const actor = await authenticate(ctx.req);
    await preflight(actor, "mutation", ctx);
    const id = parse(uuid, ctx.params.id);
    const b = parse(schemas.proposalAction, await readJson(ctx.req));
    const m = await mutation(ctx, `POST /v1/proposals/${id}/${action}`, b);
    const v = b.expected_version ?? null;
    const r = await one(ctx, () =>
      action === "accept"
        ? sql()`select game_api.accept_proposal(${actor.userId}::uuid, ${m.key}, ${m.hash}, ${id}::uuid, ${v}::int) as r`
        : action === "reject"
        ? sql()`select game_api.reject_proposal(${actor.userId}::uuid, ${m.key}, ${m.hash}, ${id}::uuid, ${v}::int) as r`
        : sql()`select game_api.cancel_proposal(${actor.userId}::uuid, ${m.key}, ${m.hash}, ${id}::uuid, ${v}::int) as r`);
    return opResponse(r, ctx.correlationId);
  });
}

router.on("POST", "/v1/forced-purchases", async (ctx) => {
  const actor = await authenticate(ctx.req);
  await preflight(actor, "mutation", ctx);
  const b = parse(schemas.forced, await readJson(ctx.req));
  const m = await mutation(ctx, "POST /v1/forced-purchases", b);
  const r = await one(ctx, () => sql()`
    select game_api.forced_purchase(${actor.userId}::uuid, ${m.key}, ${m.hash}, ${b.property_id}::uuid, ${b.reason},
             ${b.proposal_id ?? null}::uuid, ${b.proof_id}::uuid, ${b.expected_version ?? null}::bigint) as r`);
  return opResponse(r, ctx.correlationId);
});

router.on("GET", "/v1/portfolio", async (ctx) => {
  const actor = await authenticate(ctx.req);
  await preflight(actor, "read", ctx);
  const q = parse(schemas.page, query(ctx.url));
  return json(await one(ctx, () =>
    sql()`select game_api.portfolio(${actor.userId}::uuid, ${q.cursor ?? null}, ${q.limit ?? 50}::int) as r`));
});

router.on("POST", "/v1/income/collect", async (ctx) => {
  const actor = await authenticate(ctx.req);
  await preflight(actor, "mutation", ctx);
  const b = parse(schemas.collect, await readJson(ctx.req));
  const ids = [...new Set(b.ownership_ids)].sort();
  const m = await mutation(ctx, "POST /v1/income/collect", { ownership_ids: ids });
  const r = await one(ctx, () => sql()`
    select game_api.collect_income(${actor.userId}::uuid, ${m.key}, ${m.hash}, ${uuidArrayLiteral(ids)}::uuid[]) as r`);
  return opResponse(r, ctx.correlationId);
});

router.on("GET", "/v1/wallet", async (ctx) => {
  const actor = await authenticate(ctx.req);
  await preflight(actor, "read", ctx);
  const q = parse(schemas.page, query(ctx.url));
  return json(await one(ctx, () =>
    sql()`select game_api.wallet(${actor.userId}::uuid, ${q.cursor ?? null}, ${q.limit ?? 50}::int) as r`));
});

router.on("GET", "/v1/history", async (ctx) => {
  const actor = await authenticate(ctx.req);
  await preflight(actor, "read", ctx);
  const q = parse(schemas.history, query(ctx.url));
  return json(await one(ctx, () =>
    sql()`select game_api.history(${actor.userId}::uuid, ${q.kind}, ${q.cursor ?? null}, ${q.limit ?? 50}::int) as r`));
});

router.on("GET", "/v1/operations/:key", async (ctx) => {
  const actor = await authenticate(ctx.req);
  await preflight(actor, "read", ctx);
  const key = parse(idempotencyKey, ctx.params.key);
  const r = await one(ctx, () => sql()`select game_api.get_operation(${actor.userId}::uuid, ${key}) as r`);
  return json(r, r.found ? 200 : 404);
});

router.on("POST", "/v1/devices", async (ctx) => {
  const actor = await authenticate(ctx.req);
  await preflight(actor, "mutation", ctx);
  const b = parse(schemas.device, await readJson(ctx.req));
  return json(await one(ctx, () => sql()`select game_api.register_device(${actor.userId}::uuid, ${b.token}, ${b.platform}) as r`));
});

router.on("POST", "/v1/devices/unregister", async (ctx) => {
  const actor = await authenticate(ctx.req);
  await preflight(actor, "mutation", ctx);
  const b = parse(schemas.deviceRemove, await readJson(ctx.req));
  return json(await one(ctx, () => sql()`select game_api.unregister_device(${actor.userId}::uuid, ${b.token}) as r`));
});

router.on("GET", "/v1/notifications", async (ctx) => {
  const actor = await authenticate(ctx.req);
  await preflight(actor, "read", ctx);
  const q = parse(schemas.page, query(ctx.url));
  return json(await one(ctx, () =>
    sql()`select game_api.list_notifications(${actor.userId}::uuid, ${q.cursor ?? null}, ${q.limit ?? 50}::int) as r`));
});

router.on("POST", "/v1/notifications/read", async (ctx) => {
  const actor = await authenticate(ctx.req);
  await preflight(actor, "mutation", ctx);
  const b = parse(schemas.notificationsRead, await readJson(ctx.req));
  const ids = b.ids ? uuidArrayLiteral(b.ids) : null;
  return json(await one(ctx, () => sql()`select game_api.mark_notifications_read(${actor.userId}::uuid, ${ids}::uuid[]) as r`));
});

router.on("POST", "/v1/reports", async (ctx) => {
  const actor = await authenticate(ctx.req);
  await preflight(actor, "report", ctx);
  const b = parse(schemas.report, await readJson(ctx.req));
  return json(await one(ctx, () =>
    sql()`select game_api.create_report(${actor.userId}::uuid, ${b.category}, ${b.property_id ?? null}::uuid, ${b.message}) as r`), 201);
});

router.on("POST", "/v1/account/deletion", async (ctx) => {
  const actor = await authenticate(ctx.req);
  requireRecentAuth(actor);
  await preflight(actor, "mutation", ctx);
  const b = parse(schemas.deletion, await readJson(ctx.req));
  const m = await mutation(ctx, "POST /v1/account/deletion", b);
  const r = await one(ctx, () => sql()`select game_api.request_account_deletion(${actor.userId}::uuid, ${m.key}, ${m.hash}) as r`);
  return opResponse(r, ctx.correlationId);
});

Deno.serve(async (req) => {
  const url = new URL(req.url);
  const correlationId = req.headers.get("x-correlation-id")?.slice(0, 64) || crypto.randomUUID();
  const ctx: Ctx = { req, url, correlationId, params: {}, started: performance.now() };
  const path = logicalPath(url);
  let res: Response;
  try {
    const m = router.match(req.method, path);
    if (m === null) throw new ApiError(404, "NOT_FOUND", "Rota não encontrada");
    if (m === "method") throw new ApiError(405, "VALIDATION_FAILED", "Método não permitido");
    ctx.params = m.params;
    res = await m.handler(ctx);
  } catch (e) {
    if (e instanceof ApiError) {
      res = errorResponse(e, correlationId);
    } else {
      log("error", "api.unhandled", { correlation_id: correlationId, name: (e as Error)?.name });
      res = errorResponse(new ApiError(500, "INTERNAL", "Erro interno"), correlationId);
    }
  }
  res.headers.set("x-correlation-id", correlationId);
  log(res.status >= 500 ? "error" : "info", "api.request", {
    correlation_id: correlationId,
    method: req.method,
    route: path.replace(/[0-9a-f]{8}-[0-9a-f-]{27}/gi, ":id"),
    status: res.status,
    duration_ms: Math.round(performance.now() - ctx.started),
  });
  return res;
});
