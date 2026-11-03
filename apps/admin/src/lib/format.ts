const brl = new Intl.NumberFormat("pt-BR", { minimumFractionDigits: 2, maximumFractionDigits: 2 });

/** Dinheiro do jogo chega como string decimal; exibe truncado em 2 casas (nunca arredonda para cima). */
export function money(v: unknown): string {
  if (v === null || v === undefined || v === "") return "—";
  const s = String(v);
  if (!/^-?\d+(\.\d+)?$/.test(s)) return s;
  const neg = s.startsWith("-");
  const [int = "0", frac = ""] = s.replace("-", "").split(".");
  const cents = (frac + "00").slice(0, 2);
  const n = Number(`${int}.${cents}`);
  return (neg && n !== 0 ? "−" : "") + brl.format(n);
}

export function dateTime(v: unknown): string {
  if (!v) return "—";
  const d = new Date(String(v));
  if (Number.isNaN(d.getTime())) return String(v);
  return d.toLocaleString("pt-BR", { dateStyle: "short", timeStyle: "short" });
}

export function shortId(v: unknown): string {
  const s = String(v ?? "");
  return s.length > 12 ? `${s.slice(0, 8)}…` : s;
}

export const CATEGORY_LABEL: Record<string, string> = {
  house_small: "Casa pequena",
  house_large: "Casa grande",
  commercial: "Comércio",
  residential_building: "Prédio residencial",
};

/** Valida um valor de ajuste: até 13 dígitos inteiros e 8 decimais, diferente de zero. */
export function adjustmentError(v: string): string | null {
  const s = v.trim().replace(",", ".");
  if (!/^-?\d{1,13}(\.\d{1,8})?$/.test(s)) return "Use número com ponto, até 8 casas (ex.: -12.50)";
  if (Number(s) === 0) return "O ajuste não pode ser zero";
  return null;
}
