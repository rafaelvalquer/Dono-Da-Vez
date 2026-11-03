import { jose } from "../_shared/deps.ts";

interface ServiceAccount { client_email: string; private_key: string; project_id: string }
let cached: { token: string; exp: number } | undefined;

export function serviceAccount(): ServiceAccount | undefined {
  const raw = Deno.env.get("FCM_SERVICE_ACCOUNT_JSON");
  if (!raw) return undefined;
  return JSON.parse(raw) as ServiceAccount;
}

async function accessToken(sa: ServiceAccount): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  if (cached && cached.exp - 60 > now) return cached.token;
  const key = await jose.importPKCS8(sa.private_key, "RS256");
  const assertion = await new jose.SignJWT({ scope: "https://www.googleapis.com/auth/firebase.messaging" })
    .setProtectedHeader({ alg: "RS256", typ: "JWT" })
    .setIssuer(sa.client_email)
    .setAudience("https://oauth2.googleapis.com/token")
    .setIssuedAt(now)
    .setExpirationTime(now + 3600)
    .sign(key);
  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion }),
  });
  if (!res.ok) throw new Error(`oauth ${res.status}`);
  const body = await res.json() as { access_token: string; expires_in: number };
  cached = { token: body.access_token, exp: now + body.expires_in };
  return body.access_token;
}

export interface PushMessage { title: string; body: string; data: Record<string, string>; channel: string }
export type SendResult = "sent" | "invalid_token";

export class RetryableError extends Error {}

export async function sendToToken(sa: ServiceAccount, token: string, msg: PushMessage): Promise<SendResult> {
  const res = await fetch(`https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`, {
    method: "POST",
    headers: { "content-type": "application/json", authorization: `Bearer ${await accessToken(sa)}` },
    body: JSON.stringify({
      message: {
        token,
        notification: { title: msg.title, body: msg.body },
        data: msg.data,
        android: { priority: "HIGH", notification: { channel_id: msg.channel } },
      },
    }),
  });
  if (res.ok) return "sent";
  const text = await res.text();
  if (res.status === 404 || (res.status === 400 && /UNREGISTERED|INVALID_ARGUMENT|registration-token/i.test(text))) {
    return "invalid_token";
  }
  if (res.status === 401) cached = undefined;
  throw new RetryableError(`fcm ${res.status}`);
}
