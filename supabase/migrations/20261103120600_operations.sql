-- Dono da Vez — 0007: operações econômicas (corpo interno, SECURITY INVOKER).
-- Cada função recebe o actor derivado do JWT pela Edge Function e o id da operação.
-- Ordem global de locks: propriedade → atividade do dono → propostas (ID) → carteiras (UUID) → holds/titularidades.

create or replace function game.consume_proof(p_proof_id uuid, p_actor uuid, p_property_id uuid, p_op uuid)
returns void language plpgsql set search_path = pg_catalog, pg_temp as $$
declare v game.presence_proofs;
begin
  if p_proof_id is null then
    perform game.err('PRESENCE_REQUIRED', 'Confirme sua localização perto do imóvel');
  end if;
  select * into v from game.presence_proofs where id = p_proof_id for update;
  if not found or v.actor_id <> p_actor then
    perform game.err('PRESENCE_REQUIRED', 'Confirme sua localização perto do imóvel');
  end if;
  if v.property_id <> p_property_id then
    perform game.err('PRESENCE_REQUIRED', 'A localização confirmada é de outro imóvel', jsonb_build_object('reason', 'other_property'));
  end if;
  if v.consumed_by_operation is not null then
    perform game.err('PRESENCE_REQUIRED', 'Esta confirmação de localização já foi usada', jsonb_build_object('reason', 'already_used'));
  end if;
  if v.expires_at <= clock_timestamp() then
    perform game.err('LOCATION_STALE', 'Localização expirada; confirme novamente');
  end if;
  update game.presence_proofs set consumed_by_operation = p_op, consumed_at = clock_timestamp() where id = p_proof_id;
end $$;

create or replace function game.check_property_open(p game.properties, p_expected_version bigint)
returns void language plpgsql stable set search_path = pg_catalog, pg_temp as $$
declare v_release text;
begin
  if p_expected_version is not null and p.version <> p_expected_version then
    perform game.err('PROPERTY_CHANGED', 'O imóvel mudou; confira a ficha atualizada',
                     jsonb_build_object('property_version', p.version));
  end if;
  select release_status into v_release from game.regions where id = p.region_id;
  if v_release is distinct from 'released' then
    perform game.err('REGION_UNAVAILABLE', 'Região ainda não disponível');
  end if;
  if p.eligibility <> 'eligible' then
    perform game.err('PROPERTY_UNAVAILABLE', 'Imóvel indisponível para negociação no momento',
                     jsonb_build_object('eligibility', p.eligibility));
  end if;
end $$;

create or replace function game.property_summary(p_property_id uuid) returns jsonb
language sql stable set search_path = pg_catalog, extensions, pg_temp as $$
  select jsonb_build_object(
           'id', p.id, 'category', c.code, 'subtype', p.subtype,
           'address_label', p.address_label,
           'address_status', case when p.address_label is null then 'missing' else 'informed' end,
           'lat', round(extensions.st_y(p.point)::numeric, 6), 'lon', round(extensions.st_x(p.point)::numeric, 6))
    from game.properties p left join game.categories c on c.id = p.category_id
   where p.id = p_property_id
$$;

-- Estado lógico: pendente vencida aparece como expirada mesmo antes do job.
create or replace function game.proposal_json(p_id uuid, p_viewer uuid) returns jsonb
language sql stable set search_path = pg_catalog, pg_temp as $$
  select jsonb_build_object(
    'id', pr.id,
    'property', game.property_summary(pr.property_id),
    'ownership_id', pr.ownership_id,
    'direction', case when pr.buyer_id = p_viewer then 'sent' else 'received' end,
    'counterpart_nickname', case when pr.buyer_id = p_viewer then s.nickname else b.nickname end,
    'amount', game.money_text(pr.amount),
    'status', case when pr.status = 'pending' and pr.expires_at <= now() then 'expired' else pr.status end,
    'created_at', pr.created_at,
    'expires_at', pr.expires_at,
    'eligible_until', case
       when pr.status = 'pending' and pr.expires_at <= now()
         then pr.expires_at + make_interval(secs => (pr.rules_snapshot->>'forced_purchase_window_s')::int)
       else pr.eligible_until end,
    'forced_price', game.money_text(pr.forced_price),
    'version', pr.version,
    'rules_version', pr.rules_version,
    'trade_id', pr.trade_id)
  from game.proposals pr
  join game.profiles b on b.id = pr.buyer_id
  join game.profiles s on s.id = pr.seller_id
  where pr.id = p_id and (pr.buyer_id = p_viewer or pr.seller_id = p_viewer)
$$;

-- ---------------------------------------------------------------------------
-- Saldo inicial (uma vez por conta elegível e por e-mail)
-- ---------------------------------------------------------------------------
create or replace function game.grant_initial_balance(p_actor uuid) returns boolean
language plpgsql set search_path = pg_catalog, pg_temp as $$
declare v game.profiles; v_op uuid; v_tx uuid; v_amount numeric;
begin
  select * into v from game.profiles where id = p_actor for update;
  if not found or v.initial_grant_at is not null or v.email_confirmed_at is null or v.status <> 'active' then
    return false;
  end if;
  if v.email_sha256 is not null then
    insert into game.grant_registry (email_sha256, profile_id) values (v.email_sha256, p_actor)
    on conflict (email_sha256) do nothing;
    if not found then return false; end if;
  end if;
  perform game.lock_wallets(array[p_actor]);
  insert into game.operations (actor_id, idempotency_key, kind, status)
  values (p_actor, 'system:initial_grant', 'initial_grant', 'in_progress')
  on conflict (actor_id, idempotency_key) do nothing
  returning id into v_op;
  if v_op is null then return false; end if;
  v_amount := (game.cfg()->>'initial_balance')::numeric;
  v_tx := game.ledger_tx('initial_grant', v_op, jsonb_build_object('reason', 'saldo inicial'));
  perform game.ledger_post(v_tx, game.acct_issuance(), -v_amount, 'initial_grant');
  perform game.ledger_post(v_tx, game.account_of(p_actor), v_amount, 'initial_grant');
  perform game.wallet_apply(p_actor, v_amount, 0);
  update game.profiles set initial_grant_at = clock_timestamp() where id = p_actor;
  perform game.op_succeed(v_op, jsonb_build_object('granted', game.money_text(v_amount)));
  perform game.notify(p_actor, 'wallet.initial_grant', 'initial', jsonb_build_object('amount', game.money_text(v_amount)), false);
  return true;
end $$;

-- ---------------------------------------------------------------------------
-- 4.1 Compra do sistema
-- ---------------------------------------------------------------------------
create or replace function game.do_purchase(p_actor uuid, p_op uuid, p_property_id uuid, p_proof_id uuid, p_expected_version bigint)
returns jsonb language plpgsql set search_path = pg_catalog, pg_temp as $$
declare v_cfg jsonb := game.cfg(); v_prop game.properties; v_price numeric; v_now timestamptz; v_res jsonb;
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
  return v_res || jsonb_build_object(
    'property_id', p_property_id,
    'purchase_price', game.money_text(v_price),
    'daily_income', game.money_text(v_price * (v_cfg->>'daily_income_rate')::numeric),
    'acquired_at', v_now,
    'wallet', game.wallet_json(p_actor));
end $$;

-- ---------------------------------------------------------------------------
-- 4.2 Proposta voluntária: presença no envio + reserva integral
-- ---------------------------------------------------------------------------
create or replace function game.do_create_proposal(p_actor uuid, p_op uuid, p_property_id uuid, p_ownership_id uuid,
                                                   p_amount numeric, p_proof_id uuid)
returns jsonb language plpgsql set search_path = pg_catalog, pg_temp as $$
declare
  v_cfg jsonb := game.cfg(); v_prop game.properties; v_own game.ownerships; v_now timestamptz; v_id uuid;
  v_last timestamptz; v_open int; v_existing uuid; v_snapshot jsonb; v_forced numeric;
begin
  perform game.require_player(p_actor);
  if p_amount is null or p_amount <= 0 or p_amount <> round(p_amount, 2)
     or p_amount > (v_cfg->>'max_offer_amount')::numeric then
    perform game.err('INVALID_AMOUNT', 'Valor inválido: use um número positivo com até duas casas decimais');
  end if;

  select * into v_prop from game.properties where id = p_property_id for update;
  if not found then perform game.err('PROPERTY_NOT_FOUND', 'Imóvel não encontrado'); end if;
  perform game.consume_proof(p_proof_id, p_actor, p_property_id, p_op);
  if v_prop.current_ownership_id is null then
    perform game.err('PROPERTY_CHANGED', 'Imóvel sem dono: compre pelo preço do sistema', jsonb_build_object('reason', 'no_owner'));
  end if;
  if p_ownership_id is not null and p_ownership_id <> v_prop.current_ownership_id then
    perform game.err('PROPERTY_CHANGED', 'O dono mudou; confira a ficha atualizada', jsonb_build_object('reason', 'owner_changed'));
  end if;
  select * into v_own from game.ownerships where id = v_prop.current_ownership_id;
  if v_own.owner_id = p_actor then perform game.err('SELF_PROPOSAL', 'Você já é o dono deste imóvel'); end if;

  perform game.transfer_lock_set(null, p_actor);
  perform game.lock_wallets(array[p_actor]);
  v_now := clock_timestamp();
  perform game.check_property_open(v_prop, null);
  perform game.materialize_expired_for_buyer(p_actor, v_now);

  select id into v_existing from game.proposals
   where buyer_id = p_actor and property_id = p_property_id and status = 'pending';
  if v_existing is not null then
    perform game.err('PROPOSAL_EXISTS', 'Você já tem uma proposta aberta para este imóvel', jsonb_build_object('proposal_id', v_existing));
  end if;

  select max(closed_at) into v_last from game.proposals
   where buyer_id = p_actor and property_id = p_property_id and status <> 'pending';
  if v_last is not null and v_last > v_now - make_interval(secs => (v_cfg->>'proposal_cooldown_s')::int) then
    perform game.err('PROPOSAL_COOLDOWN', 'Aguarde para enviar nova proposta a este imóvel',
      jsonb_build_object('retry_at', v_last + make_interval(secs => (v_cfg->>'proposal_cooldown_s')::int)));
  end if;

  select count(*) into v_open from game.proposals where buyer_id = p_actor and status = 'pending';
  if v_open >= (v_cfg->>'max_open_proposals_per_buyer')::int then
    perform game.err('PROPOSAL_LIMIT', 'Limite de propostas abertas atingido',
                     jsonb_build_object('max', (v_cfg->>'max_open_proposals_per_buyer')::int));
  end if;

  perform game.enforce_trade_risk('proposal', p_actor, v_own.owner_id, p_property_id, p_amount, v_own.purchase_price, v_now);

  v_snapshot := jsonb_build_object(
    'proposal_response_window_s', (v_cfg->>'proposal_response_window_s')::int,
    'forced_purchase_window_s',   (v_cfg->>'forced_purchase_window_s')::int,
    'forced_premium',             v_cfg->>'forced_premium',
    'transfer_protection_s',      (v_cfg->>'transfer_protection_s')::int,
    'proposal_cooldown_s',        (v_cfg->>'proposal_cooldown_s')::int,
    'daily_income_rate',          v_cfg->>'daily_income_rate');
  v_forced := greatest(p_amount, game.forced_floor_price(v_own.purchase_price, (v_cfg->>'forced_premium')::numeric));

  insert into game.proposals (property_id, ownership_id, buyer_id, seller_id, amount, status,
                              purchase_price_snapshot, forced_price, created_at, expires_at,
                              rules_version, rules_snapshot)
  values (p_property_id, v_own.id, p_actor, v_own.owner_id, p_amount, 'pending',
          v_own.purchase_price, v_forced, v_now,
          v_now + make_interval(secs => (v_cfg->>'proposal_response_window_s')::int),
          (v_cfg->>'_version')::int, v_snapshot)
  returning id into v_id;
  perform game.hold_create(p_actor, v_id, p_amount);

  perform game.notify(v_own.owner_id, 'proposal.received', v_id::text,
    jsonb_build_object('proposal_id', v_id, 'property_id', p_property_id, 'amount', game.money_text(p_amount),
                       'expires_at', v_now + make_interval(secs => (v_cfg->>'proposal_response_window_s')::int)));
  return jsonb_build_object('proposal', game.proposal_json(v_id, p_actor), 'wallet', game.wallet_json(p_actor));
end $$;

-- ---------------------------------------------------------------------------
-- Aceite: debita a reserva, credita o vendedor, invalida as demais propostas
-- ---------------------------------------------------------------------------
create or replace function game.do_accept_proposal(p_actor uuid, p_op uuid, p_proposal_id uuid, p_expected_version integer)
returns jsonb language plpgsql set search_path = pg_catalog, pg_temp as $$
declare v_cfg jsonb := game.cfg(); v_pid uuid; v_prop game.properties; v_p game.proposals; v_users uuid[];
        v_now timestamptz; v_res jsonb; v_buyer_status text;
begin
  perform game.require_player(p_actor);
  select property_id into v_pid from game.proposals where id = p_proposal_id and seller_id = p_actor;
  if v_pid is null then perform game.err('PROPOSAL_NOT_FOUND', 'Proposta não encontrada'); end if;

  select * into v_prop from game.properties where id = v_pid for update;
  v_users := game.transfer_lock_set(v_prop.current_ownership_id, null, array[p_proposal_id]);
  select * into v_p from game.proposals where id = p_proposal_id;
  if p_expected_version is not null and v_p.version <> p_expected_version then
    perform game.err('PROPOSAL_CHANGED', 'A proposta foi alterada em outro aparelho', jsonb_build_object('version', v_p.version));
  end if;
  if v_p.status <> 'pending' then
    perform game.err('PROPOSAL_NOT_PENDING', 'Esta proposta não está mais pendente', jsonb_build_object('status', v_p.status));
  end if;
  if v_p.ownership_id is distinct from v_prop.current_ownership_id then
    perform game.err('PROPERTY_CHANGED', 'A titularidade mudou');
  end if;
  perform game.lock_wallets(v_users || v_p.seller_id || v_p.buyer_id);
  v_now := clock_timestamp();
  if v_p.expires_at <= v_now then
    perform game.err('PROPOSAL_EXPIRED', 'O prazo de resposta terminou', jsonb_build_object('expires_at', v_p.expires_at));
  end if;
  perform game.check_property_open(v_prop, null);
  select status into v_buyer_status from game.profiles where id = v_p.buyer_id;
  if v_buyer_status <> 'active' then
    perform game.err('COUNTERPARTY_UNAVAILABLE', 'O comprador não pode concluir negociações no momento');
  end if;
  perform game.enforce_trade_risk('accept', v_p.buyer_id, v_p.seller_id, v_pid, v_p.amount, v_p.purchase_price_snapshot, v_now);

  v_res := game.transfer_property(v_pid, v_p.buyer_id, v_p.amount, 'voluntary', p_proposal_id, true, p_op, v_now, v_cfg);
  update game.proposals
     set status = 'accepted', closed_at = v_now, close_reason = 'accepted', version = version + 1,
         trade_id = (v_res->>'trade_id')::uuid
   where id = p_proposal_id;
  perform game.notify(v_p.buyer_id, 'proposal.accepted', p_proposal_id::text,
    jsonb_build_object('proposal_id', p_proposal_id, 'property_id', v_pid, 'amount', game.money_text(v_p.amount),
                       'trade_id', v_res->>'trade_id'));
  return v_res || jsonb_build_object('proposal', game.proposal_json(p_proposal_id, p_actor), 'wallet', game.wallet_json(p_actor));
end $$;

-- Recusa tempestiva: libera a reserva e impede a via de compra por silêncio.
create or replace function game.do_reject_proposal(p_actor uuid, p_op uuid, p_proposal_id uuid, p_expected_version integer)
returns jsonb language plpgsql set search_path = pg_catalog, pg_temp as $$
declare v_p game.proposals; v_now timestamptz;
begin
  perform game.require_player(p_actor);
  select * into v_p from game.proposals where id = p_proposal_id and seller_id = p_actor for update;
  if not found then perform game.err('PROPOSAL_NOT_FOUND', 'Proposta não encontrada'); end if;
  if p_expected_version is not null and v_p.version <> p_expected_version then
    perform game.err('PROPOSAL_CHANGED', 'A proposta foi alterada em outro aparelho', jsonb_build_object('version', v_p.version));
  end if;
  if v_p.status <> 'pending' then
    perform game.err('PROPOSAL_NOT_PENDING', 'Esta proposta não está mais pendente', jsonb_build_object('status', v_p.status));
  end if;
  perform game.lock_wallets(array[v_p.buyer_id]);
  v_now := clock_timestamp();
  if v_p.expires_at <= v_now then
    perform game.err('PROPOSAL_EXPIRED', 'O prazo de resposta terminou', jsonb_build_object('expires_at', v_p.expires_at));
  end if;
  update game.proposals
     set status = 'rejected', closed_at = v_now, close_reason = 'rejected_by_seller', version = version + 1
   where id = p_proposal_id;
  perform game.hold_release(p_proposal_id, 'rejected');
  perform game.notify(v_p.buyer_id, 'proposal.rejected', p_proposal_id::text,
    jsonb_build_object('proposal_id', p_proposal_id, 'property_id', v_p.property_id));
  return jsonb_build_object('proposal', game.proposal_json(p_proposal_id, p_actor));
end $$;

create or replace function game.do_cancel_proposal(p_actor uuid, p_op uuid, p_proposal_id uuid, p_expected_version integer)
returns jsonb language plpgsql set search_path = pg_catalog, pg_temp as $$
declare v_p game.proposals; v_now timestamptz;
begin
  perform game.require_player(p_actor, false);
  select * into v_p from game.proposals where id = p_proposal_id and buyer_id = p_actor for update;
  if not found then perform game.err('PROPOSAL_NOT_FOUND', 'Proposta não encontrada'); end if;
  if p_expected_version is not null and v_p.version <> p_expected_version then
    perform game.err('PROPOSAL_CHANGED', 'A proposta foi alterada em outro aparelho', jsonb_build_object('version', v_p.version));
  end if;
  if v_p.status <> 'pending' then
    perform game.err('PROPOSAL_NOT_PENDING', 'Esta proposta não está mais pendente', jsonb_build_object('status', v_p.status));
  end if;
  perform game.lock_wallets(array[p_actor]);
  v_now := clock_timestamp();
  if v_p.expires_at <= v_now then
    perform game.err('PROPOSAL_EXPIRED', 'O prazo de resposta terminou', jsonb_build_object('expires_at', v_p.expires_at));
  end if;
  update game.proposals
     set status = 'cancelled', closed_at = v_now, close_reason = 'cancelled_by_buyer', version = version + 1
   where id = p_proposal_id;
  perform game.hold_release(p_proposal_id, 'cancelled');
  perform game.notify(v_p.seller_id, 'proposal.cancelled', p_proposal_id::text,
    jsonb_build_object('proposal_id', p_proposal_id, 'property_id', v_p.property_id), false);
  return jsonb_build_object('proposal', game.proposal_json(p_proposal_id, p_actor), 'wallet', game.wallet_json(p_actor));
end $$;

-- ---------------------------------------------------------------------------
-- 4.3 Compra sem aceite (silêncio ou inatividade): presencial, +20%, condições
-- reavaliadas no commit com a atividade do dono travada.
-- ---------------------------------------------------------------------------
create or replace function game.do_forced_purchase(p_actor uuid, p_op uuid, p_property_id uuid, p_reason text,
                                                   p_proposal_id uuid, p_proof_id uuid, p_expected_version bigint)
returns jsonb language plpgsql set search_path = pg_catalog, pg_temp as $$
declare
  v_cfg jsonb := game.cfg(); v_prop game.properties; v_own game.ownerships; v_act game.player_activity;
  v_p game.proposals; v_users uuid[]; v_now timestamptz; v_price numeric; v_type text; v_res jsonb; v_prot_until timestamptz;
begin
  perform game.require_player(p_actor);
  if p_reason is null or p_reason not in ('silence', 'inactivity') then
    perform game.err('VALIDATION_FAILED', 'Motivo inválido');
  end if;
  if p_reason = 'silence' and p_proposal_id is null then
    perform game.err('VALIDATION_FAILED', 'Informe a proposta expirada');
  end if;

  select * into v_prop from game.properties where id = p_property_id for update;
  if not found then perform game.err('PROPERTY_NOT_FOUND', 'Imóvel não encontrado'); end if;
  perform game.consume_proof(p_proof_id, p_actor, p_property_id, p_op);
  if p_expected_version is not null and v_prop.version <> p_expected_version then
    perform game.err('PROPERTY_CHANGED', 'O imóvel mudou; confira a ficha atualizada', jsonb_build_object('property_version', v_prop.version));
  end if;
  if v_prop.current_ownership_id is null then
    perform game.err('PROPERTY_CHANGED', 'Imóvel sem dono: compre pelo preço do sistema', jsonb_build_object('reason', 'no_owner'));
  end if;
  select * into v_own from game.ownerships where id = v_prop.current_ownership_id;
  if v_own.owner_id = p_actor then perform game.err('ALREADY_OWNER', 'Você já é o dono deste imóvel'); end if;

  if p_reason = 'inactivity' then
    select * into v_act from game.player_activity where user_id = v_own.owner_id for update;
  end if;
  v_users := game.transfer_lock_set(v_own.id, p_actor,
               case when p_proposal_id is null then '{}'::uuid[] else array[p_proposal_id] end);
  perform game.lock_wallets(v_users || p_actor || v_own.owner_id);
  v_now := clock_timestamp();

  perform game.check_property_open(v_prop, null);
  v_prot_until := v_prop.last_transfer_at + make_interval(secs => (v_cfg->>'transfer_protection_s')::int);
  if v_prot_until > v_now then
    perform game.err('PROTECTION_ACTIVE', 'Imóvel protegido após transferência recente', jsonb_build_object('protected_until', v_prot_until));
  end if;

  if p_reason = 'inactivity' then
    if v_act.last_qualified_activity_at > v_now - make_interval(secs => (v_cfg->>'owner_inactivity_s')::int) then
      perform game.err('OWNER_ACTIVE', 'O dono está ativo; envie uma proposta');
    end if;
    v_price := game.forced_floor_price(v_own.purchase_price, (v_cfg->>'forced_premium')::numeric);
    v_type := 'forced_inactivity';
  else
    select * into v_p from game.proposals where id = p_proposal_id;
    if not found or v_p.buyer_id <> p_actor then
      perform game.err('PROPOSAL_NOT_FOUND', 'Proposta não encontrada');
    end if;
    if v_p.ownership_id <> v_own.id or v_p.status = 'superseded' then
      perform game.err('PROPERTY_CHANGED', 'A titularidade mudou; o direito desta proposta foi invalidado', jsonb_build_object('reason', 'owner_changed'));
    end if;
    if v_p.status = 'pending' and v_p.expires_at <= v_now then
      perform game.expire_proposal(v_p.id, v_now);
      select * into v_p from game.proposals where id = p_proposal_id;
    end if;
    if v_p.status = 'pending' then
      perform game.err('PROPOSAL_PENDING', 'Aguarde o prazo de resposta do dono', jsonb_build_object('expires_at', v_p.expires_at));
    end if;
    if v_p.status <> 'expired' then
      perform game.err('PROPOSAL_NOT_ELIGIBLE', 'Esta proposta não dá direito a compra sem aceite', jsonb_build_object('status', v_p.status));
    end if;
    if v_p.eligible_until <= v_now then
      perform game.err('WINDOW_CLOSED', 'A janela para compra sem aceite terminou', jsonb_build_object('eligible_until', v_p.eligible_until));
    end if;
    v_price := v_p.forced_price;
    v_type := 'forced_silence';
  end if;

  perform game.materialize_expired_for_buyer(p_actor, v_now);
  perform game.enforce_trade_risk('forced_' || p_reason, p_actor, v_own.owner_id, p_property_id, v_price, v_own.purchase_price, v_now);

  v_res := game.transfer_property(p_property_id, p_actor, v_price, v_type,
                                  case when p_reason = 'silence' then p_proposal_id end, false, p_op, v_now, v_cfg);
  if p_reason = 'silence' then
    update game.proposals
       set status = 'executed_without_acceptance', close_reason = 'executed_without_acceptance',
           version = version + 1, trade_id = (v_res->>'trade_id')::uuid
     where id = p_proposal_id;
  end if;
  return v_res || jsonb_build_object(
    'property_id', p_property_id, 'reason', p_reason,
    'purchase_price', game.money_text(v_price),
    'daily_income', game.money_text(v_price * (v_cfg->>'daily_income_rate')::numeric),
    'acquired_at', v_now,
    'wallet', game.wallet_json(p_actor));
end $$;

-- ---------------------------------------------------------------------------
-- 3.4 Coleta: credita o acumulado e avança o checkpoint na mesma transação
-- ---------------------------------------------------------------------------
create or replace function game.do_collect_income(p_actor uuid, p_op uuid, p_ids uuid[])
returns jsonb language plpgsql set search_path = pg_catalog, pg_temp as $$
declare v_props uuid[]; v_tx uuid; v_now timestamptz; r record; v_c numeric; v_total numeric := 0;
        v_items jsonb := '[]'::jsonb; v_done uuid[] := '{}';
begin
  perform game.require_player(p_actor);
  if p_ids is null or cardinality(p_ids) = 0 or cardinality(p_ids) > 50 then
    perform game.err('VALIDATION_FAILED', 'Informe de 1 a 50 titularidades');
  end if;
  select coalesce(array_agg(distinct property_id), '{}') into v_props
    from game.ownerships where id = any(p_ids) and owner_id = p_actor and ended_at is null;
  perform 1 from game.properties where id = any(v_props) order by id for update;
  perform game.lock_wallets(array[p_actor]);
  v_now := clock_timestamp();
  for r in select o.id, o.property_id
             from game.ownerships o
             join game.properties p on p.id = o.property_id and p.current_ownership_id = o.id
            where o.id = any(p_ids) and o.owner_id = p_actor and o.ended_at is null
            order by o.property_id loop
    select s.p_tx, s.credited into v_tx, v_c from game.settle_income(r.id, v_now, false, v_tx, 'income', p_op) s;
    v_total := v_total + v_c;
    v_done := v_done || r.id;
    v_items := v_items || jsonb_build_object('ownership_id', r.id, 'property_id', r.property_id, 'credited', game.money_text(v_c));
  end loop;
  return jsonb_build_object(
    'collected', game.money_text(v_total),
    'collected_at', v_now,
    'items', v_items,
    'skipped', to_jsonb(array(select x from unnest(p_ids) x where not x = any(v_done))),
    'ledger_transaction_id', v_tx,
    'wallet', game.wallet_json(p_actor));
end $$;

-- ---------------------------------------------------------------------------
-- 13 Exclusão: bloqueia novas ações, cancela propostas e libera reservas.
-- A devolução dos imóveis ao sistema é feita em lotes retomáveis pelo job.
-- ---------------------------------------------------------------------------
create or replace function game.do_request_account_deletion(p_actor uuid, p_op uuid)
returns jsonb language plpgsql set search_path = pg_catalog, pg_temp as $$
declare v game.profiles; v_ids uuid[]; r record; v_owned int;
begin
  select * into v from game.profiles where id = p_actor for update;
  if not found then perform game.err('PROFILE_NOT_FOUND', 'Perfil não encontrado'); end if;
  if v.status in ('deletion_pending', 'deleted') then
    return jsonb_build_object('status', v.status, 'requested_at', v.deletion_requested_at);
  end if;
  select coalesce(array_agg(id order by id), '{}') into v_ids from game.proposals where buyer_id = p_actor and status = 'pending';
  perform game.lock_proposals(v_ids);
  perform game.lock_wallets(array[p_actor]);
  for r in select id, seller_id, property_id from game.proposals where id = any(v_ids) and status = 'pending' order by id loop
    update game.proposals set status = 'cancelled', closed_at = clock_timestamp(), close_reason = 'account_deletion', version = version + 1
     where id = r.id;
    perform game.hold_release(r.id, 'account_deletion');
    perform game.notify(r.seller_id, 'proposal.cancelled', r.id::text,
                        jsonb_build_object('proposal_id', r.id, 'property_id', r.property_id), false);
  end loop;
  update game.profiles set status = 'deletion_pending', deletion_requested_at = clock_timestamp() where id = p_actor;
  update game.device_tokens set revoked_at = now() where user_id = p_actor and revoked_at is null;
  select count(*) into v_owned from game.ownerships where owner_id = p_actor and ended_at is null;
  return jsonb_build_object('status', 'deletion_pending', 'requested_at', clock_timestamp(), 'properties_to_return', v_owned);
end $$;
