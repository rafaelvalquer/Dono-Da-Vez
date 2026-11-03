-- Dono da Vez — 0006: funções internas (SECURITY INVOKER) usadas pelas APIs e jobs.
-- Todas executam como o dono das funções SECURITY DEFINER (dv_engine).
-- Convenção de erro de domínio: SQLSTATE 'DV000', MESSAGE = código, HINT = mensagem, DETAIL = JSON.

create or replace function game.err(p_code text, p_message text default null, p_detail jsonb default null)
returns void language plpgsql set search_path = pg_catalog, pg_temp as $$
begin
  raise exception using
    errcode = 'DV000',
    message = p_code,
    hint    = coalesce(p_message, p_code),
    detail  = coalesce(p_detail, '{}'::jsonb)::text;
end $$;

-- Configuração vigente (versão mais recente com effective_at <= now()).
create or replace function game.cfg() returns jsonb
language sql stable set search_path = pg_catalog, pg_temp as $$
  select c.values || jsonb_build_object('_version', c.version)
    from game.game_config c
   where c.effective_at <= now()
   order by c.effective_at desc, c.version desc
   limit 1
$$;

-- ---------------------------------------------------------------------------
-- Dinheiro: numeric sempre; JSON usa strings decimais.
-- ---------------------------------------------------------------------------
create or replace function game.money_text(p numeric) returns text
language sql immutable set search_path = pg_catalog as $$
  select case when p is null then null
              when scale(trim_scale(p)) <= 2 then round(p, 2)::text
              else trim_scale(p)::text end
$$;

create or replace function game.ceil_cents(p numeric) returns numeric
language sql immutable set search_path = pg_catalog as $$
  select round(ceil(p * 100) / 100, 2)
$$;

create or replace function game.floor_cents(p numeric) returns numeric
language sql immutable set search_path = pg_catalog as $$
  select round(floor(p * 100) / 100, 2)
$$;

-- renda = preço_pago × taxa × segundos_de_posse / 86.400 (+ fração pendente)
create or replace function game.accrued_income(p_price numeric, p_rate numeric, p_from timestamptz, p_to timestamptz, p_fraction numeric default 0)
returns numeric language sql immutable set search_path = pg_catalog as $$
  select coalesce(p_fraction, 0) + p_price * p_rate * greatest(extract(epoch from (p_to - p_from)), 0) / 86400
$$;

create or replace function game.acct_issuance() returns uuid language sql immutable set search_path = pg_catalog as
$$ select '00000000-0000-4000-8000-00000000a001'::uuid $$;
create or replace function game.acct_treasury() returns uuid language sql immutable set search_path = pg_catalog as
$$ select '00000000-0000-4000-8000-00000000a002'::uuid $$;
create or replace function game.acct_adjustments() returns uuid language sql immutable set search_path = pg_catalog as
$$ select '00000000-0000-4000-8000-00000000a003'::uuid $$;

create or replace function game.account_of(p_user uuid) returns uuid
language plpgsql stable set search_path = pg_catalog, pg_temp as $$
declare v uuid;
begin
  select account_id into v from game.wallets where user_id = p_user;
  if v is null then perform game.err('WALLET_NOT_FOUND', 'Carteira não encontrada'); end if;
  return v;
end $$;

-- ---------------------------------------------------------------------------
-- Idempotência
-- ---------------------------------------------------------------------------
create or replace function game.op_begin(p_actor uuid, p_key text, p_kind text, p_hash text,
                                         out op_id uuid, out replay jsonb)
language plpgsql set search_path = pg_catalog, pg_temp as $$
declare v game.operations;
begin
  if p_key is null or length(p_key) < 8 or length(p_key) > 128 then
    perform game.err('VALIDATION_FAILED', 'Idempotency-Key ausente ou inválida');
  end if;
  if not exists (select 1 from game.profiles where id = p_actor) then
    perform game.err('PROFILE_NOT_FOUND', 'Perfil não encontrado; abra o app novamente');
  end if;
  -- Uma tentativa concorrente com a mesma chave espera aqui o commit da primeira.
  insert into game.operations (actor_id, idempotency_key, kind, request_hash, status)
  values (p_actor, p_key, p_kind, p_hash, 'in_progress')
  on conflict (actor_id, idempotency_key) do nothing
  returning id into op_id;
  if op_id is not null then
    replay := null;
    return;
  end if;
  select * into v from game.operations o where o.actor_id = p_actor and o.idempotency_key = p_key;
  if v.kind <> p_kind or v.request_hash is distinct from p_hash then
    perform game.err('IDEMPOTENCY_CONFLICT', 'Esta chave de operação já foi usada com outro conteúdo',
                     jsonb_build_object('operation_id', v.id));
  end if;
  op_id := v.id;
  replay := coalesce(v.response, jsonb_build_object('ok', false, 'operation_id', v.id)) || jsonb_build_object('replayed', true);
end $$;

create or replace function game.op_succeed(p_op uuid, p_result jsonb) returns jsonb
language plpgsql set search_path = pg_catalog, pg_temp as $$
declare v jsonb := jsonb_build_object('ok', true, 'operation_id', p_op, 'server_time', clock_timestamp())
                   || coalesce(p_result, '{}'::jsonb);
begin
  update game.operations set status = 'succeeded', response = v, completed_at = clock_timestamp() where id = p_op;
  return v;
end $$;

create or replace function game.open_risk_review(p_detail jsonb) returns void
language plpgsql set search_path = pg_catalog, pg_temp as $$
begin
  if p_detail ? 'buyer_id' and p_detail ? 'seller_id' and p_detail ? 'property_id' then
    insert into game.risk_reviews (buyer_id, seller_id, property_id, context, amount, score, reasons)
    values ((p_detail->>'buyer_id')::uuid, (p_detail->>'seller_id')::uuid, (p_detail->>'property_id')::uuid,
            coalesce(p_detail->>'context', 'trade'), (p_detail->>'amount')::numeric, coalesce((p_detail->>'score')::int, 0),
            coalesce(array(select jsonb_array_elements_text(p_detail->'reasons')), '{}'))
    on conflict (buyer_id, seller_id, property_id) where status = 'pending' do nothing;
  end if;
end $$;

create or replace function game.op_fail(p_op uuid, p_code text, p_message text, p_detail text) returns jsonb
language plpgsql set search_path = pg_catalog, pg_temp as $$
declare v jsonb; v_detail jsonb;
begin
  begin
    v_detail := nullif(p_detail, '')::jsonb;
  exception when others then
    v_detail := jsonb_build_object('raw', p_detail);
  end;
  v := jsonb_build_object('ok', false, 'operation_id', p_op, 'server_time', clock_timestamp(),
         'error', jsonb_build_object('code', p_code, 'message', p_message,
                                     'detail', coalesce(v_detail, '{}'::jsonb) - 'buyer_id' - 'seller_id'));
  update game.operations set status = 'failed', response = v, error_code = p_code, completed_at = clock_timestamp()
   where id = p_op;
  if p_code = 'RISK_REVIEW' then
    perform game.open_risk_review(v_detail);
  end if;
  return v;
end $$;

-- ---------------------------------------------------------------------------
-- Jogadores
-- ---------------------------------------------------------------------------
create or replace function game.email_hash(p_email text) returns text
language sql immutable set search_path = pg_catalog as $$
  select case when p_email is null then null
              else encode(sha256(convert_to(lower(trim(p_email)), 'UTF8')), 'hex') end
$$;

create or replace function game.ensure_player(p_user uuid, p_confirmed_at timestamptz, p_nick_hint text, p_email text)
returns void language plpgsql set search_path = pg_catalog, pg_temp as $$
declare v_nick text; v_account uuid; v_try int := 0; v_inserted uuid;
begin
  if exists (select 1 from game.profiles where id = p_user) then return; end if;
  v_nick := nullif(regexp_replace(coalesce(p_nick_hint, ''), '[^A-Za-z0-9_.-]', '', 'g'), '');
  if v_nick is null or length(v_nick) < 3 or length(v_nick) > 24 then
    v_nick := 'jogador_' || substr(md5(p_user::text), 1, 8);
  end if;
  while exists (select 1 from game.profiles where lower(nickname) = lower(v_nick)) loop
    v_try := v_try + 1;
    v_nick := 'jogador_' || substr(md5(p_user::text || v_try::text), 1, 8);
  end loop;
  insert into game.profiles (id, auth_user_id, nickname, email_confirmed_at, email_sha256)
  values (p_user, p_user, v_nick, p_confirmed_at, game.email_hash(p_email))
  on conflict (id) do nothing
  returning id into v_inserted;
  if v_inserted is null then return; end if;
  insert into game.player_activity (user_id, last_qualified_activity_at, last_source) values (p_user, now(), 'signup');
  insert into game.ledger_accounts (kind, owner_id) values ('player', p_user) returning id into v_account;
  insert into game.wallets (user_id, account_id) values (p_user, v_account);
end $$;

-- Sincroniza auth.users → profiles. Falha do jogo nunca bloqueia o Auth.
create or replace function game.on_auth_user_change() returns trigger
language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
begin
  begin
    if tg_op = 'INSERT' then
      perform game.ensure_player(new.id, new.email_confirmed_at, new.raw_user_meta_data->>'nickname', new.email);
    elsif new.email_confirmed_at is distinct from old.email_confirmed_at or new.email is distinct from old.email then
      update game.profiles
         set email_confirmed_at = new.email_confirmed_at,
             email_sha256 = coalesce(game.email_hash(new.email), email_sha256)
       where auth_user_id = new.id;
    end if;
  exception when others then
    raise warning 'dono-da-vez: falha ao sincronizar perfil %: %', new.id, sqlerrm;
  end;
  return new;
end $$;

do $$
begin
  if to_regclass('auth.users') is not null then
    drop trigger if exists dv_on_auth_user_change on auth.users;
    create trigger dv_on_auth_user_change
      after insert or update of email_confirmed_at, email on auth.users
      for each row execute function game.on_auth_user_change();
  end if;
end $$;

create or replace function game.require_player(p_actor uuid, p_need_confirmed boolean default true)
returns game.profiles language plpgsql stable set search_path = pg_catalog, pg_temp as $$
declare v game.profiles;
begin
  select * into v from game.profiles where id = p_actor;
  if not found then perform game.err('PROFILE_NOT_FOUND', 'Perfil não encontrado; abra o app novamente'); end if;
  if v.status = 'suspended' then perform game.err('ACCOUNT_SUSPENDED', 'Conta suspensa; fale com o suporte'); end if;
  if v.status in ('deletion_pending', 'deleted') then perform game.err('ACCOUNT_CLOSED', 'Conta em encerramento'); end if;
  if p_need_confirmed and v.email_confirmed_at is null then
    perform game.err('EMAIL_NOT_CONFIRMED', 'Confirme seu e-mail para jogar');
  end if;
  return v;
end $$;

create or replace function game.touch_activity(p_actor uuid, p_source text) returns void
language plpgsql set search_path = pg_catalog, pg_temp as $$
declare v_min int := coalesce((game.cfg()->>'heartbeat_min_interval_s')::int, 60);
begin
  update game.player_activity
     set last_qualified_activity_at = now(), last_source = left(p_source, 40), updated_at = now()
   where user_id = p_actor
     and last_qualified_activity_at < now() - make_interval(secs => v_min);
end $$;

-- ---------------------------------------------------------------------------
-- Ledger e carteiras (o chamador já travou as carteiras em ordem de UUID)
-- ---------------------------------------------------------------------------
create or replace function game.ledger_tx(p_kind text, p_op uuid, p_metadata jsonb default '{}'::jsonb) returns uuid
language plpgsql set search_path = pg_catalog, pg_temp as $$
declare v uuid;
begin
  insert into game.ledger_transactions (kind, operation_id, metadata)
  values (p_kind, p_op, coalesce(p_metadata, '{}'::jsonb))
  returning id into v;
  return v;
end $$;

create or replace function game.ledger_post(p_tx uuid, p_account uuid, p_amount numeric, p_entry_type text,
                                            p_property uuid default null, p_ownership uuid default null)
returns void language plpgsql set search_path = pg_catalog, pg_temp as $$
begin
  if p_amount = 0 then return; end if;
  insert into game.ledger_entries (transaction_id, account_id, amount, entry_type, property_id, ownership_id)
  values (p_tx, p_account, p_amount, p_entry_type, p_property, p_ownership);
end $$;

-- Aplica variação no saldo contábil e na reserva. Débitos/reservas exigem disponível suficiente
-- e carteira não bloqueada; créditos e liberações sempre passam.
create or replace function game.wallet_apply(p_user uuid, p_balance_delta numeric, p_reserved_delta numeric)
returns game.wallets language plpgsql set search_path = pg_catalog, pg_temp as $$
declare v game.wallets;
begin
  select * into v from game.wallets where user_id = p_user for update;
  if not found then perform game.err('WALLET_NOT_FOUND', 'Carteira não encontrada'); end if;
  if p_balance_delta < 0 or p_reserved_delta > 0 then
    if v.frozen then
      perform game.err('WALLET_LOCKED', 'Carteira bloqueada para revisão; fale com o suporte');
    end if;
    if (v.balance + p_balance_delta) - (v.reserved + p_reserved_delta) < 0 then
      perform game.err('INSUFFICIENT_FUNDS', 'Saldo disponível insuficiente',
                       jsonb_build_object('available', game.money_text(v.balance - v.reserved)));
    end if;
  end if;
  update game.wallets
     set balance = balance + p_balance_delta,
         reserved = reserved + p_reserved_delta,
         version = version + 1,
         updated_at = clock_timestamp()
   where user_id = p_user
  returning * into v;
  return v;
end $$;

create or replace function game.lock_wallets(p_users uuid[]) returns void
language plpgsql set search_path = pg_catalog, pg_temp as $$
begin
  perform 1 from game.wallets where user_id = any(p_users) order by user_id for update;
end $$;

create or replace function game.lock_proposals(p_ids uuid[]) returns void
language plpgsql set search_path = pg_catalog, pg_temp as $$
begin
  perform 1 from game.proposals where id = any(p_ids) order by id for update;
end $$;

create or replace function game.wallet_json(p_user uuid) returns jsonb
language sql stable set search_path = pg_catalog, pg_temp as $$
  select jsonb_build_object(
           'balance',   game.money_text(w.balance),
           'reserved',  game.money_text(w.reserved),
           'available', game.money_text(w.balance - w.reserved),
           'frozen',    w.frozen)
    from game.wallets w where w.user_id = p_user
$$;

-- ---------------------------------------------------------------------------
-- Reservas de propostas
-- ---------------------------------------------------------------------------
create or replace function game.hold_create(p_user uuid, p_proposal uuid, p_amount numeric) returns void
language plpgsql set search_path = pg_catalog, pg_temp as $$
begin
  perform game.wallet_apply(p_user, 0, p_amount);
  insert into game.wallet_holds (user_id, proposal_id, amount) values (p_user, p_proposal, p_amount);
end $$;

create or replace function game.hold_release(p_proposal uuid, p_reason text) returns void
language plpgsql set search_path = pg_catalog, pg_temp as $$
declare h game.wallet_holds;
begin
  select * into h from game.wallet_holds where proposal_id = p_proposal and status = 'active';
  if not found then return; end if;
  perform game.wallet_apply(h.user_id, 0, -h.amount);
  update game.wallet_holds set status = 'released', release_reason = p_reason, released_at = clock_timestamp()
   where id = h.id;
end $$;

create or replace function game.hold_consume(p_proposal uuid) returns numeric
language plpgsql set search_path = pg_catalog, pg_temp as $$
declare h game.wallet_holds;
begin
  select * into h from game.wallet_holds where proposal_id = p_proposal and status = 'active';
  if not found then
    perform game.err('HOLD_MISSING', 'Reserva da proposta não encontrada', jsonb_build_object('proposal_id', p_proposal));
  end if;
  perform game.wallet_apply(h.user_id, -h.amount, -h.amount);
  update game.wallet_holds set status = 'consumed', release_reason = 'consumed', released_at = clock_timestamp()
   where id = h.id;
  return h.amount;
end $$;

-- ---------------------------------------------------------------------------
-- Avisos (persistidos) + outbox (push). Deduplicação por usuário+tipo+evento.
-- ---------------------------------------------------------------------------
create or replace function game.notify(p_user uuid, p_type text, p_event_key text, p_payload jsonb, p_push boolean default true)
returns void language plpgsql set search_path = pg_catalog, pg_temp as $$
declare v_id uuid;
begin
  if p_user is null then return; end if;
  insert into game.notifications (user_id, type, event_key, payload)
  values (p_user, p_type, p_event_key, coalesce(p_payload, '{}'::jsonb))
  on conflict (user_id, type, event_key) do nothing
  returning id into v_id;
  if v_id is not null and p_push then
    insert into game.outbox (topic, user_id, payload)
    values ('push', p_user, jsonb_build_object('notification_id', v_id, 'type', p_type) || coalesce(p_payload, '{}'::jsonb));
  end if;
end $$;

-- ---------------------------------------------------------------------------
-- Renda: cálculo sob demanda; coleta credita centésimos e guarda a fração.
-- Encerramento credita o resíduo representável (8 casas) ao dono que sai.
-- ---------------------------------------------------------------------------
create or replace function game.settle_income(p_ownership_id uuid, p_at timestamptz, p_close boolean,
                                              inout p_tx uuid, p_tx_kind text, p_op uuid, out credited numeric)
language plpgsql set search_path = pg_catalog, pg_temp as $$
declare o game.ownerships; v_acc numeric; v_credit numeric; v_rest numeric := 0; v_disc numeric := 0; v_at timestamptz;
begin
  select * into o from game.ownerships where id = p_ownership_id for update;
  if not found or o.ended_at is not null then
    perform game.err('PROPERTY_CHANGED', 'Titularidade já encerrada');
  end if;
  v_at := greatest(p_at, o.income_checkpoint_at);
  v_acc := game.accrued_income(o.purchase_price, o.rate_snapshot, o.income_checkpoint_at, v_at, o.income_fraction);
  if p_close then
    v_credit := trunc(v_acc, 8);
    v_disc := v_acc - v_credit;
  else
    v_credit := game.floor_cents(v_acc);
    v_rest := trunc(v_acc - v_credit, 18);
  end if;
  if v_credit > 0 then
    if p_tx is null then
      p_tx := game.ledger_tx(p_tx_kind, p_op, jsonb_build_object('reason', 'income'));
    end if;
    perform game.ledger_post(p_tx, game.acct_issuance(), -v_credit, 'income', o.property_id, o.id);
    perform game.ledger_post(p_tx, game.account_of(o.owner_id), v_credit, 'income', o.property_id, o.id);
    perform game.wallet_apply(o.owner_id, v_credit, 0);
  end if;
  update game.ownerships
     set income_checkpoint_at = v_at,
         income_fraction = v_rest,
         income_credited = income_credited + v_credit,
         income_discarded = income_discarded + trunc(v_disc, 18)
   where id = o.id;
  credited := v_credit;
end $$;

-- ---------------------------------------------------------------------------
-- Propostas: expiração lógica pelo horário; materialização sob lock.
-- ---------------------------------------------------------------------------
create or replace function game.expire_proposal(p_id uuid, p_now timestamptz) returns boolean
language plpgsql set search_path = pg_catalog, pg_temp as $$
declare p game.proposals; v_until timestamptz;
begin
  select * into p from game.proposals where id = p_id;
  if not found or p.status <> 'pending' or p.expires_at > p_now then return false; end if;
  v_until := p.expires_at + make_interval(secs => (p.rules_snapshot->>'forced_purchase_window_s')::int);
  update game.proposals
     set status = 'expired', closed_at = p.expires_at, eligible_until = v_until,
         close_reason = 'no_response', version = version + 1
   where id = p_id;
  perform game.hold_release(p_id, 'expired');
  perform game.notify(p.buyer_id, 'proposal.expired', p_id::text,
    jsonb_build_object('proposal_id', p_id, 'property_id', p.property_id,
                       'eligible_until', v_until, 'forced_price', game.money_text(p.forced_price)));
  perform game.notify(p.seller_id, 'proposal.unanswered', p_id::text,
    jsonb_build_object('proposal_id', p_id, 'property_id', p.property_id, 'eligible_until', v_until));
  return true;
end $$;

create or replace function game.materialize_expired_for_buyer(p_buyer uuid, p_now timestamptz) returns integer
language plpgsql set search_path = pg_catalog, pg_temp as $$
declare r record; n int := 0;
begin
  for r in select id from game.proposals
            where buyer_id = p_buyer and status = 'pending' and expires_at <= p_now
            order by id loop
    if game.expire_proposal(r.id, p_now) then n := n + 1; end if;
  end loop;
  return n;
end $$;

-- Trava propostas afetadas por uma transferência (em ordem de ID) e devolve os
-- compradores com reserva ativa, para o chamador travar as carteiras em ordem.
create or replace function game.transfer_lock_set(p_ownership uuid, p_buyer uuid, p_extra uuid[] default '{}')
returns uuid[] language plpgsql set search_path = pg_catalog, pg_temp as $$
declare v_ids uuid[]; v_users uuid[];
begin
  select coalesce(array_agg(id order by id), '{}') into v_ids
    from game.proposals
   where (p_ownership is not null and ownership_id = p_ownership and status in ('pending','expired'))
      or (buyer_id = p_buyer and status = 'pending' and expires_at <= clock_timestamp() + interval '5 seconds')
      or id = any(p_extra);
  perform game.lock_proposals(v_ids);
  select coalesce(array_agg(distinct buyer_id), '{}') into v_users
    from game.proposals where id = any(v_ids) and status = 'pending';
  return v_users;
end $$;

-- ---------------------------------------------------------------------------
-- Antiabuso: sinais determinísticos; bloqueio preventivo com revisão registrada.
-- ---------------------------------------------------------------------------
create or replace function game.assess_trade_risk(p_context text, p_buyer uuid, p_seller uuid, p_property uuid,
                                                  p_amount numeric, p_reference numeric, p_now timestamptz)
returns jsonb language plpgsql set search_path = pg_catalog, pg_temp as $$
declare
  v_score int := 0; v_reasons text[] := '{}'; v_base numeric; v_age interval; v_bal numeric;
  v_pair int; v_vel int; v_threshold int; v_approved boolean;
begin
  select c.base_price into v_base
    from game.properties p join game.categories c on c.id = p.category_id where p.id = p_property;

  if exists (select 1 from game.trades t
              where t.buyer_id = p_seller and t.seller_id = p_buyer and t.occurred_at > p_now - interval '30 days') then
    v_score := v_score + 40; v_reasons := array_append(v_reasons, 'pair_cycle');
  end if;

  select count(*) into v_pair from game.trades t
   where ((t.buyer_id = p_buyer and t.seller_id = p_seller) or (t.buyer_id = p_seller and t.seller_id = p_buyer))
     and t.occurred_at > p_now - interval '30 days';
  if v_pair >= 3 then v_score := v_score + 30; v_reasons := array_append(v_reasons, 'repeated_pair'); end if;

  if v_base is not null and p_amount > v_base * 10 then
    v_score := v_score + 40; v_reasons := array_append(v_reasons, 'price_far_above_category');
  elsif v_base is not null and p_amount > v_base * 3 then
    v_score := v_score + 20; v_reasons := array_append(v_reasons, 'price_above_category');
  end if;

  select p_now - created_at into v_age from game.profiles where id = p_buyer;
  select balance into v_bal from game.wallets where user_id = p_buyer;
  if v_age < interval '7 days' and p_amount >= 0.5 * greatest(coalesce(v_bal, 0), 0.01)
     and p_amount > coalesce(p_reference, 0) * 1.5 then
    v_score := v_score + 25; v_reasons := array_append(v_reasons, 'new_account_overpay');
  end if;

  select count(*) into v_vel from game.trades t
   where (t.buyer_id in (p_buyer, p_seller) or t.seller_id in (p_buyer, p_seller))
     and t.occurred_at > p_now - interval '24 hours';
  if v_vel >= 10 then v_score := v_score + 20; v_reasons := array_append(v_reasons, 'high_velocity'); end if;

  if v_score > 0 then
    insert into game.risk_signals (context, buyer_id, seller_id, property_id, amount, score, reasons)
    values (p_context, p_buyer, p_seller, p_property, p_amount, v_score, v_reasons);
  end if;

  v_threshold := coalesce((game.cfg()->>'risk_block_score')::int, 60);
  select exists (select 1 from game.risk_reviews r
                  where r.buyer_id = p_buyer and r.seller_id = p_seller and r.property_id = p_property
                    and r.status = 'approved' and r.decided_at > p_now - interval '7 days') into v_approved;

  return jsonb_build_object('score', v_score, 'reasons', to_jsonb(v_reasons),
                            'blocked', v_score >= v_threshold and not v_approved);
end $$;

create or replace function game.enforce_trade_risk(p_context text, p_buyer uuid, p_seller uuid, p_property uuid,
                                                   p_amount numeric, p_reference numeric, p_now timestamptz)
returns void language plpgsql set search_path = pg_catalog, pg_temp as $$
declare v jsonb;
begin
  v := game.assess_trade_risk(p_context, p_buyer, p_seller, p_property, p_amount, p_reference, p_now);
  if (v->>'blocked')::boolean then
    perform game.err('RISK_REVIEW', 'Negociação enviada para revisão preventiva da equipe',
      jsonb_build_object('context', p_context, 'buyer_id', p_buyer, 'seller_id', p_seller, 'property_id', p_property,
                         'amount', p_amount, 'score', v->'score', 'reasons', v->'reasons'));
  end if;
end $$;

-- ---------------------------------------------------------------------------
-- Transferência de titularidade (passos 7–11 do pseudofluxo).
-- Pré-condições: propriedade, propostas e carteiras já travadas pelo chamador.
-- ---------------------------------------------------------------------------
create or replace function game.transfer_property(
  p_property_id uuid, p_buyer uuid, p_price numeric, p_trade_type text,
  p_proposal_id uuid, p_use_hold boolean, p_op uuid, p_now timestamptz, p_cfg jsonb)
returns jsonb language plpgsql set search_path = pg_catalog, pg_temp as $$
declare
  v_prop game.properties; v_old game.ownerships; v_seller uuid; v_tx uuid; v_kind text; v_via text;
  v_income numeric := 0; v_new_id uuid; v_trade_id uuid; v_held numeric; r record;
begin
  select * into v_prop from game.properties where id = p_property_id;
  v_kind := case p_trade_type
              when 'system_purchase'   then 'system_purchase'
              when 'voluntary'         then 'voluntary_sale'
              when 'forced_silence'    then 'forced_sale_silence'
              when 'forced_inactivity' then 'forced_sale_inactivity' end;
  v_via := case p_trade_type when 'system_purchase' then 'system' else p_trade_type end;
  if v_kind is null then perform game.err('VALIDATION_FAILED', 'Tipo de transferência inválido'); end if;

  v_tx := game.ledger_tx(v_kind, p_op, jsonb_build_object(
            'property_id', p_property_id, 'price', game.money_text(p_price), 'proposal_id', p_proposal_id));

  if v_prop.current_ownership_id is not null then
    select * into v_old from game.ownerships where id = v_prop.current_ownership_id;
    v_seller := v_old.owner_id;
    if v_seller = p_buyer then perform game.err('ALREADY_OWNER', 'Você já é o dono deste imóvel'); end if;
    -- Liquida a renda do dono antigo até o instante efetivo (sem lacuna nem sobreposição).
    select s.credited into v_income from game.settle_income(v_old.id, p_now, true, v_tx, v_kind, p_op) s;
  end if;

  -- Pagamento: reserva da proposta (aceite) ou saldo disponível (compra do sistema / sem aceite).
  if p_use_hold then
    v_held := game.hold_consume(p_proposal_id);
    if v_held <> p_price then perform game.err('HOLD_MISMATCH', 'Reserva diferente do valor da proposta'); end if;
  else
    perform game.wallet_apply(p_buyer, -p_price, 0);
  end if;
  perform game.ledger_post(v_tx, game.account_of(p_buyer), -p_price, 'price', p_property_id, null);
  if v_seller is null then
    perform game.ledger_post(v_tx, game.acct_treasury(), p_price, 'price', p_property_id, null);
  else
    perform game.ledger_post(v_tx, game.account_of(v_seller), p_price, 'price', p_property_id, v_old.id);
    perform game.wallet_apply(v_seller, p_price, 0);
  end if;

  -- Fecha a titularidade antiga e abre a nova no mesmo instante.
  if v_old.id is not null then
    update game.ownerships
       set ended_at = p_now, end_reason = case when p_trade_type = 'voluntary' then 'sold' else 'forced_sale' end
     where id = v_old.id;
  end if;
  insert into game.ownerships (property_id, owner_id, purchase_price, acquired_at, acquired_via,
                               income_checkpoint_at, rate_snapshot, config_version)
  values (p_property_id, p_buyer, p_price, p_now, v_via, p_now,
          (p_cfg->>'daily_income_rate')::numeric, (p_cfg->>'_version')::int)
  returning id into v_new_id;

  update game.properties
     set current_ownership_id = v_new_id, version = version + 1, last_transfer_at = p_now
   where id = p_property_id;

  -- Invalida as demais autorizações baseadas na titularidade anterior.
  if v_old.id is not null then
    for r in select id, status, buyer_id from game.proposals
              where ownership_id = v_old.id and status in ('pending','expired')
                and id is distinct from p_proposal_id
              order by id loop
      update game.proposals
         set status = 'superseded', closed_at = coalesce(closed_at, p_now),
             close_reason = 'ownership_changed', version = version + 1
       where id = r.id;
      if r.status = 'pending' then perform game.hold_release(r.id, 'superseded'); end if;
      perform game.notify(r.buyer_id, 'proposal.superseded', r.id::text,
                          jsonb_build_object('proposal_id', r.id, 'property_id', p_property_id));
    end loop;
  end if;

  insert into game.trades (property_id, old_ownership_id, new_ownership_id, buyer_id, seller_id, price, type,
                           proposal_id, ledger_transaction_id, operation_id, occurred_at)
  values (p_property_id, v_old.id, v_new_id, p_buyer, v_seller, p_price, p_trade_type,
          p_proposal_id, v_tx, p_op, p_now)
  returning id into v_trade_id;

  if v_seller is not null then
    perform game.notify(v_seller,
      case when p_trade_type = 'voluntary' then 'property.sold' else 'property.forced_sale' end,
      v_trade_id::text,
      jsonb_build_object('trade_id', v_trade_id, 'property_id', p_property_id, 'price', game.money_text(p_price),
                         'type', p_trade_type, 'income_settled', game.money_text(v_income)));
  end if;

  return jsonb_build_object(
    'trade_id', v_trade_id, 'ownership_id', v_new_id, 'old_ownership_id', v_old.id,
    'ledger_transaction_id', v_tx, 'income_settled_to_seller', game.money_text(v_income),
    'property_version', v_prop.version + 1);
end $$;

-- Preço sem aceite = arredondar_para_cima_em_centésimos(última_compra × prêmio)
create or replace function game.forced_floor_price(p_purchase_price numeric, p_premium numeric) returns numeric
language sql immutable set search_path = pg_catalog as $$
  select game.ceil_cents(p_purchase_price * p_premium)
$$;
