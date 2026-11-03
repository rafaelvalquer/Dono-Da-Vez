// Textos de push (pt-BR). Payload mínimo: somente IDs e valores do próprio destinatário.
// Prazos exatos são mostrados no app (servidor é o relógio da regra), não na notificação.
import type { PushMessage } from "./fcm.ts";

type Payload = Record<string, unknown>;
const coins = (v: unknown) => (typeof v === "string" ? `${v} moedas` : "moedas");

const TEMPLATES: Record<string, { pref: string; channel: string; render: (p: Payload) => [string, string] }> = {
  "proposal.received": { pref: "proposals", channel: "proposals", render: (p) => ["Nova proposta", `Você recebeu uma oferta de ${coins(p.amount)}. Responda dentro do prazo.`] },
  "proposal.expiring": { pref: "reminders", channel: "proposals", render: (p) => ["Proposta perto do prazo", `A oferta de ${coins(p.amount)} vence em breve. Aceite ou recuse no app.`] },
  "proposal.accepted": { pref: "proposals", channel: "proposals", render: () => ["Proposta aceita", "O imóvel agora é seu. Confira no portfólio."] },
  "proposal.rejected": { pref: "proposals", channel: "proposals", render: () => ["Proposta recusada", "O valor reservado voltou para o seu saldo disponível."] },
  "proposal.expired": { pref: "proposals", channel: "proposals", render: (p) => ["Prazo encerrado", `O dono não respondeu. Você pode comprar por ${coins(p.forced_price)} no local, dentro da janela exibida no app.`] },
  "proposal.unanswered": { pref: "proposals", channel: "proposals", render: () => ["Proposta sem resposta", "O prazo terminou. O comprador pode concluir a compra com adicional de 20% por tempo limitado."] },
  "proposal.superseded": { pref: "proposals", channel: "proposals", render: () => ["Proposta encerrada", "O imóvel mudou de dono e a sua reserva foi liberada."] },
  "proposal.cancelled_by_support": { pref: "proposals", channel: "proposals", render: () => ["Proposta cancelada", "O suporte cancelou uma proposta e liberou a reserva."] },
  "property.sold": { pref: "transfers", channel: "transfers", render: (p) => ["Imóvel vendido", `Você recebeu ${coins(p.price)} pela venda.`] },
  "property.forced_sale": { pref: "transfers", channel: "transfers", render: (p) => ["Imóvel comprado sem aceite", `Você recebeu ${coins(p.price)}, incluindo o adicional de 20%.`] },
  "property.compensated": { pref: "transfers", channel: "transfers", render: (p) => ["Imóvel encerrado com compensação", `Você recebeu ${coins(p.compensation)} de compensação.`] },
  "wallet.adjustment": { pref: "transfers", channel: "transfers", render: () => ["Ajuste na carteira", "Um ajuste registrado foi aplicado. Veja o extrato."] },
  "risk.review_decided": { pref: "proposals", channel: "proposals", render: () => ["Revisão concluída", "A revisão de uma negociação foi concluída."] },
  "report.updated": { pref: "transfers", channel: "support", render: (p) => ["Atualização do chamado", `O chamado ${String(p.protocol ?? "")} foi atualizado.`] },
};

export function render(type: string, payload: Payload, prefs: Record<string, boolean> | null): PushMessage | null {
  const t = TEMPLATES[type];
  if (!t) return null;
  if (prefs && prefs[t.pref] === false) return null;
  const [title, body] = t.render(payload);
  const data: Record<string, string> = { type };
  for (const k of ["notification_id", "proposal_id", "property_id", "trade_id"]) {
    if (typeof payload[k] === "string") data[k] = payload[k] as string;
  }
  return { title, body, data, channel: t.channel };
}
