-- Dono da Vez — 0004: titularidades, propostas, trades, presença, avisos, administração

-- ---------------------------------------------------------------------------
-- Titularidades: purchase_price = preço efetivamente pago; renda sob demanda.
-- ---------------------------------------------------------------------------
create table game.ownerships (
  id                   uuid primary key default gen_random_uuid(),
  property_id          uuid not null references game.properties(id) on delete restrict,
  owner_id             uuid not null references game.profiles(id) on delete restrict,
  purchase_price       numeric(24,8) not null check (purchase_price > 0),
  acquired_at          timestamptz not null,
  acquired_via         text not null check (acquired_via in ('system','voluntary','forced_silence','forced_inactivity')),
  ended_at             timestamptz,
  end_reason           text check (end_reason in ('sold','forced_sale','account_deleted','compensation')),
  income_checkpoint_at timestamptz not null,
  income_fraction      numeric(38,18) not null default 0 check (income_fraction >= 0),
  income_credited      numeric(24,8) not null default 0 check (income_credited >= 0),
  income_discarded     numeric(38,18) not null default 0 check (income_discarded >= 0),
  rate_snapshot        numeric(12,8) not null check (rate_snapshot > 0),
  config_version       integer not null references game.game_config(version),
  created_at           timestamptz not null default clock_timestamp(),
  constraint ownerships_end_after_start check (ended_at is null or ended_at >= acquired_at),
  constraint ownerships_checkpoint_after_start check (income_checkpoint_at >= acquired_at),
  constraint ownerships_end_reason_iff_ended check ((ended_at is null) = (end_reason is null))
);
create unique index ownerships_one_active_per_property_uq on game.ownerships (property_id) where ended_at is null;
create index ownerships_owner_ended_idx on game.ownerships (owner_id, ended_at);
create index ownerships_owner_active_idx on game.ownerships (owner_id, acquired_at desc, id) where ended_at is null;
create index ownerships_property_idx on game.ownerships (property_id, acquired_at desc);

alter table game.properties
  add constraint properties_current_ownership_fk foreign key (current_ownership_id)
  references game.ownerships(id) on delete restrict deferrable initially deferred;

-- ---------------------------------------------------------------------------
-- Propostas e máquina de estados
-- ---------------------------------------------------------------------------
create table game.proposals (
  id                      uuid primary key default gen_random_uuid(),
  property_id             uuid not null references game.properties(id) on delete restrict,
  ownership_id            uuid not null references game.ownerships(id) on delete restrict,
  buyer_id                uuid not null references game.profiles(id) on delete restrict,
  seller_id               uuid not null references game.profiles(id) on delete restrict,
  amount                  numeric(24,8) not null check (amount > 0 and amount = round(amount, 2)),
  status                  text not null default 'pending' check (status in (
                            'pending','accepted','rejected','cancelled','expired',
                            'executed_without_acceptance','superseded')),
  purchase_price_snapshot numeric(24,8) not null check (purchase_price_snapshot > 0),
  forced_price            numeric(24,8) not null check (forced_price > 0),
  created_at              timestamptz not null,
  expires_at              timestamptz not null,
  eligible_until          timestamptz,
  closed_at               timestamptz,
  close_reason            text,
  rules_version           integer not null references game.game_config(version),
  rules_snapshot          jsonb not null,
  version                 integer not null default 1,
  trade_id                uuid,
  constraint proposals_not_self check (buyer_id <> seller_id),
  constraint proposals_expiry_after_creation check (expires_at > created_at),
  constraint proposals_window_after_expiry check (eligible_until is null or eligible_until >= expires_at),
  constraint proposals_closed_iff_not_pending check ((status = 'pending') = (closed_at is null)),
  constraint proposals_closed_after_creation check (closed_at is null or closed_at >= created_at)
);
create unique index proposals_one_pending_per_buyer_property_uq on game.proposals (buyer_id, property_id) where status = 'pending';
create index proposals_seller_status_expires_idx on game.proposals (seller_id, status, expires_at);
create index proposals_buyer_status_idx on game.proposals (buyer_id, status);
create index proposals_ownership_open_idx on game.proposals (ownership_id) where status in ('pending','expired');
create index proposals_pending_expiry_idx on game.proposals (expires_at, id) where status = 'pending';
create index proposals_buyer_property_closed_idx on game.proposals (buyer_id, property_id, closed_at desc);

alter table game.wallet_holds
  add constraint wallet_holds_proposal_fk foreign key (proposal_id)
  references game.proposals(id) on delete restrict;

-- ---------------------------------------------------------------------------
-- Trades: registro imutável de cada mudança de titularidade
-- ---------------------------------------------------------------------------
create table game.trades (
  id                    uuid primary key default gen_random_uuid(),
  property_id           uuid not null references game.properties(id) on delete restrict,
  old_ownership_id      uuid references game.ownerships(id) on delete restrict,
  new_ownership_id      uuid references game.ownerships(id) on delete restrict,
  buyer_id              uuid references game.profiles(id) on delete restrict,
  seller_id             uuid references game.profiles(id) on delete restrict,
  price                 numeric(24,8) not null check (price >= 0),
  type                  text not null check (type in ('system_purchase','voluntary','forced_silence','forced_inactivity','returned_to_system')),
  proposal_id           uuid references game.proposals(id) on delete restrict,
  ledger_transaction_id uuid references game.ledger_transactions(id) on delete restrict,
  operation_id          uuid references game.operations(id) on delete restrict,
  occurred_at           timestamptz not null,
  constraint trades_paid_shape check (type = 'returned_to_system' or (price > 0 and buyer_id is not null and new_ownership_id is not null)),
  constraint trades_system_has_no_seller check (type <> 'system_purchase' or seller_id is null)
);
create index trades_property_idx on game.trades (property_id, occurred_at desc);
create index trades_buyer_idx on game.trades (buyer_id, occurred_at desc);
create index trades_seller_idx on game.trades (seller_id, occurred_at desc);
create index trades_occurred_idx on game.trades (occurred_at);
create trigger trades_immutable before update or delete on game.trades
  for each row execute function game.forbid_mutation();

alter table game.proposals
  add constraint proposals_trade_fk foreign key (trade_id) references game.trades(id) on delete restrict;

-- ---------------------------------------------------------------------------
-- Prova de presença: uso único, vinculada a usuário/imóvel/operação, ~30 s.
-- Coordenadas são eliminadas após o prazo de retenção (job diário).
-- ---------------------------------------------------------------------------
create table game.presence_proofs (
  id                    uuid primary key default gen_random_uuid(),
  nonce                 text not null unique check (length(nonce) between 16 and 128),
  actor_id              uuid not null references game.profiles(id) on delete restrict,
  property_id           uuid not null references game.properties(id) on delete restrict,
  recorded_at           timestamptz not null,
  expires_at            timestamptz not null,
  location              extensions.geography(Point, 4326),
  accuracy_m            numeric(8,2) not null,
  distance_m            numeric(10,2) not null,
  fix_age_ms            integer not null,
  speed_kmh             numeric(8,2),
  mock_location         boolean not null default false,
  integrity_verdict     text,
  consumed_by_operation uuid references game.operations(id) on delete restrict,
  consumed_at           timestamptz,
  coords_purged_at      timestamptz,
  check (expires_at > recorded_at)
);
create index presence_proofs_actor_recorded_idx on game.presence_proofs (actor_id, recorded_at desc);
create index presence_proofs_recorded_idx on game.presence_proofs (recorded_at);

create table game.presence_rejections (
  id          bigint generated always as identity primary key,
  actor_id    uuid not null,
  property_id uuid,
  reason      text not null,
  accuracy_m  numeric(8,2),
  distance_m  numeric(10,2),
  fix_age_ms  integer,
  created_at  timestamptz not null default now()
);
create index presence_rejections_created_idx on game.presence_rejections (created_at);

-- Interesse agregado fora da área (célula arredondada ~1 km, sem coordenada exata)
create table game.region_interest (
  actor_id   uuid not null references game.profiles(id) on delete restrict,
  cell       text not null,
  created_at timestamptz not null default now(),
  primary key (actor_id, cell)
);

-- ---------------------------------------------------------------------------
-- Avisos persistidos, outbox de entrega e tokens de push
-- ---------------------------------------------------------------------------
create table game.notifications (
  id         uuid primary key default gen_random_uuid(),
  user_id    uuid not null references game.profiles(id) on delete restrict,
  type       text not null,
  event_key  text not null,
  payload    jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default clock_timestamp(),
  read_at    timestamptz,
  constraint notifications_dedupe_uq unique (user_id, type, event_key)
);
create index notifications_user_created_idx on game.notifications (user_id, created_at desc, id);

create table game.outbox (
  id              bigint generated always as identity primary key,
  event_id        uuid not null unique default gen_random_uuid(),
  topic           text not null,
  user_id         uuid,
  payload         jsonb not null default '{}'::jsonb,
  status          text not null default 'pending' check (status in ('pending','processing','sent','failed','dead')),
  attempts        integer not null default 0,
  next_attempt_at timestamptz not null default now(),
  locked_until    timestamptz,
  last_error      text,
  created_at      timestamptz not null default clock_timestamp(),
  sent_at         timestamptz
);
create index outbox_status_next_idx on game.outbox (status, next_attempt_at);

create table game.device_tokens (
  id           uuid primary key default gen_random_uuid(),
  user_id      uuid not null references game.profiles(id) on delete restrict,
  token        text not null unique check (length(token) between 20 and 4096),
  platform     text not null check (platform in ('android','ios')),
  created_at   timestamptz not null default now(),
  last_seen_at timestamptz not null default now(),
  revoked_at   timestamptz
);
create index device_tokens_user_active_idx on game.device_tokens (user_id) where revoked_at is null;

-- ---------------------------------------------------------------------------
-- Suporte, administração, antiabuso, reconciliação, relatórios e rate limit
-- ---------------------------------------------------------------------------
create table game.reports (
  id          bigint generated always as identity primary key,
  reporter_id uuid not null references game.profiles(id) on delete restrict,
  property_id uuid references game.properties(id) on delete restrict,
  category    text not null check (category in ('map_error','wrong_category','missing_property','abuse','bug','account','other')),
  message     text not null check (length(message) between 5 and 2000),
  status      text not null default 'open' check (status in ('open','in_progress','resolved','rejected')),
  resolution  text,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);
create index reports_status_idx on game.reports (status, created_at);
create trigger reports_touch before update on game.reports
  for each row execute function game.touch_updated_at();

create table game.admin_roles (
  user_id    uuid not null references game.profiles(id) on delete restrict,
  role       text not null check (role in ('support','geo','finance','superadmin')),
  granted_by uuid,
  granted_at timestamptz not null default now(),
  revoked_at timestamptz,
  primary key (user_id, role)
);

create table game.admin_audit (
  id           bigint generated always as identity primary key,
  actor_id     uuid not null,
  action       text not null,
  target_type  text not null,
  target_id    text,
  reason       text not null check (length(reason) >= 3),
  before       jsonb,
  after        jsonb,
  operation_id uuid references game.operations(id) on delete restrict,
  created_at   timestamptz not null default clock_timestamp()
);
create index admin_audit_created_idx on game.admin_audit (created_at desc);
create trigger admin_audit_immutable before update or delete on game.admin_audit
  for each row execute function game.forbid_mutation();

create table game.risk_signals (
  id          bigint generated always as identity primary key,
  context     text not null,
  buyer_id    uuid,
  seller_id   uuid,
  property_id uuid,
  amount      numeric(24,8),
  score       integer not null,
  reasons     text[] not null default '{}',
  created_at  timestamptz not null default clock_timestamp()
);
create index risk_signals_created_idx on game.risk_signals (created_at);

create table game.risk_reviews (
  id          uuid primary key default gen_random_uuid(),
  buyer_id    uuid not null references game.profiles(id) on delete restrict,
  seller_id   uuid not null references game.profiles(id) on delete restrict,
  property_id uuid not null references game.properties(id) on delete restrict,
  context     text not null,
  amount      numeric(24,8),
  score       integer not null,
  reasons     text[] not null,
  status      text not null default 'pending' check (status in ('pending','approved','rejected')),
  created_at  timestamptz not null default clock_timestamp(),
  decided_by  uuid,
  decided_at  timestamptz,
  note        text
);
create unique index risk_reviews_one_pending_uq on game.risk_reviews (buyer_id, seller_id, property_id) where status = 'pending';

create table game.reconciliation_runs (
  id              uuid primary key default gen_random_uuid(),
  started_at      timestamptz not null default clock_timestamp(),
  finished_at     timestamptz,
  wallets_checked integer not null default 0,
  alerts_opened   integer not null default 0,
  triggered_by    text not null default 'cron'
);

create table game.reconciliation_alerts (
  id          uuid primary key default gen_random_uuid(),
  run_id      uuid references game.reconciliation_runs(id) on delete restrict,
  kind        text not null,
  user_id     uuid,
  subject_id  text,
  details     jsonb not null default '{}'::jsonb,
  status      text not null default 'open' check (status in ('open','acknowledged','resolved')),
  created_at  timestamptz not null default clock_timestamp(),
  resolved_by uuid,
  resolved_at timestamptz,
  note        text
);
create index reconciliation_alerts_status_idx on game.reconciliation_alerts (status, created_at);

create table game.economy_daily_reports (
  report_date date primary key,
  metrics     jsonb not null,
  created_at  timestamptz not null default now()
);

create table game.rate_limits (
  actor_id     uuid not null,
  bucket       text not null,
  window_start timestamptz not null,
  hits         integer not null default 0,
  primary key (actor_id, bucket, window_start)
);
