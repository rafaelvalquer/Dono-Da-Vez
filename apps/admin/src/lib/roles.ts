export type Role = "support" | "geo" | "finance" | "superadmin";

/** Espelha game.require_admin: superadmin passa em tudo. A UI só esconde; quem decide é o servidor. */
export function allowed(roles: Role[], needed: Role[]): boolean {
  return roles.includes("superadmin") || needed.some((r) => roles.includes(r));
}
