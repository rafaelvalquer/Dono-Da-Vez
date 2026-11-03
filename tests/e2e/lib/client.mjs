// Cliente HTTP do jogo como o app o usa: Bearer do Auth, Idempotency-Key por intenção de mutação,
// x-correlation-id por requisição. Nunca envia user_id: o ator vem do JWT.
import { randomUUID } from "node:crypto";

export const newKey = (label = "e2e") => `${label}-${randomUUID()}`;

export class Api {
  constructor(baseUrl) {
    this.baseUrl = baseUrl.replace(/\/$/, "");
  }

  withBase(baseUrl) {
    return new Api(baseUrl);
  }

  async call(method, path, { token, body, key, query, headers = {}, signal } = {}) {
    const url = new URL(this.baseUrl + path);
    for (const [k, v] of Object.entries(query ?? {})) if (v !== undefined && v !== null) url.searchParams.set(k, String(v));
    const h = { "x-correlation-id": `e2e-${randomUUID()}`, ...headers };
    if (token) h.authorization = `Bearer ${token}`;
    if (key) h["idempotency-key"] = key;
    if (body !== undefined) h["content-type"] = "application/json";
    const res = await fetch(url, { method, headers: h, body: body === undefined ? undefined : JSON.stringify(body), signal });
    const text = await res.text();
    let json = null;
    try {
      json = text ? JSON.parse(text) : null;
    } catch {
      json = { raw: text };
    }
    return { status: res.status, body: json, headers: res.headers };
  }
}

export class Player {
  constructor(api, { userId, token, email, nickname }) {
    Object.assign(this, { api, userId, token, email, nickname });
  }

  /** Mesmo jogador falando por outra base (ex.: proxy de falhas). */
  via(api) {
    return new Player(api, this);
  }

  get(path, query, opts = {}) {
    return this.api.call("GET", path, { token: this.token, query, ...opts });
  }

  post(path, body = {}, opts = {}) {
    return this.api.call("POST", path, { token: this.token, body, ...opts });
  }

  patch(path, body = {}, opts = {}) {
    return this.api.call("PATCH", path, { token: this.token, body, ...opts });
  }

  async bootstrap() {
    const r = await this.get("/v1/bootstrap", { app_version: 1 });
    if (r.status !== 200) throw new Error(`bootstrap ${r.status}: ${JSON.stringify(r.body)}`);
    return r.body;
  }

  async wallet() {
    const r = await this.get("/v1/wallet");
    if (r.status !== 200) throw new Error(`wallet ${r.status}: ${JSON.stringify(r.body)}`);
    return r.body.wallet;
  }

  /** Prova de presença no ponto de interação do imóvel (GPS bom, sem movimento). */
  presence(property, { offsetM = 0, accuracy = 5 } = {}) {
    return this.post("/v1/presence", {
      property_id: property.id, lat: property.lat + offsetM / 111_320, lon: property.lon,
      accuracy_m: accuracy, fix_age_ms: 800, speed_mps: 0, nonce: `n${randomUUID().replaceAll("-", "")}`, mock_location: false,
    });
  }

  async proofId(property) {
    const r = await this.presence(property);
    if (r.status !== 200 || !r.body.ok) throw new Error(`presença recusada ${r.status}: ${JSON.stringify(r.body)}`);
    return r.body.proof_id;
  }

  async buy(property, { key = newKey("buy"), proofId, signal } = {}) {
    const proof_id = proofId ?? (await this.proofId(property));
    return this.post("/v1/purchases", { property_id: property.id, proof_id }, { key, signal });
  }

  async propose(property, ownershipId, amount, { key = newKey("prop"), proofId } = {}) {
    const proof_id = proofId ?? (await this.proofId(property));
    return this.post("/v1/proposals", { property_id: property.id, ownership_id: ownershipId, amount, proof_id }, { key });
  }
}
