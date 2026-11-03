// Leitura centralizada de variáveis. Segredos nunca são registrados em log.
export function env(name: string, fallback?: string): string {
  const v = Deno.env.get(name) ?? fallback;
  if (v === undefined || v === "") throw new Error(`variável de ambiente ausente: ${name}`);
  return v;
}

export function optionalEnv(name: string): string | undefined {
  const v = Deno.env.get(name);
  return v === undefined || v === "" ? undefined : v;
}

export const APP_ENV = optionalEnv("APP_ENV") ?? "local";
