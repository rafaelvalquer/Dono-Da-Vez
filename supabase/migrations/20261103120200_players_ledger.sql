-- Dono da Vez — 0003: jogadores, carteiras, ledger de partidas dobradas e idempotência

-- ---------------------------------------------------------------------------
-- Perfis: id = id do auth.users na criação (pseudônimo técnico permanente).
-- auth_user_id é anulado quando a credencial é removida; histórico econômico fica.
-- ---------------------------------------------------------------------------
create table game.profiles (
  id                    uuid primary key,
  auth_user_id          uuid unique,
  nickname              text not null check (nickname ~ '^[A-Za-z0-9_.-]{3,24}$'),
  avatar_id             text not null default 'avatar_01' check (avatar_id ~ '^avatar_[0-9]{2}$'),
  status                text not null default 'active'
                        check (status in ('active','suspended','deletion_pending','deleted')),
  email_confirmed_at    timestamptz,
  email_sha256          text,
  initial_grant_at      timestamptz,
  notification_prefs    jsonb not null default '{"proposals": true, "transfers": true, "reminders": true}'::jsonb,
  deletion_requested_at timestamptz,
  deleted_at            timestamptz,
  created_at            timestamptz not null default now(),
  updated_at            timestamptz not null default now()
);
create unique index profiles_nickname_lower_uq on game.profiles (lower(nickname));
create trigger profiles_touch before update on game.profiles
  for each row execute function game.touch_updated_at();

do $$
begin
  if to_regclass('auth.users') is not null then
    alter table game.profiles
      add constraint profiles_auth_user_fk foreign key (auth_user_id)
      references auth.users(id) on delete set null;
  end if;
end $$;

-- Saldo inicial: uma vez por e-mail confirmado, mesmo após exclusão e recadastro.
create table game.grant_registry (
  email_sha256 text primary key,
  profile_id   uuid not null references game.profiles(id) on delete restrict,
  granted_at   timestamptz not null default now()
);

create table game.player_activity (
  user_id                    uuid primary key references game.profiles(id) on delete restrict,
  last_qualified_activity_at timestamptz not null default now(),
  last_source                text not null default 'signup',
  updated_at                 timestamptz not null default now()
);

-- ---------------------------------------------------------------------------
-- Contas contábeis: jogador, emissão, tesouraria e ajustes.
-- ---------------------------------------------------------------------------
create table game.ledger_accounts (
  id         uuid primary key default gen_random_uuid(),
  kind       text not null check (kind in ('player','issuance','treasury','adjustments')),
  owner_id   uuid unique references game.profiles(id) on delete restrict,
  code       text unique,
  created_at timestamptz not null default now(),
  check ((kind = 'player') = (owner_id is not null)),
  check ((kind = 'player') = (code is null))
);

insert into game.ledger_accounts (id, kind, code) values
  ('00000000-0000-4000-8000-00000000a001', 'issuance',    'SYSTEM_ISSUANCE'),
  ('00000000-0000-4000-8000-00000000a002', 'treasury',    'SYSTEM_TREASURY'),
  ('00000000-0000-4000-8000-00000000a003', 'adjustments', 'SYSTEM_ADJUSTMENTS');

create table game.wallets (
  user_id       uuid primary key references game.profiles(id) on delete restrict,
  account_id    uuid not null unique references game.ledger_accounts(id) on delete restrict,
  balance       numeric(24,8) not null default 0,
  reserved      numeric(24,8) not null default 0,
  frozen        boolean not null default false,
  frozen_reason text,
  version       bigint not null default 0,
  updated_at    timestamptz not null default now(),
  constraint wallets_reserved_nonnegative check (reserved >= 0),
  constraint wallets_balance_covers_reserved check (balance >= reserved)
);

-- ---------------------------------------------------------------------------
-- Operações idempotentes: unique(actor_id, idempotency_key), guardadas permanentemente.
-- ---------------------------------------------------------------------------
create table game.operations (
  id              uuid primary key default gen_random_uuid(),
  actor_id        uuid not null references game.profiles(id) on delete restrict,
  idempotency_key text not null check (length(idempotency_key) between 8 and 128),
  kind            text not null,
  request_hash    text,
  status          text not null check (status in ('in_progress','succeeded','failed')),
  response        jsonb,
  error_code      text,
  created_at      timestamptz not null default clock_timestamp(),
  completed_at    timestamptz,
  constraint operations_actor_key_uq unique (actor_id, idempotency_key)
);
create index operations_created_idx on game.operations (created_at);

-- ---------------------------------------------------------------------------
-- Ledger: cabeçalho e lançamentos imutáveis; soma zero por transação.
-- ---------------------------------------------------------------------------
create table game.ledger_transactions (
  id                     uuid primary key default gen_random_uuid(),
  kind                   text not null check (kind in (
                           'initial_grant','income','system_purchase','voluntary_sale',
                           'forced_sale_silence','forced_sale_inactivity','adjustment',
                           'compensation','account_closure')),
  operation_id           uuid unique references game.operations(id) on delete restrict,
  reverses_transaction_id uuid references game.ledger_transactions(id) on delete restrict,
  metadata               jsonb not null default '{}'::jsonb,
  created_at             timestamptz not null default clock_timestamp()
);
create trigger ledger_transactions_immutable before update or delete on game.ledger_transactions
  for each row execute function game.forbid_mutation();

create table game.ledger_entries (
  id             bigint generated always as identity primary key,
  transaction_id uuid not null references game.ledger_transactions(id) on delete restrict,
  account_id     uuid not null references game.ledger_accounts(id) on delete restrict,
  amount         numeric(24,8) not null check (amount <> 0),
  entry_type     text not null check (entry_type in ('initial_grant','income','price','adjustment','closure')),
  property_id    uuid,
  ownership_id   uuid,
  created_at     timestamptz not null default clock_timestamp()
);
create index ledger_entries_account_tx_idx on game.ledger_entries (account_id, transaction_id);
create index ledger_entries_account_id_desc_idx on game.ledger_entries (account_id, id desc);
create index ledger_entries_tx_idx on game.ledger_entries (transaction_id);
create trigger ledger_entries_immutable before update or delete on game.ledger_entries
  for each row execute function game.forbid_mutation();

create or replace function game.check_ledger_balanced() returns trigger
language plpgsql set search_path = pg_catalog, pg_temp as $$
declare v_sum numeric;
begin
  select coalesce(sum(amount), 0) into v_sum from game.ledger_entries where transaction_id = new.transaction_id;
  if v_sum <> 0 then
    raise exception 'ledger desbalanceado na transação % (soma %)', new.transaction_id, v_sum
      using errcode = 'DV002';
  end if;
  return null;
end $$;

create constraint trigger ledger_entries_balanced
  after insert on game.ledger_entries
  deferrable initially deferred
  for each row execute function game.check_ledger_balanced();

-- ---------------------------------------------------------------------------
-- Reservas: reduzem disponibilidade; não são lançamentos contábeis.
-- (FK para proposals é adicionada na migração de mercado.)
-- ---------------------------------------------------------------------------
create table game.wallet_holds (
  id             uuid primary key default gen_random_uuid(),
  user_id        uuid not null references game.profiles(id) on delete restrict,
  proposal_id    uuid not null unique,
  amount         numeric(24,8) not null check (amount > 0),
  status         text not null default 'active' check (status in ('active','consumed','released')),
  release_reason text,
  created_at     timestamptz not null default clock_timestamp(),
  released_at    timestamptz,
  check ((status = 'active') = (released_at is null))
);
create index wallet_holds_user_active_idx on game.wallet_holds (user_id) where status = 'active';
create index wallet_holds_user_created_idx on game.wallet_holds (user_id, created_at desc);
