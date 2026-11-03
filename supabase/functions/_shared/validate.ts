import { z } from "./deps.ts";
import { ApiError } from "./errors.ts";

export const uuid = z.string().uuid();
export const money = z.string().regex(/^\d{1,13}(\.\d{1,2})?$/, "use até duas casas decimais");
export const idempotencyKey = z.string().regex(/^[A-Za-z0-9_.:-]{8,128}$/);

export function parse<T extends z.ZodTypeAny>(schema: T, value: unknown): z.infer<T> {
  const r = schema.safeParse(value);
  if (!r.success) {
    const issues = r.error.issues.slice(0, 5).map((i) => ({ path: i.path.join("."), message: i.message }));
    throw new ApiError(422, "VALIDATION_FAILED", "Dados inválidos", { issues });
  }
  return r.data;
}

export function query(url: URL): Record<string, string> {
  return Object.fromEntries(url.searchParams.entries());
}

export const schemas = {
  bootstrap: z.object({ app_version: z.coerce.number().int().min(0).max(1_000_000).optional() }),
  activity: z.object({ source: z.enum(["foreground", "resume", "heartbeat"]).default("heartbeat") }),
  map: z.object({
    bbox: z.string().regex(/^-?\d+(\.\d+)?(,-?\d+(\.\d+)?){3}$/),
    zoom: z.coerce.number().min(0).max(24),
    cursor: uuid.optional(),
    limit: z.coerce.number().int().min(1).max(500).optional(),
  }),
  nearby: z.object({
    lat: z.coerce.number().min(-90).max(90),
    lon: z.coerce.number().min(-180).max(180),
    radius: z.coerce.number().min(1).max(150).optional(),
  }),
  presence: z.object({
    property_id: uuid,
    lat: z.number().min(-90).max(90),
    lon: z.number().min(-180).max(180),
    accuracy_m: z.number().positive().max(10_000),
    fix_age_ms: z.number().int().min(0).max(3_600_000),
    speed_mps: z.number().min(0).max(1_000).optional(),
    nonce: z.string().regex(/^[A-Za-z0-9_-]{16,128}$/),
    mock_location: z.boolean().default(false),
    integrity_verdict: z.string().max(64).optional(),
  }),
  purchase: z.object({ property_id: uuid, proof_id: uuid, expected_version: z.number().int().positive().optional() }),
  proposal: z.object({ property_id: uuid, ownership_id: uuid, amount: money, proof_id: uuid }),
  proposalAction: z.object({ expected_version: z.number().int().positive().optional() }),
  proposalList: z.object({
    direction: z.enum(["received", "sent"]),
    status: z.enum(["open", "closed", "all"]).default("all"),
    cursor: z.string().max(200).optional(),
    limit: z.coerce.number().int().min(1).max(100).optional(),
  }),
  forced: z.object({
    property_id: uuid,
    reason: z.enum(["silence", "inactivity"]),
    proposal_id: uuid.optional(),
    proof_id: uuid,
    expected_version: z.number().int().positive().optional(),
  }).refine((v) => v.reason !== "silence" || !!v.proposal_id, { message: "proposal_id obrigatório para silêncio", path: ["proposal_id"] }),
  page: z.object({ cursor: z.string().max(200).optional(), limit: z.coerce.number().int().min(1).max(100).optional() }),
  history: z.object({
    kind: z.enum(["all", "purchases", "sales", "proposals", "income"]).default("all"),
    cursor: z.string().max(200).optional(),
    limit: z.coerce.number().int().min(1).max(100).optional(),
  }),
  collect: z.object({ ownership_ids: z.array(uuid).min(1).max(50) }),
  device: z.object({ token: z.string().min(20).max(4096), platform: z.enum(["android", "ios"]).default("android") }),
  deviceRemove: z.object({ token: z.string().min(20).max(4096) }),
  notificationsRead: z.object({ ids: z.array(uuid).max(200).optional() }),
  report: z.object({
    category: z.enum(["map_error", "wrong_category", "missing_property", "abuse", "bug", "account", "other"]),
    property_id: uuid.optional(),
    message: z.string().trim().min(5).max(2000),
  }),
  profile: z.object({
    nickname: z.string().regex(/^[A-Za-z0-9_.-]{3,24}$/).optional(),
    avatar_id: z.string().regex(/^avatar_[0-9]{2}$/).optional(),
    notification_prefs: z.object({ proposals: z.boolean(), transfers: z.boolean(), reminders: z.boolean() }).partial().optional(),
  }),
  deletion: z.object({ confirm: z.literal("EXCLUIR") }),
};
