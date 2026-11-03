import { describe, expect, it, vi } from "vitest";

vi.mock("./supabase", () => ({ adminApiUrl: "https://example.test/functions/v1/admin", publishableKey: "pk", supabase: {} }));

const { AdminError, buildUrl, interpret, newIdempotencyKey } = await import("./api");

describe("interpret", () => {
  it("devolve o corpo em sucesso", () => {
    expect(interpret(200, { ok: true, operation_id: "x" })).toEqual({ ok: true, operation_id: "x" });
    expect(interpret(200, [1, 2])).toEqual([1, 2]);
  });
  it("converte HTTP de erro em AdminError com o código do servidor", () => {
    try {
      interpret(403, { error: { code: "MFA_REQUIRED", message: "Ative o MFA" } }, "corr-1");
      expect.unreachable();
    } catch (e) {
      expect(e).toBeInstanceOf(AdminError);
      const err = e as InstanceType<typeof AdminError>;
      expect(err.status).toBe(403);
      expect(err.code).toBe("MFA_REQUIRED");
      expect(err.correlationId).toBe("corr-1");
    }
  });
  it("trata resultado de operação {ok:false} com HTTP 200 como erro", () => {
    expect(() => interpret(200, { ok: false, error: { code: "RECONCILIATION_PENDING", message: "diverge" } }))
      .toThrowError(expect.objectContaining({ code: "RECONCILIATION_PENDING", status: 409 }) as Error);
  });
  it("usa código genérico quando o corpo não é JSON", () => {
    expect(() => interpret(401, null)).toThrowError(expect.objectContaining({ code: "UNAUTHORIZED" }) as Error);
    expect(() => interpret(502, null)).toThrowError(expect.objectContaining({ code: "INTERNAL" }) as Error);
  });
});

describe("buildUrl", () => {
  it("omite parâmetros vazios", () => {
    expect(buildUrl("https://x.test/admin", "/v1/proposals", { status: "", user: undefined, q: "a b" }))
      .toBe("https://x.test/admin/v1/proposals?q=a+b");
  });
});

describe("newIdempotencyKey", () => {
  it("segue o formato aceito pelo servidor", () => {
    const k = newIdempotencyKey();
    expect(k).toMatch(/^[A-Za-z0-9_.:-]{8,128}$/);
    expect(newIdempotencyKey()).not.toBe(k);
  });
});
