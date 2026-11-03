// Erros de domínio → HTTP. Mensagens orientam a ação sem expor SQL ou segredos.
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly detail: Record<string, unknown> = {},
  ) {
    super(message);
  }
}

const STATUS: Record<string, number> = {
  VALIDATION_FAILED: 422,
  INVALID_AMOUNT: 422,
  AREA_TOO_LARGE: 422,
  OUT_OF_RANGE: 422,
  LOCATION_STALE: 422,
  LOCATION_INACCURATE: 422,
  LOCATION_SUSPICIOUS: 422,
  SPEED_LIMIT: 422,
  PRESENCE_REQUIRED: 422,
  NONCE_REUSED: 422,
  UNAUTHORIZED: 401,
  REAUTH_REQUIRED: 401,
  FORBIDDEN: 403,
  MFA_REQUIRED: 403,
  ACCOUNT_SUSPENDED: 403,
  ACCOUNT_CLOSED: 403,
  EMAIL_NOT_CONFIRMED: 403,
  NOT_FOUND: 404,
  PROPERTY_NOT_FOUND: 404,
  PROPOSAL_NOT_FOUND: 404,
  PROFILE_NOT_FOUND: 404,
  WALLET_NOT_FOUND: 404,
  REGION_NOT_FOUND: 404,
  RATE_LIMITED: 429,
  PROPOSAL_COOLDOWN: 429,
  WALLET_LOCKED: 423,
  TRANSIENT: 503,
  INTERNAL: 500,
};

export function statusFor(code: string): number {
  return STATUS[code] ?? 409; // demais erros de domínio são conflitos de estado
}

export const TRANSIENT_SQLSTATES = new Set(["40001", "40P01", "55P03", "57014", "08006", "08003", "08001"]);
