// Consumidor da outbox (disparado pelo pg_cron a cada minuto). Push falho nunca altera
// resultado econômico: o evento é reprocessado com backoff até virar "dead".
import { db } from "../_shared/db.ts";
import { env } from "../_shared/env.ts";
import { json, log } from "../_shared/http.ts";
import { RetryableError, sendToToken, serviceAccount } from "./fcm.ts";
import { render } from "./templates.ts";

const sql = () => db("DV_BACKEND_DB_URL");

interface OutboxEvent {
  id: number;
  event_id: string;
  topic: string;
  user_id: string | null;
  payload: Record<string, unknown>;
  attempts: number;
  tokens: string[];
  prefs: Record<string, boolean> | null;
}

async function handlePush(ev: OutboxEvent): Promise<string[]> {
  const msg = render(String(ev.payload.type ?? ""), ev.payload, ev.prefs);
  if (!msg || ev.tokens.length === 0) return [];
  const sa = serviceAccount();
  if (!sa) {
    log("info", "push.mock", { event_id: ev.event_id, type: ev.payload.type, tokens: ev.tokens.length });
    return [];
  }
  const invalid: string[] = [];
  let retryable: unknown;
  for (const token of ev.tokens) {
    try {
      if ((await sendToToken(sa, token, msg)) === "invalid_token") invalid.push(token);
    } catch (e) {
      retryable = e;
    }
  }
  if (retryable && invalid.length < ev.tokens.length) {
    // Repassa os tokens inválidos já detectados e agenda nova tentativa.
    await sql()`select game_api.outbox_complete(${ev.id}::bigint, false, ${String((retryable as Error).message)}, ${`{${invalid.join(",")}}`}::text[])`;
    throw new RetryableError("handled");
  }
  return invalid;
}

async function handleAuthDelete(ev: OutboxEvent) {
  const id = String(ev.payload.auth_user_id ?? "");
  if (!/^[0-9a-f-]{36}$/i.test(id)) return;
  const key = env("SUPABASE_SERVICE_ROLE_KEY");
  const res = await fetch(`${env("SUPABASE_URL")}/auth/v1/admin/users/${id}`, {
    method: "DELETE",
    headers: { apikey: key, authorization: `Bearer ${key}` },
  });
  if (!res.ok && res.status !== 404) throw new RetryableError(`auth ${res.status}`);
}

Deno.serve(async (req) => {
  if (req.method !== "POST" || req.headers.get("x-cron-secret") !== env("DV_CRON_SECRET")) {
    return json({ ok: false, error: { code: "UNAUTHORIZED", message: "não autorizado" } }, 401);
  }
  const started = Date.now();
  let processed = 0, failed = 0;
  while (Date.now() - started < 40_000) {
    const [row] = await sql()`select game_api.outbox_claim(50, 90) as r`;
    const events = row.r as OutboxEvent[];
    if (events.length === 0) break;
    for (const ev of events) {
      try {
        let invalid: string[] = [];
        if (ev.topic === "push") invalid = await handlePush(ev);
        else if (ev.topic === "auth.delete_user") await handleAuthDelete(ev);
        await sql()`select game_api.outbox_complete(${ev.id}::bigint, true, null, ${`{${invalid.join(",")}}`}::text[])`;
        processed++;
      } catch (e) {
        failed++;
        if (!(e instanceof RetryableError && e.message === "handled")) {
          await sql()`select game_api.outbox_complete(${ev.id}::bigint, false, ${String((e as Error)?.message ?? "erro")}, '{}'::text[])`;
        }
        log("warn", "outbox.retry", { event_id: ev.event_id, topic: ev.topic, attempts: ev.attempts });
      }
    }
  }
  log("info", "outbox.run", { processed, failed, duration_ms: Date.now() - started });
  return json({ ok: true, processed, failed });
});
