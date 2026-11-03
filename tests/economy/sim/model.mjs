// Modelo econômico por agentes, determinístico (semente fixa), em centavos inteiros.
// Reproduz as regras de docs/rules.md: saldo inicial único, renda de 1%/dia sobre o preço pago, compra do
// sistema pelo preço da categoria, proposta com reserva e 24 h de resposta, compra sem aceite +20% (silêncio
// com janela de 24 h ou dono inativo há 14 dias), proteção de 24 h após transferência e pontuação de risco.
// Passo = 1 dia. Não substitui os testes SQL (tests/database): serve para inflação, concentração e abuso.

export function rng(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const MIX = { house_small: 0.6, house_large: 0.15, commercial: 0.2, residential_building: 0.05 };

export const SCENARIOS = {
  baseline: {
    description: "Crescimento orgânico (150 jogadores iniciais, +12/dia, 40% abandonam), sem abuso coordenado",
    initialPlayers: 150, dailySignups: 12, properties: 3000, cells: 30, categoryMix: MIX, abuse: null,
  },
  abuse: {
    description: "Além do orgânico, um jogador cria 3 contas/dia (e-mails distintos) por 55 dias para canalizar o saldo inicial: "
      + "cada conta nova compra uma casa dele a 2,9× o preço da categoria e ele a recompra pelo preço base",
    initialPlayers: 150, dailySignups: 12, properties: 3000, cells: 30, categoryMix: MIX,
    abuse: { startDay: 5, endDay: 60, sybilsPerDay: 3, priceMultiplier: 2.9 },
  },
};

const ceilCents = (v) => Math.ceil(v - 1e-9);

export function simulate({ days, seed = 42, scenario = "baseline", config: cfg, overrides = {} }) {
  const sc = { ...SCENARIOS[scenario], ...overrides };
  const rand = rng(seed);
  const pick = (arr) => arr[Math.floor(rand() * arr.length)];
  const catByCode = Object.fromEntries(cfg.categories.map((c) => [c.code, c]));

  const ledger = { issuedInitial: 0, issuedIncome: 0, treasury: 0 };
  const players = [];
  const props = [];
  const proposals = [];
  const trades = [];
  const counters = { system: 0, voluntary: 0, forcedSilence: 0, forcedInactivity: 0, rejected: 0, expired: 0, superseded: 0, riskSignals: 0, riskBlocked: 0 };
  const daily = [];

  // Índices
  const owned = new Map(); // jogador → Set(imóvel)
  const pendingBySeller = new Map();
  const openByBuyer = new Map(); // pendentes + vencidas com janela
  const openByProp = new Map();
  const pairDays = new Map(); // "comprador>vendedor" → dias
  const tradeDays = new Map(); // jogador → dias
  const setOf = (m, k) => { let s = m.get(k); if (!s) { s = new Set(); m.set(k, s); } return s; };

  const mix = Object.entries(sc.categoryMix);
  for (let i = 0; i < sc.properties; i++) {
    let r = rand(), code = mix[0][0];
    for (const [c, w] of mix) { if (r < w) { code = c; break; } r -= w; }
    props.push({ id: i, cat: catByCode[code], cell: i % sc.cells, owner: null, price: 0, frac: 0, lastTransfer: -Infinity, ownership: 0 });
  }
  const propsByCell = Array.from({ length: sc.cells }, () => []);
  for (const p of props) propsByCell[p.cell].push(p);

  function join(day, kind = "player", home = null) {
    const archetype = kind !== "player" ? kind : rand() < 0.25 ? "active" : "casual";
    const pl = {
      id: players.length, kind: archetype, joined: day, home: home ?? Math.floor(rand() * sc.cells),
      activity: archetype === "active" ? 0.85 : archetype === "casual" ? 0.35 : 1,
      churnDay: archetype === "sybil" ? day + 1 : day + (rand() < 0.4 ? Math.floor(10 + rand() * 60) : Infinity),
      reservation: 1.05 + rand() * 0.5, aggressiveness: rand(),
      balance: cfg.initialBalance, reserved: 0, lastActive: day, cooldown: new Map(),
    };
    ledger.issuedInitial += cfg.initialBalance;
    players.push(pl);
    return pl;
  }

  const available = (pl) => pl.balance - pl.reserved;
  const near = (pl) => [pl.home, (pl.home + 1) % sc.cells, (pl.home + sc.cells - 1) % sc.cells].flatMap((c) => propsByCell[c]);

  function close(pr, status) {
    if (pr.status === "pending") {
      players[pr.buyer].reserved -= pr.amount;
      pendingBySeller.get(pr.seller)?.delete(pr);
    }
    pr.status = status;
    if (status !== "expired") { openByBuyer.get(pr.buyer)?.delete(pr); openByProp.get(pr.prop)?.delete(pr); }
  }

  function risk(buyer, seller, p, amount, reference, day) {
    let score = 0;
    const recent = (k) => (pairDays.get(k) ?? []).filter((d) => d > day - 30).length;
    if (recent(`${seller.id}>${buyer.id}`) > 0) score += 40;
    if (recent(`${buyer.id}>${seller.id}`) + recent(`${seller.id}>${buyer.id}`) >= 3) score += 30;
    if (amount > p.cat.basePrice * 10) score += 40;
    else if (amount > p.cat.basePrice * 3) score += 20;
    if (day - buyer.joined < 7 && amount >= 0.5 * Math.max(buyer.balance, 1) && amount > reference * 1.5) score += 25;
    const vel = [buyer.id, seller.id].reduce((s, id) => s + (tradeDays.get(id) ?? []).filter((d) => d === day).length, 0);
    if (vel >= 10) score += 20;
    if (score > 0) counters.riskSignals += 1;
    const blocked = score >= cfg.riskBlockScore;
    if (blocked) counters.riskBlocked += 1;
    return blocked;
  }

  /** Transferência atômica. `consumed` = proposta que origina a venda (reserva consumida se pendente). */
  function transfer(p, buyer, price, type, day, consumed = null) {
    const seller = p.owner === null ? null : players[p.owner];
    if (seller) {
      // Liquida a renda acumulada até o instante; fração abaixo do centavo é descartada no fechamento.
      const credit = Math.floor(p.frac);
      if (credit > 0) { seller.balance += credit; ledger.issuedIncome += credit; }
      owned.get(seller.id).delete(p);
    }
    if (consumed?.status === "pending") {
      // Reserva consumida: o valor já estava separado no saldo do comprador.
      buyer.reserved -= price;
      pendingBySeller.get(consumed.seller)?.delete(consumed);
    } else if (available(buyer) < price) {
      throw new Error(`saldo insuficiente na transferência (jogador ${buyer.id})`);
    }
    buyer.balance -= price;
    if (seller) seller.balance += price; else ledger.treasury += price;
    if (consumed) {
      consumed.status = type === "voluntary" ? "accepted" : "executed";
      openByBuyer.get(consumed.buyer)?.delete(consumed);
      openByProp.get(p.id)?.delete(consumed);
    }
    for (const pr of [...(openByProp.get(p.id) ?? [])]) { close(pr, "superseded"); counters.superseded += 1; }
    trades.push({ day, prop: p.id, buyer: buyer.id, seller: seller?.id ?? null, price, type });
    if (seller) {
      const k = `${buyer.id}>${seller.id}`;
      pairDays.set(k, [...(pairDays.get(k) ?? []).filter((d) => d > day - 30), day]);
    }
    for (const id of [buyer.id, seller?.id]) if (id !== undefined) tradeDays.set(id, [...(tradeDays.get(id) ?? []).filter((d) => d === day), day]);
    p.owner = buyer.id; p.price = price; p.frac = 0; p.lastTransfer = day; p.ownership += 1;
    setOf(owned, buyer.id).add(p);
    counters[type] += 1;
  }

  function propose(buyer, p, amount, day) {
    const seller = players[p.owner];
    if (amount <= 0 || available(buyer) < amount) return null;
    const mine = [...(openByBuyer.get(buyer.id) ?? [])];
    if (mine.some((x) => x.prop === p.id && x.status === "pending")) return null;
    if ((buyer.cooldown.get(p.id) ?? -Infinity) > day - cfg.cooldownDays) return null;
    if (mine.filter((x) => x.status === "pending").length >= cfg.maxOpenProposals) return null;
    if (risk(buyer, seller, p, amount, p.price, day)) return null;
    buyer.reserved += amount;
    const pr = { id: proposals.length, prop: p.id, buyer: buyer.id, seller: seller.id, ownership: p.ownership, amount,
                 forced: Math.max(amount, ceilCents(p.price * cfg.forcedPremium)), expires: day + cfg.responseWindowDays,
                 status: "pending", eligibleUntil: null };
    proposals.push(pr);
    setOf(pendingBySeller, seller.id).add(pr);
    setOf(openByBuyer, buyer.id).add(pr);
    setOf(openByProp, p.id).add(pr);
    return pr;
  }

  for (let i = 0; i < sc.initialPlayers; i++) join(0);
  const abuser = sc.abuse ? join(0, "abuser") : null;

  for (let day = 0; day < days; day++) {
    if (day > 0) for (let i = 0; i < sc.dailySignups; i++) join(day);

    for (const p of props) if (p.owner !== null) p.frac += p.price * cfg.dailyIncomeRate;

    // Job de vencimento: libera a reserva e abre a janela de compra sem aceite.
    for (const set of pendingBySeller.values()) {
      for (const pr of [...set]) {
        if (pr.expires <= day) { close(pr, "expired"); pr.eligibleUntil = pr.expires + cfg.forcedWindowDays; counters.expired += 1; }
      }
    }

    // Abuso: conta nova compra caro uma casa do abusador (a recompra acontece no turno dele).
    if (abuser && day >= sc.abuse.startDay && day < sc.abuse.endDay) {
      for (let s = 0; s < sc.abuse.sybilsPerDay; s++) {
        const sybil = join(day, "sybil", abuser.home);
        const target = [...(owned.get(abuser.id) ?? [])].find((p) => p.cat.code === "house_small" && p.lastTransfer <= day - cfg.protectionDays);
        if (!target) {
          const free = near(abuser).find((p) => p.owner === null && p.cat.code === "house_small" && available(abuser) >= p.cat.basePrice);
          if (free) transfer(free, abuser, free.cat.basePrice, "system", day);
          continue;
        }
        const pr = propose(sybil, target, Math.min(Math.round(target.cat.basePrice * sc.abuse.priceMultiplier), available(sybil)), day);
        if (pr) transfer(target, sybil, pr.amount, "voluntary", day, pr);
      }
    }

    const order = players.map((_, i) => i);
    for (let i = order.length - 1; i > 0; i--) { const j = Math.floor(rand() * (i + 1)); [order[i], order[j]] = [order[j], order[i]]; }

    for (const idx of order) {
      const pl = players[idx];
      if (pl.kind === "sybil") continue; // contas descartáveis não voltam a abrir o app
      if (!(day < pl.churnDay && rand() < pl.activity)) continue;
      pl.lastActive = day;

      for (const p of owned.get(pl.id) ?? []) {
        const c = Math.floor(p.frac);
        if (c > 0) { p.frac -= c; pl.balance += c; ledger.issuedIncome += c; }
      }

      for (const pr of [...(pendingBySeller.get(pl.id) ?? [])]) {
        const p = props[pr.prop];
        if (pl.kind !== "abuser" && rand() >= 0.8) continue; // não respondeu hoje
        if (pr.amount >= p.price * pl.reservation) transfer(p, players[pr.buyer], pr.amount, "voluntary", day, pr);
        else { close(pr, "rejected"); players[pr.buyer].cooldown.set(p.id, day); counters.rejected += 1; }
      }

      for (const pr of [...(openByBuyer.get(pl.id) ?? [])]) {
        if (pr.status !== "expired") continue;
        const p = props[pr.prop];
        if (pr.eligibleUntil < day || p.ownership !== pr.ownership) { close(pr, "lapsed"); continue; }
        if (p.lastTransfer > day - cfg.protectionDays) continue;
        if (available(pl) >= pr.forced && rand() < 0.5 + pl.aggressiveness / 2
            && !risk(pl, players[p.owner], p, pr.forced, p.price, day)) {
          transfer(p, pl, pr.forced, "forcedSilence", day, pr);
        }
      }

      const candidates = near(pl);
      if (rand() < 0.3 * pl.aggressiveness) {
        const target = candidates.find((p) => p.owner !== null && p.owner !== pl.id
          && players[p.owner].lastActive <= day - cfg.inactivityDays && p.lastTransfer <= day - cfg.protectionDays
          && available(pl) >= ceilCents(p.price * cfg.forcedPremium));
        if (target) {
          const price = ceilCents(target.price * cfg.forcedPremium);
          if (!risk(pl, players[target.owner], target, price, target.price, day)) transfer(target, pl, price, "forcedInactivity", day);
        }
      }

      if (rand() < (pl.kind === "active" ? 0.6 : 0.3)) {
        const free = candidates.filter((p) => p.owner === null && available(pl) >= p.cat.basePrice);
        if (free.length) { const p = pick(free); transfer(p, pl, p.cat.basePrice, "system", day); }
      }

      if (pl.kind === "abuser") {
        // Recompra pelo preço base, aceita pelo próprio abusador no aparelho da conta descartável.
        for (const p of props) {
          if (p.owner === null || players[p.owner].kind !== "sybil" || p.lastTransfer > day - cfg.protectionDays) continue;
          const pr = propose(pl, p, p.cat.basePrice, day);
          if (pr) transfer(p, pl, pr.amount, "voluntary", day, pr);
        }
        continue;
      }

      if (rand() < 0.25 + 0.3 * pl.aggressiveness) {
        const targets = candidates.filter((p) => p.owner !== null && p.owner !== pl.id);
        if (targets.length) { const p = pick(targets); propose(pl, p, Math.round(p.price * (1 + rand() * 0.45)), day); }
      }
    }

    daily.push(metrics(day));
  }

  function metrics(day) {
    const supply = players.reduce((s, p) => s + p.balance, 0);
    const reserved = players.reduce((s, p) => s + p.reserved, 0);
    const assets = new Map();
    const income = new Map();
    let ownedCount = 0;
    for (const p of props) {
      if (p.owner === null) continue;
      ownedCount += 1;
      assets.set(p.owner, (assets.get(p.owner) ?? 0) + p.price);
      income.set(p.owner, (income.get(p.owner) ?? 0) + p.price * cfg.dailyIncomeRate);
    }
    const runRate = [...income.values()].sort((a, b) => b - a);
    const totalRun = runRate.reduce((s, v) => s + v, 0);
    const wealth = players.map((pl) => pl.balance + (assets.get(pl.id) ?? 0));
    const totalWealth = wealth.reduce((s, v) => s + v, 0);
    const today = trades.filter((t) => t.day === day);
    const vol = today.filter((t) => t.type === "voluntary");
    return {
      day,
      players: players.length,
      active: players.filter((p) => p.lastActive === day).length,
      money_supply: supply,
      reserved,
      issued_initial: ledger.issuedInitial,
      issued_income: ledger.issuedIncome,
      treasury: ledger.treasury,
      owned_properties: ownedCount,
      daily_income_run_rate: Math.round(totalRun),
      top10_income_share: totalRun ? runRate.slice(0, 10).reduce((s, v) => s + v, 0) / totalRun : 0,
      gini_wealth: gini(wealth),
      trades_today: today.length,
      avg_voluntary_price_ratio: vol.length ? vol.reduce((s, t) => s + t.price / props[t.prop].cat.basePrice, 0) / vol.length : null,
      abuser_wealth_share: abuser ? wealth[abuser.id] / totalWealth : null,
    };
  }

  return { scenario, seed, days, daily, counters, ledger, players, props, proposals, trades, abuserId: abuser?.id ?? null };
}

export function gini(values) {
  const v = values.filter((x) => x >= 0).sort((a, b) => a - b);
  const n = v.length;
  const sum = v.reduce((s, x) => s + x, 0);
  if (!n || !sum) return 0;
  let acc = 0;
  v.forEach((x, i) => { acc += (2 * (i + 1) - n - 1) * x; });
  return acc / (n * sum);
}

/** Invariantes contábeis do modelo (as mesmas da conciliação do banco). */
export function checkInvariants(run) {
  const supply = run.players.reduce((s, p) => s + p.balance, 0);
  const issued = run.ledger.issuedInitial + run.ledger.issuedIncome;
  const errors = [];
  if (supply + run.ledger.treasury !== issued) errors.push(`massa ${supply} + tesouro ${run.ledger.treasury} ≠ emitido ${issued}`);
  const pendingByBuyer = new Map();
  for (const pr of run.proposals) if (pr.status === "pending") pendingByBuyer.set(pr.buyer, (pendingByBuyer.get(pr.buyer) ?? 0) + pr.amount);
  for (const p of run.players) {
    if (p.balance < 0) errors.push(`saldo negativo: jogador ${p.id}`);
    if (p.reserved < 0 || p.reserved > p.balance) errors.push(`reserva inválida: jogador ${p.id}`);
    if ((pendingByBuyer.get(p.id) ?? 0) !== p.reserved) errors.push(`reserva ≠ propostas pendentes: jogador ${p.id}`);
  }
  const lastPrice = new Map();
  for (const t of run.trades) {
    if ((t.type === "forcedSilence" || t.type === "forcedInactivity") && t.price < Math.ceil(lastPrice.get(t.prop) * 1.2 - 1e-9)) {
      errors.push(`compra sem aceite abaixo de +20%: imóvel ${t.prop}`);
    }
    lastPrice.set(t.prop, t.price);
  }
  return errors;
}
