-- Dono da Vez — 0015: bônus de inauguração.
-- Imóvel comprado do sistema rende um adicional (+4% ao dia sobre o preço pago) nos 7 primeiros dias, além da
-- renda permanente de 1% ao dia. O bônus é fixado na titularidade no momento da compra, como a taxa base:
-- mudar a configuração depois não altera titularidades existentes. Compras entre jogadores não têm bônus
-- (o preço vai para o vendedor, não sai de circulação, e revender não pode gerar bônus de novo).
-- "daily_income" nas respostas continua sendo a renda permanente; o bônus vem em "opening_bonus".

-- ---------------------------------------------------------------------------
-- 1. Bônus fixado na titularidade
-- ---------------------------------------------------------------------------
alter table game.ownerships
  add column opening_bonus_rate  numeric(12,8) not null default 0 check (opening_bonus_rate >= 0),
  add column opening_bonus_until timestamptz,
  add constraint ownerships_opening_bonus_consistent
    check ((opening_bonus_until is null) = (opening_bonus_rate = 0)),
  add constraint ownerships_opening_bonus_after_start
    check (opening_bonus_until is null or opening_bonus_until > acquired_at);

-- Lido da versão de configuração gravada na própria titularidade (a mesma usada na compra).
create or replace function game.set_opening_bonus() returns trigger
language plpgsql set search_path = pg_catalog, pg_temp as $$
declare v jsonb; v_rate numeric; v_secs integer;
begin
  new.opening_bonus_rate := 0;
  new.opening_bonus_until := null;
  if new.acquired_via = 'system' then
    select c.values into v from game.game_config c where c.version = new.config_version;
    v_rate := coalesce((v->>'opening_bonus_rate')::numeric, 0);
    v_secs := coalesce((v->>'opening_bonus_s')::integer, 0);
    if v_rate > 0 and v_secs > 0 then
      new.opening_bonus_rate := v_rate;
      new.opening_bonus_until := new.acquired_at + make_interval(secs => v_secs);
    end if;
  end if;
  return new;
end $$;

create trigger ownerships_opening_bonus before insert on game.ownerships
  for each row execute function game.set_opening_bonus();

-- ---------------------------------------------------------------------------
-- 2. Cálculo: renda permanente + parte do intervalo que cai dentro da inauguração
-- ---------------------------------------------------------------------------
create or replace function game.ownership_accrued(o game.ownerships, p_to timestamptz) returns numeric
language sql immutable set search_path = pg_catalog, pg_temp as $$
  select game.accrued_income(o.purchase_price, o.rate_snapshot, o.income_checkpoint_at, p_to, o.income_fraction)
       + case when o.opening_bonus_until is null then 0
              else o.purchase_price * o.opening_bonus_rate
                   * greatest(extract(epoch from (least(p_to, o.opening_bonus_until) - o.income_checkpoint_at)), 0) / 86400
         end
$$;

-- Bônus ainda ativo de uma titularidade (null quando não tem ou já terminou).
create or replace function game.opening_bonus_json(o game.ownerships, p_at timestamptz) returns jsonb
language sql immutable set search_path = pg_catalog, pg_temp as $$
  select case when o.opening_bonus_until > p_at
              then jsonb_build_object('daily', game.money_text(o.purchase_price * o.opening_bonus_rate),
                                      'until', o.opening_bonus_until)
         end
$$;

-- Bônus que uma compra do sistema pelo preço informado receberia com a configuração vigente.
create or replace function game.opening_bonus_offer(p_price numeric, p_cfg jsonb) returns jsonb
language sql immutable set search_path = pg_catalog, pg_temp as $$
  select case when coalesce((p_cfg->>'opening_bonus_rate')::numeric, 0) > 0
               and coalesce((p_cfg->>'opening_bonus_s')::integer, 0) > 0 and p_price is not null
              then jsonb_build_object('daily', game.money_text(p_price * (p_cfg->>'opening_bonus_rate')::numeric),
                                      'seconds', (p_cfg->>'opening_bonus_s')::integer)
         end
$$;

revoke execute on function game.set_opening_bonus(), game.ownership_accrued(game.ownerships, timestamptz),
  game.opening_bonus_json(game.ownerships, timestamptz), game.opening_bonus_offer(numeric, jsonb) from public;
grant execute on function game.set_opening_bonus(), game.ownership_accrued(game.ownerships, timestamptz),
  game.opening_bonus_json(game.ownerships, timestamptz), game.opening_bonus_offer(numeric, jsonb) to dv_engine;

-- ---------------------------------------------------------------------------
-- 3. Coleta e liquidação na venda (antes: game.accrued_income com a taxa base)
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
  v_acc := game.ownership_accrued(o, v_at);
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
-- 4. Compra do sistema: a resposta informa o bônus da nova titularidade
-- ---------------------------------------------------------------------------
create or replace function game.do_purchase(p_actor uuid, p_op uuid, p_property_id uuid, p_proof_id uuid, p_expected_version bigint)
returns jsonb language plpgsql set search_path = pg_catalog, pg_temp as $$
declare v_cfg jsonb := game.cfg(); v_prop game.properties; v_price numeric; v_now timestamptz; v_res jsonb;
        v_own game.ownerships;
begin
  perform game.require_player(p_actor);
  select * into v_prop from game.properties where id = p_property_id for update;
  if not found then perform game.err('PROPERTY_NOT_FOUND', 'Imóvel não encontrado'); end if;
  perform game.consume_proof(p_proof_id, p_actor, p_property_id, p_op);
  perform game.transfer_lock_set(null, p_actor);
  perform game.lock_wallets(array[p_actor]);
  v_now := clock_timestamp();

  if v_prop.current_ownership_id is not null then
    perform game.err('PROPERTY_CHANGED', 'Este imóvel já tem dono', jsonb_build_object('reason', 'owned', 'property_version', v_prop.version));
  end if;
  perform game.check_property_open(v_prop, p_expected_version);
  select base_price into v_price from game.categories where id = v_prop.category_id and active;
  if v_price is null then perform game.err('PROPERTY_UNAVAILABLE', 'Categoria indisponível'); end if;

  perform game.materialize_expired_for_buyer(p_actor, v_now);
  v_res := game.transfer_property(p_property_id, p_actor, v_price, 'system_purchase', null, false, p_op, v_now, v_cfg);
  select * into v_own from game.ownerships where id = (v_res->>'ownership_id')::uuid;
  return v_res || jsonb_build_object(
    'property_id', p_property_id,
    'purchase_price', game.money_text(v_price),
    'daily_income', game.money_text(v_price * (v_cfg->>'daily_income_rate')::numeric),
    'opening_bonus', game.opening_bonus_json(v_own, v_now),
    'acquired_at', v_now,
    'wallet', game.wallet_json(p_actor));
end $$;

-- ---------------------------------------------------------------------------
-- 5. Ficha do imóvel: bônus da compra (visitante) e bônus ativo (dono)
-- ---------------------------------------------------------------------------
create or replace function game_api.property_detail(p_actor uuid, p_property_id uuid) returns jsonb
language plpgsql stable security definer set search_path = pg_catalog, extensions, pg_temp as $$
declare
  v_cfg jsonb := game.cfg(); p game.properties; v_cat game.categories; v_region game.regions; v_own game.ownerships;
  v_owner game.profiles; v_act game.player_activity; v_my game.proposals; v_now timestamptz := now();
  v_rate numeric := (v_cfg->>'daily_income_rate')::numeric; v_open boolean; v_prot_until timestamptz;
  v_inactive boolean := false; v_my_status text; v_my_until timestamptz; v_received int := 0; v_accrued numeric;
  v_mine boolean := false;
begin
  select * into p from game.properties where id = p_property_id;
  if not found or p.eligibility = 'removed_from_source' and p.current_ownership_id is null then
    perform game.err('PROPERTY_NOT_FOUND', 'Imóvel não encontrado');
  end if;
  select * into v_cat from game.categories where id = p.category_id;
  select * into v_region from game.regions where id = p.region_id;
  v_open := v_region.release_status = 'released' and p.eligibility = 'eligible';
  v_prot_until := p.last_transfer_at + make_interval(secs => (v_cfg->>'transfer_protection_s')::int);

  if p.current_ownership_id is not null then
    select * into v_own from game.ownerships where id = p.current_ownership_id;
    select * into v_owner from game.profiles where id = v_own.owner_id;
    select * into v_act from game.player_activity where user_id = v_own.owner_id;
    v_mine := v_own.owner_id = p_actor;
    v_inactive := coalesce(v_act.last_qualified_activity_at <= v_now - make_interval(secs => (v_cfg->>'owner_inactivity_s')::int), false);
    select * into v_my from game.proposals
     where property_id = p.id and buyer_id = p_actor and ownership_id = v_own.id and status in ('pending','expired')
     order by created_at desc limit 1;
    if found then
      v_my_status := case when v_my.status = 'pending' and v_my.expires_at <= v_now then 'expired' else v_my.status end;
      v_my_until := case when v_my.status = 'pending'
                         then v_my.expires_at + make_interval(secs => (v_my.rules_snapshot->>'forced_purchase_window_s')::int)
                         else v_my.eligible_until end;
    end if;
    if v_mine then
      select count(*) into v_received from game.proposals
       where ownership_id = v_own.id and status = 'pending' and expires_at > v_now;
      v_accrued := game.ownership_accrued(v_own, v_now);
    end if;
  end if;

  return jsonb_build_object(
    'server_time', v_now,
    'property', game.property_summary(p.id) || jsonb_build_object(
       'version', p.version,
       'category_name', v_cat.name,
       'classification_status', p.classification_status,
       'classification_confidence', p.classification_confidence,
       'eligibility', p.eligibility,
       'eligibility_reason', p.eligibility_reason,
       'interaction_point', jsonb_build_object('lat', round(st_y(p.interaction_point)::numeric, 6), 'lon', round(st_x(p.interaction_point)::numeric, 6)),
       'interaction_quality', p.interaction_quality,
       'height_m', p.height_m, 'height_source', p.height_source, 'visual_model', p.visual_model,
       'units_count', p.units_count,
       'region', jsonb_build_object('code', v_region.code, 'name', v_region.name, 'release_status', v_region.release_status, 'attribution', v_region.attribution)),
    'ownership', case when v_own.id is null then null else jsonb_build_object(
       'ownership_id', v_own.id,
       'owner_nickname', v_owner.nickname,
       'owner_avatar_id', v_owner.avatar_id,
       'is_mine', v_mine,
       'purchase_price', game.money_text(v_own.purchase_price),
       'acquired_at', v_own.acquired_at,
       'owner_daily_income', game.money_text(v_own.purchase_price * v_own.rate_snapshot),
       'protected_until', case when v_prot_until > v_now then v_prot_until end) end,
    'reference_price', game.money_text(coalesce(v_own.purchase_price, v_cat.base_price)),
    'daily_income_rate', v_cfg->>'daily_income_rate',
    'mine', case when not v_mine then null else jsonb_build_object(
       'accrued', game.money_text(trunc(v_accrued, 8)),
       'collectable', game.money_text(game.floor_cents(v_accrued)),
       'opening_bonus', game.opening_bonus_json(v_own, v_now),
       'pending_offers', v_received) end,
    'my_proposal', case when v_my.id is null then null else game.proposal_json(v_my.id, p_actor) end,
    'actions', jsonb_build_object(
       'purchase', jsonb_build_object(
          'allowed', v_open and v_own.id is null and v_cat.id is not null,
          'price', case when v_own.id is null then game.money_text(v_cat.base_price) end,
          'daily_income', case when v_own.id is null then game.money_text(v_cat.base_price * v_rate) end,
          'opening_bonus', case when v_own.id is null then game.opening_bonus_offer(v_cat.base_price, v_cfg) end,
          'requires_presence', true,
          'reason', case when not v_open then 'unavailable' when v_own.id is not null then 'owned' end),
       'propose', jsonb_build_object(
          'allowed', v_open and v_own.id is not null and not v_mine and coalesce(v_my_status, '') <> 'pending',
          'min_amount', '0.01',
          'requires_presence', true,
          'reason', case when not v_open then 'unavailable' when v_own.id is null then 'no_owner'
                         when v_mine then 'mine' when v_my_status = 'pending' then 'proposal_exists' end),
       'forced_inactivity', jsonb_build_object(
          'allowed', v_open and v_own.id is not null and not v_mine and v_inactive
                     and (v_prot_until is null or v_prot_until <= v_now),
          'price', case when v_own.id is not null and not v_mine
                        then game.money_text(game.forced_floor_price(v_own.purchase_price, (v_cfg->>'forced_premium')::numeric)) end,
          'requires_presence', true,
          -- Mesma ordem de verificação de game.do_forced_purchase.
          'reason', case when v_own.id is null or v_mine then null
                         when not v_open then 'unavailable'
                         when v_prot_until > v_now then 'protection_active'
                         when not v_inactive then 'owner_active' end),
       'forced_silence', jsonb_build_object(
          'allowed', coalesce(v_open and v_my_status = 'expired' and v_my_until > v_now
                     and (v_prot_until is null or v_prot_until <= v_now), false),
          'proposal_id', case when v_my_status = 'expired' then v_my.id end,
          'price', case when v_my_status = 'expired' then game.money_text(v_my.forced_price) end,
          'eligible_until', case when v_my_status = 'expired' then v_my_until end,
          'requires_presence', true)),
    'history', coalesce((select jsonb_agg(jsonb_build_object(
                  'trade_id', t.id, 'type', t.type, 'price', game.money_text(t.price), 'occurred_at', t.occurred_at,
                  'buyer_nickname', b.nickname, 'seller_nickname', s.nickname) order by t.occurred_at desc)
                 from (select * from game.trades where property_id = p.id order by occurred_at desc limit 10) t
                 left join game.profiles b on b.id = t.buyer_id
                 left join game.profiles s on s.id = t.seller_id), '[]'::jsonb));
end $$;

-- ---------------------------------------------------------------------------
-- 6. Portfólio e carteira: acumulado com o bônus; bônus ativo por imóvel e no total
-- ---------------------------------------------------------------------------
create or replace function game_api.portfolio(p_actor uuid, p_cursor text default null, p_limit integer default 50)
returns jsonb language plpgsql stable security definer set search_path = pg_catalog, pg_temp as $$
declare v_lim int := least(greatest(coalesce(p_limit, 50), 1), 100); c record; v_items jsonb; v_n int; v_next text;
        v_now timestamptz := now(); v_totals jsonb;
begin
  select * into c from game.cursor_decode(p_cursor);
  select coalesce(jsonb_agg(x.j order by x.acquired_at desc, x.id desc), '[]'::jsonb), count(*)
    into v_items, v_n
    from (select o.id, o.acquired_at,
                 jsonb_build_object(
                   'ownership_id', o.id,
                   'acquired_at', o.acquired_at,
                   'acquired_via', o.acquired_via,
                   'purchase_price', game.money_text(o.purchase_price),
                   'daily_income', game.money_text(o.purchase_price * o.rate_snapshot),
                   'opening_bonus', game.opening_bonus_json(o, v_now),
                   'accrued', game.money_text(trunc(game.ownership_accrued(o, v_now), 8)),
                   'collectable', game.money_text(game.floor_cents(game.ownership_accrued(o, v_now))),
                   'income_credited', game.money_text(o.income_credited),
                   'property', game.property_summary(o.property_id) || jsonb_build_object('eligibility', p.eligibility, 'version', p.version),
                   'pending_offers', (select count(*) from game.proposals pr where pr.ownership_id = o.id and pr.status = 'pending' and pr.expires_at > v_now)
                 ) as j
            from game.ownerships o join game.properties p on p.id = o.property_id
           where o.owner_id = p_actor and o.ended_at is null
             and (c.ts is null or (o.acquired_at, o.id) < (c.ts, c.id::uuid))
           order by o.acquired_at desc, o.id desc
           limit v_lim + 1) x;
  if v_n > v_lim then
    v_items := v_items - v_lim;
    v_next := game.cursor_encode((v_items -> (v_lim - 1) ->> 'acquired_at')::timestamptz, v_items -> (v_lim - 1) ->> 'ownership_id');
  end if;
  select jsonb_build_object(
           'count', count(*),
           'daily_income', game.money_text(coalesce(sum(o.purchase_price * o.rate_snapshot), 0)),
           'opening_bonus_daily', game.money_text(coalesce(sum(o.purchase_price * o.opening_bonus_rate)
                                                             filter (where o.opening_bonus_until > v_now), 0)),
           'collectable', game.money_text(coalesce(sum(game.floor_cents(game.ownership_accrued(o, v_now))), 0)),
           'invested', game.money_text(coalesce(sum(o.purchase_price), 0)))
    into v_totals
    from game.ownerships o where o.owner_id = p_actor and o.ended_at is null;
  return jsonb_build_object('items', v_items, 'next_cursor', v_next, 'totals', v_totals, 'server_time', v_now);
end $$;

create or replace function game_api.wallet(p_actor uuid, p_cursor text default null, p_limit integer default 50)
returns jsonb language plpgsql stable security definer set search_path = pg_catalog, pg_temp as $$
declare v_lim int := least(greatest(coalesce(p_limit, 50), 1), 100); v_acct uuid; v_cur bigint; v_items jsonb; v_n int;
        v_next text; v_collectable numeric;
begin
  select account_id into v_acct from game.wallets where user_id = p_actor;
  if v_acct is null then perform game.err('WALLET_NOT_FOUND', 'Carteira não encontrada'); end if;
  if p_cursor is not null and p_cursor !~ '^[0-9]{1,18}$' then perform game.err('VALIDATION_FAILED', 'Cursor inválido'); end if;
  v_cur := p_cursor::bigint;
  select coalesce(jsonb_agg(x.j order by x.id desc), '[]'::jsonb), count(*) into v_items, v_n
    from (select e.id, jsonb_build_object(
                   'entry_id', e.id, 'amount', game.money_text(e.amount), 'entry_type', e.entry_type,
                   'transaction_id', e.transaction_id, 'transaction_kind', t.kind, 'created_at', t.created_at,
                   'property', case when e.property_id is null then null else game.property_summary(e.property_id) end,
                   'proposal_id', t.metadata->>'proposal_id') as j
            from game.ledger_entries e join game.ledger_transactions t on t.id = e.transaction_id
           where e.account_id = v_acct and (v_cur is null or e.id < v_cur)
           order by e.id desc limit v_lim + 1) x;
  if v_n > v_lim then
    v_items := v_items - v_lim;
    v_next := (v_items -> (v_lim - 1) ->> 'entry_id');
  end if;
  select coalesce(sum(game.floor_cents(game.ownership_accrued(o, now()))), 0)
    into v_collectable from game.ownerships o where o.owner_id = p_actor and o.ended_at is null;
  return jsonb_build_object(
    'wallet', game.wallet_json(p_actor) || jsonb_build_object('collectable_income', game.money_text(v_collectable)),
    'entries', v_items, 'next_cursor', v_next,
    'holds', coalesce((select jsonb_agg(jsonb_build_object(
                'hold_id', h.id, 'proposal_id', h.proposal_id, 'amount', game.money_text(h.amount), 'status', h.status,
                'release_reason', h.release_reason, 'created_at', h.created_at, 'released_at', h.released_at)
                order by h.created_at desc)
              from (select * from game.wallet_holds where user_id = p_actor
                     order by (status = 'active') desc, created_at desc limit 30) h), '[]'::jsonb),
    'server_time', now());
end $$;

-- ---------------------------------------------------------------------------
-- 7. Relatório diário: taxa de emissão do bônus ativo, separada da renda permanente
-- ---------------------------------------------------------------------------
create or replace function game_internal.economy_daily_report(p_date date default (now() at time zone 'utc')::date - 1) returns jsonb
language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare v jsonb; v_from timestamptz := p_date::timestamptz; v_to timestamptz := (p_date + 1)::timestamptz;
begin
  with player_bal as (select balance from game.wallets),
       day_entries as (select e.entry_type, e.amount, e.account_id from game.ledger_entries e
                        where e.created_at >= v_from and e.created_at < v_to),
       income_by_owner as (select o.owner_id, sum(o.purchase_price * o.rate_snapshot) as daily
                             from game.ownerships o where o.ended_at is null group by o.owner_id),
       ranked as (select daily, row_number() over (order by daily desc) rn, sum(daily) over () total from income_by_owner)
  select jsonb_build_object(
    'date', p_date,
    'money_supply', game.money_text((select coalesce(sum(balance), 0) from player_bal)),
    'total_issued', game.money_text(-(select coalesce(sum(amount), 0) from game.ledger_entries where account_id = game.acct_issuance())),
    'treasury', game.money_text((select coalesce(sum(amount), 0) from game.ledger_entries where account_id = game.acct_treasury())),
    'issued_today_income', game.money_text((select coalesce(sum(amount), 0) from day_entries where entry_type = 'income' and account_id <> game.acct_issuance())),
    'issued_today_initial', game.money_text((select coalesce(sum(amount), 0) from day_entries where entry_type = 'initial_grant' and account_id <> game.acct_issuance())),
    'daily_income_run_rate', game.money_text((select coalesce(sum(daily), 0) from income_by_owner)),
    'opening_bonus_run_rate', game.money_text((select coalesce(sum(o.purchase_price * o.opening_bonus_rate), 0)
                                                 from game.ownerships o where o.ended_at is null and o.opening_bonus_until > now())),
    'top10_income_share', (select round(coalesce(sum(daily) filter (where rn <= 10) / nullif(max(total), 0), 0), 4) from ranked),
    'owners', (select count(*) from income_by_owner),
    'owned_properties', (select count(*) from game.ownerships where ended_at is null),
    'trades', (select jsonb_object_agg(type, n) from (select type, count(*) n from game.trades
                where occurred_at >= v_from and occurred_at < v_to group by type) t),
    'avg_voluntary_price_ratio', (select round(avg(t.price / c.base_price), 4) from game.trades t
                join game.properties p on p.id = t.property_id join game.categories c on c.id = p.category_id
               where t.type = 'voluntary' and t.occurred_at >= v_from and t.occurred_at < v_to),
    'active_players', (select count(*) from game.player_activity where last_qualified_activity_at >= v_from),
    'new_players', (select count(*) from game.profiles where created_at >= v_from and created_at < v_to),
    'risk_signals', (select count(*) from game.risk_signals where created_at >= v_from and created_at < v_to),
    'risk_reviews_pending', (select count(*) from game.risk_reviews where status = 'pending'),
    'pair_cycles', (select count(*) from game.risk_signals where created_at >= v_from and created_at < v_to and 'pair_cycle' = any(reasons))
  ) into v;
  insert into game.economy_daily_reports (report_date, metrics) values (p_date, v)
  on conflict (report_date) do update set metrics = excluded.metrics, created_at = now();
  return v;
end $$;

-- ---------------------------------------------------------------------------
-- 8. Nova versão da configuração (patch sobre a vigente). O simulador em tests/economy lê este bloco.
--    Depois de publicada, o painel pode ajustar ou zerar o bônus com uma nova versão.
-- ---------------------------------------------------------------------------
insert into game.game_config (version, values, note)
select (select coalesce(max(version), 0) + 1 from game.game_config),
       (game.cfg() - '_version') || $patch${
         "opening_bonus_rate": "0.04",
         "opening_bonus_s": 604800
       }$patch$::jsonb,
       'Bônus de inauguração: +4% ao dia nos 7 primeiros dias de cada compra do sistema';
