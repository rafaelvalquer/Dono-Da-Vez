import { describe, expect, it, vi } from "vitest";

vi.mock("../lib/supabase", () => ({ adminApiUrl: "https://example.test", publishableKey: "pk", supabase: {} }));

const { patchErrors } = await import("./Config");

const current = { initial_balance: "5000.00", interaction_radius_m: 80, allow_mock_location: false, _version: 3 };

describe("patchErrors", () => {
  it("aceita chaves existentes com o mesmo tipo", () => {
    expect(patchErrors(current, { interaction_radius_m: 100, initial_balance: "6000.00" })).toEqual([]);
  });
  it("recusa chaves desconhecidas, _version e tipos trocados", () => {
    expect(patchErrors(current, { nova: 1 })).toEqual(["Chave desconhecida: nova"]);
    expect(patchErrors(current, { _version: 9 })).toEqual(["Chave desconhecida: _version"]);
    expect(patchErrors(current, { interaction_radius_m: "100" })[0]).toMatch(/Tipo inválido/);
  });
  it("recusa patch vazio ou que não é objeto", () => {
    expect(patchErrors(current, {})).toEqual(["Patch vazio"]);
    expect(patchErrors(current, [1])).toEqual(["O patch deve ser um objeto JSON"]);
  });
});
