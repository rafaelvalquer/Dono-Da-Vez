import { describe, expect, it } from "vitest";
import { adjustmentError, money, shortId } from "./format";

describe("money", () => {
  it("trunca em 2 casas sem arredondar para cima", () => {
    expect(money("1234.56789")).toBe("1.234,56");
    expect(money("0.009")).toBe("0,00");
  });
  it("aceita números vindos de to_jsonb", () => {
    expect(money(1200)).toBe("1.200,00");
  });
  it("mostra sinal negativo apenas para valores diferentes de zero", () => {
    expect(money("-12.5")).toBe("−12,50");
    expect(money("-0.001")).toBe("0,00");
  });
  it("trata vazio e texto inesperado", () => {
    expect(money(null)).toBe("—");
    expect(money("abc")).toBe("abc");
  });
});

describe("adjustmentError", () => {
  it("aceita valores com até 8 casas e sinal", () => {
    expect(adjustmentError("25")).toBeNull();
    expect(adjustmentError("-10.12345678")).toBeNull();
    expect(adjustmentError("3,50")).toBeNull();
  });
  it("recusa zero, excesso de casas e formatos inválidos", () => {
    expect(adjustmentError("0")).toMatch(/zero/);
    expect(adjustmentError("1.123456789")).not.toBeNull();
    expect(adjustmentError("1e5")).not.toBeNull();
    expect(adjustmentError("")).not.toBeNull();
  });
});

describe("shortId", () => {
  it("encurta uuids", () => {
    expect(shortId("123e4567-e89b-12d3-a456-426614174000")).toBe("123e4567…");
    expect(shortId("abc")).toBe("abc");
  });
});
