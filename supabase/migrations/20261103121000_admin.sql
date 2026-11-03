-- Dono da Vez — 0011: console administrativo. Papéis guardados no servidor (game.admin_roles),
-- MFA exigido na Edge Function (aal2). Nenhuma edição direta de saldo: apenas ajuste compensatório.

create or replace function game.require_admin(p_admin uuid, p_roles text[]) returns void
language plpgsql stable set search_path = pg_catalog, pg_temp as $$
begin
  if p_admin is null or not exists (
       select 1 from game.admin_roles
        where user_id = p_admin and revoked_at is null and (role = 'superadmin' or role = any(p_roles))) then
    perform game.err('FORBIDDEN', 'Permissão administrativa insuficiente');
  end if;
end $$;

create or replace function game.audit(p_admin uuid, p_action text, p_target_type text, p_target_id text, p_reason text,
                                      p_before jsonb, p_after jsonb, p_op uuid default null)
returns void language plpgsql set search_path = pg_catalog, pg_temp as $$
begin
  if p_reason is null or length(trim(p_reason)) < 3 then
    perform game.err('VALIDATION_FAILED', 'Informe o motivo (mínimo 3 caracteres)');
  end if;
  insert into game.admin_audit (actor_id, action, target_type, target_id, reason, before, after, operation_id)
  values (p_admin, p_action, p_target_type, p_target_id, trim(p_reason), p_before, p_after, p_op);
end $$;

-- Inicia operação administrativa idempotente; devolve replay quando já concluída.
create or replace function game.admin_op(p_admin uuid, p_key text, p_kind text, p_hash text, out op_id uuid, out replay jsonb)
language plpgsql set search_path = pg_catalog, pg_temp as $$
declare v record;
begin
  select * into v from game.op_begin(p_admin, p_key, 'admin.' || p_kind, p_hash);
  op_id := v.op_id; replay := v.replay;
end $$;

-- ---------------------------------------------------------------------------
-- Leituras
-- ---------------------------------------------------------------------------
create or replace function admin_api.whoami(p_admin uuid) returns jsonb
language plpgsql stable security definer set search_path = pg_catalog, pg_temp as $$
begin
  return jsonb_build_object(
    'profile', game.profile_json(p_admin),
    'roles', coalesce((select jsonb_agg(role order by role) from game.admin_roles where user_id = p_admin and revoked_at is null), '[]'::jsonb));
end $$;

create or replace function admin_api.search(p_admin uuid, p_q text) returns jsonb
language plpgsql stable security definer set search_path = pg_catalog, pg_temp as $$
declare v_q text := trim(coalesce(p_q, ''));
begin
  perform game.require_admin(p_admin, array['support','geo','finance']);
  if length(v_q) < 2 then perform game.err('VALIDATION_FAILED', 'Busca muito curta'); end if;
  return jsonb_build_object(
    'users', coalesce((select jsonb_agg(jsonb_build_object('id', p.id, 'nickname', p.nickname, 'status', p.status, 'created_at', p.created_at))
                         from (select * from game.profiles
                                where nickname ilike replace(replace(v_q, '%', ''), '_', '\_') || '%' or id::text = v_q
                                order by nickname limit 25) p), '[]'::jsonb),
    'properties', coalesce((select jsonb_agg(game.property_summary(p.id) || jsonb_build_object('eligibility', p.eligibility))
                              from (select * from game.properties
                                     where id::text = v_q or address_label ilike '%' || replace(v_q, '%', '') || '%'
                                     order by address_label limit 25) p), '[]'::jsonb));
end $$;

create or replace function admin_api.user_detail(p_admin uuid, p_user uuid) returns jsonb
language plpgsql stable security definer set search_path = pg_catalog, pg_temp as $$
declare v_acct uuid;
begin
  perform game.require_admin(p_admin, array['support','finance']);
  select account_id into v_acct from game.wallets where user_id = p_user;
  return jsonb_build_object(
    'profile', game.profile_json(p_user),
    'wallet', game.wallet_json(p_user) || coalesce((select jsonb_build_object('frozen_reason', frozen_reason) from game.wallets where user_id = p_user), '{}'::jsonb),
    'ledger', coalesce((select jsonb_agg(jsonb_build_object('entry_id', e.id, 'amount', game.money_text(e.amount), 'entry_type', e.entry_type,
                                                              'transaction_id', e.transaction_id, 'kind', t.kind, 'created_at', t.created_at) order by e.id desc)
                          from (select * from game.ledger_entries where account_id = v_acct order by id desc limit 100) e
                          join game.ledger_transactions t on t.id = e.transaction_id), '[]'::jsonb),
    'ownerships', coalesce((select jsonb_agg(jsonb_build_object('ownership_id', o.id, 'property_id', o.property_id,
                                   'purchase_price', game.money_text(o.purchase_price), 'acquired_at', o.acquired_at,
                                   'ended_at', o.ended_at, 'end_reason', o.end_reason) order by o.acquired_at desc)
                              from (select * from game.ownerships where owner_id = p_user order by acquired_at desc limit 100) o), '[]'::jsonb),
    'proposals', coalesce((select jsonb_agg(jsonb_build_object('id', pr.id, 'property_id', pr.property_id, 'amount', game.money_text(pr.amount),
                                   'status', pr.status, 'buyer_id', pr.buyer_id, 'seller_id', pr.seller_id, 'created_at', pr.created_at,
                                   'expires_at', pr.expires_at) order by pr.created_at desc)
                             from (select * from game.proposals where buyer_id = p_user or seller_id = p_user order by created_at desc limit 100) pr), '[]'::jsonb),
    'risk', coalesce((select jsonb_agg(jsonb_build_object('score', s.score, 'reasons', s.reasons, 'context', s.context, 'created_at', s.created_at) order by s.created_at desc)
                        from (select * from game.risk_signals where buyer_id = p_user or seller_id = p_user order by created_at desc limit 50) s), '[]'::jsonb),
    'roles', coalesce((select jsonb_agg(role) from game.admin_roles where user_id = p_user and revoked_at is null), '[]'::jsonb));
end $$;

create or replace function admin_api.property_detail(p_admin uuid, p_property uuid) returns jsonb
language plpgsql stable security definer set search_path = pg_catalog, extensions, pg_temp as $$
begin
  perform game.require_admin(p_admin, array['support','geo','finance']);
  if not exists (select 1 from game.properties where id = p_property) then perform game.err('PROPERTY_NOT_FOUND', 'Imóvel não encontrado'); end if;
  return jsonb_build_object(
    'property', (select to_jsonb(p) - 'point' - 'footprint' - 'interaction_point' - 'interaction_geog'
                        || jsonb_build_object('footprint', st_asgeojson(p.footprint)::jsonb,
                                              'interaction_point', st_asgeojson(p.interaction_point)::jsonb)
                   from game.properties p where p.id = p_property),
    'ownerships', coalesce((select jsonb_agg(jsonb_build_object('ownership_id', o.id, 'owner_id', o.owner_id, 'owner_nickname', pr.nickname,
                                   'purchase_price', game.money_text(o.purchase_price), 'acquired_at', o.acquired_at, 'acquired_via', o.acquired_via,
                                   'ended_at', o.ended_at, 'end_reason', o.end_reason, 'income_credited', game.money_text(o.income_credited)) order by o.acquired_at desc)
                              from game.ownerships o join game.profiles pr on pr.id = o.owner_id where o.property_id = p_property), '[]'::jsonb),
    'trades', coalesce((select jsonb_agg(to_jsonb(t) order by t.occurred_at desc) from game.trades t where t.property_id = p_property), '[]'::jsonb),
    'source_features', coalesce((select jsonb_agg(to_jsonb(sf)) from geo.source_features sf where sf.property_id = p_property), '[]'::jsonb),
    'review_cases', coalesce((select jsonb_agg(to_jsonb(c) order by c.created_at desc) from geo.review_cases c where c.property_id = p_property), '[]'::jsonb),
    'changes', coalesce((select jsonb_agg(to_jsonb(ch) order by ch.changed_at desc) from (select * from geo.property_changes where property_id = p_property order by changed_at desc limit 20) ch), '[]'::jsonb));
end $$;

create or replace function admin_api.list_review_cases(p_admin uuid, p_status text default 'open', p_limit integer default 100) returns jsonb
language plpgsql stable security definer set search_path = pg_catalog, pg_temp as $$
begin
  perform game.require_admin(p_admin, array['geo','support']);
  return coalesce((select jsonb_agg(to_jsonb(c) || jsonb_build_object('property', game.property_summary(c.property_id)) order by c.created_at)
                     from (select * from geo.review_cases where status = coalesce(p_status, 'open')
                            order by created_at limit least(coalesce(p_limit, 100), 500)) c), '[]'::jsonb);
end $$;

create or replace function admin_api.list_proposals(p_admin uuid, p_status text default null, p_user uuid default null, p_limit integer default 100) returns jsonb
language plpgsql stable security definer set search_path = pg_catalog, pg_temp as $$
begin
  perform game.require_admin(p_admin, array['support','finance']);
  return coalesce((select jsonb_agg(to_jsonb(pr) - 'rules_snapshot' order by pr.created_at desc)
                     from (select * from game.proposals
                            where (p_status is null or status = p_status)
                              and (p_user is null or buyer_id = p_user or seller_id = p_user)
                            order by created_at desc limit least(coalesce(p_limit, 100), 500)) pr), '[]'::jsonb);
end $$;

create or replace function admin_api.list_alerts(p_admin uuid, p_status text default 'open') returns jsonb
language plpgsql stable security definer set search_path = pg_catalog, pg_temp as $$
begin
  perform game.require_admin(p_admin, array['finance','support']);
  return coalesce((select jsonb_agg(to_jsonb(a) order by a.created_at desc)
                     from (select * from game.reconciliation_alerts where status = coalesce(p_status, 'open')
                            order by created_at desc limit 500) a), '[]'::jsonb);
end $$;

create or replace function admin_api.list_risk_reviews(p_admin uuid, p_status text default 'pending') returns jsonb
language plpgsql stable security definer set search_path = pg_catalog, pg_temp as $$
begin
  perform game.require_admin(p_admin, array['finance','support']);
  return coalesce((select jsonb_agg(to_jsonb(r) || jsonb_build_object(
                             'buyer_nickname', b.nickname, 'seller_nickname', s.nickname,
                             'property', game.property_summary(r.property_id)) order by r.created_at)
                     from (select * from game.risk_reviews where status = coalesce(p_status, 'pending')
                            order by created_at limit 500) r
                     join game.profiles b on b.id = r.buyer_id join game.profiles s on s.id = r.seller_id), '[]'::jsonb);
end $$;

create or replace function admin_api.list_import_batches(p_admin uuid, p_region_code text default null) returns jsonb
language plpgsql stable security definer set search_path = pg_catalog, pg_temp as $$
begin
  perform game.require_admin(p_admin, array['geo']);
  return coalesce((select jsonb_agg(to_jsonb(b) || jsonb_build_object('region_code', r.code) order by b.started_at desc)
                     from (select * from geo.import_batches order by started_at desc limit 100) b
                     join game.regions r on r.id = b.region_id
                    where p_region_code is null or r.code = p_region_code), '[]'::jsonb);
end $$;

create or replace function admin_api.list_regions(p_admin uuid) returns jsonb
language plpgsql stable security definer set search_path = pg_catalog, extensions, pg_temp as $$
begin
  perform game.require_admin(p_admin, array['geo','support']);
  return jsonb_build_object(
    'regions', coalesce((select jsonb_agg(jsonb_build_object(
             'id', r.id, 'code', r.code, 'name', r.name, 'release_status', r.release_status, 'import_status', r.import_status,
             'active_dataset_version', r.active_dataset_version, 'area_km2', round((st_area(r.polygon::geography) / 1e6)::numeric, 3),
             'properties', (select jsonb_object_agg(e.eligibility, e.n) from (select eligibility, count(*) n from game.properties p where p.region_id = r.id group by eligibility) e))
             order by r.code)
           from game.regions r), '[]'::jsonb),
    'region_interest', (select coalesce(jsonb_agg(jsonb_build_object('cell', i.cell, 'players', i.n) order by i.n desc), '[]'::jsonb)
                          from (select cell, count(*) n from game.region_interest group by cell order by count(*) desc limit 50) i));
end $$;

create or replace function admin_api.list_reports(p_admin uuid, p_status text default 'open') returns jsonb
language plpgsql stable security definer set search_path = pg_catalog, pg_temp as $$
begin
  perform game.require_admin(p_admin, array['support','geo']);
  return coalesce((select jsonb_agg(to_jsonb(r) || jsonb_build_object('protocol', 'DV-' || lpad(r.id::text, 6, '0'),
                                                                      'reporter_nickname', p.nickname) order by r.created_at)
                     from (select * from game.reports where status = coalesce(p_status, 'open') order by created_at limit 500) r
                     join game.profiles p on p.id = r.reporter_id), '[]'::jsonb);
end $$;

create or replace function admin_api.economy_reports(p_admin uuid, p_days integer default 30) returns jsonb
language plpgsql stable security definer set search_path = pg_catalog, pg_temp as $$
begin
  perform game.require_admin(p_admin, array['finance']);
  return coalesce((select jsonb_agg(jsonb_build_object('date', report_date, 'metrics', metrics) order by report_date desc)
                     from game.economy_daily_reports where report_date >= current_date - least(coalesce(p_days, 30), 365)), '[]'::jsonb);
end $$;

create or replace function admin_api.ops_metrics(p_admin uuid) returns jsonb
language plpgsql stable security definer set search_path = pg_catalog, pg_temp as $$
declare v jsonb;
begin
  perform game.require_admin(p_admin, array['support','finance','geo']);
  v := jsonb_build_object(
    'server_time', now(),
    'outbox', (select jsonb_object_agg(status, n) from (select status, count(*) n from game.outbox group by status) o),
    'outbox_oldest_pending_s', (select extract(epoch from now() - min(created_at))::int from game.outbox where status in ('pending','failed')),
    'proposals_pending', (select count(*) from game.proposals where status = 'pending'),
    'proposals_overdue_unmaterialized', (select count(*) from game.proposals where status = 'pending' and expires_at < now() - interval '2 minutes'),
    'orphan_holds', (select count(*) from game.wallet_holds h join game.proposals p on p.id = h.proposal_id where h.status = 'active' and p.status <> 'pending'),
    'alerts_open', (select count(*) from game.reconciliation_alerts where status = 'open'),
    'frozen_wallets', (select count(*) from game.wallets where frozen),
    'risk_reviews_pending', (select count(*) from game.risk_reviews where status = 'pending'),
    'review_cases_open', (select count(*) from geo.review_cases where status = 'open'),
    'gps_rejections_24h', (select jsonb_object_agg(reason, n) from (select reason, count(*) n from game.presence_rejections where created_at > now() - interval '24 hours' group by reason) g),
    'presence_proofs_24h', (select count(*) from game.presence_proofs where recorded_at > now() - interval '24 hours'),
    'operations_failed_24h', (select jsonb_object_agg(error_code, n) from (select error_code, count(*) n from game.operations where status = 'failed' and created_at > now() - interval '24 hours' group by error_code) f),
    'issued_24h', game.money_text((select coalesce(sum(e.amount), 0) from game.ledger_entries e where e.account_id <> game.acct_issuance() and e.entry_type in ('income','initial_grant') and e.created_at > now() - interval '24 hours')));
  return v;
end $$;

create or replace function admin_api.audit_log(p_admin uuid, p_before_id bigint default null, p_limit integer default 100) returns jsonb
language plpgsql stable security definer set search_path = pg_catalog, pg_temp as $$
begin
  perform game.require_admin(p_admin, array['superadmin']);
  return coalesce((select jsonb_agg(to_jsonb(a) order by a.id desc)
                     from (select * from game.admin_audit where p_before_id is null or id < p_before_id
                            order by id desc limit least(coalesce(p_limit, 100), 500)) a), '[]'::jsonb);
end $$;

-- ---------------------------------------------------------------------------
-- Mutações auditadas
-- ---------------------------------------------------------------------------
create or replace function admin_api.set_property_eligibility(p_admin uuid, p_key text, p_hash text, p_property uuid,
                                                              p_eligibility text, p_reason text)
returns jsonb language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare v_op record; p game.properties; v_res jsonb;
begin
  perform game.require_admin(p_admin, array['geo','support']);
  select * into v_op from game.admin_op(p_admin, p_key, 'property.eligibility', p_hash);
  if v_op.replay is not null then return v_op.replay; end if;
  if p_eligibility not in ('eligible','suspended','under_review','pending_classification') then
    perform game.err('VALIDATION_FAILED', 'Elegibilidade inválida');
  end if;
  select * into p from game.properties where id = p_property for update;
  if not found then perform game.err('PROPERTY_NOT_FOUND', 'Imóvel não encontrado'); end if;
  if p_eligibility = 'eligible' and (p.category_id is null or p.classification_status <> 'resolved') then
    perform game.err('VALIDATION_FAILED', 'Classifique o imóvel antes de liberar');
  end if;
  update game.properties
     set eligibility = p_eligibility,
         eligibility_reason = case when p_eligibility = 'eligible' then null else 'admin: ' || p_reason end,
         version = version + 1
   where id = p_property;
  perform game.audit(p_admin, 'property.eligibility', 'property', p_property::text, p_reason,
                     jsonb_build_object('eligibility', p.eligibility, 'reason', p.eligibility_reason),
                     jsonb_build_object('eligibility', p_eligibility), v_op.op_id);
  v_res := jsonb_build_object('property_id', p_property, 'eligibility', p_eligibility, 'version', p.version + 1);
  return game.op_succeed(v_op.op_id, v_res);
end $$;

create or replace function admin_api.set_property_classification(p_admin uuid, p_key text, p_hash text, p_property uuid,
                                                                 p_category_code text, p_subtype text, p_reason text)
returns jsonb language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare v_op record; p game.properties; v_cat smallint;
begin
  perform game.require_admin(p_admin, array['geo']);
  select * into v_op from game.admin_op(p_admin, p_key, 'property.classification', p_hash);
  if v_op.replay is not null then return v_op.replay; end if;
  select id into v_cat from game.categories where code = p_category_code;
  if v_cat is null then perform game.err('VALIDATION_FAILED', 'Categoria inválida'); end if;
  select * into p from game.properties where id = p_property for update;
  if not found then perform game.err('PROPERTY_NOT_FOUND', 'Imóvel não encontrado'); end if;
  if p.current_ownership_id is not null and p.category_id is distinct from v_cat then
    perform game.err('VALIDATION_FAILED', 'Imóvel com dono: a classe econômica não é alterada');
  end if;
  update game.properties
     set category_id = v_cat, subtype = p_subtype, classification_status = 'resolved', classification_confidence = 1,
         visual_model = (select visual_key from game.categories where id = v_cat), version = version + 1
   where id = p_property;
  perform game.audit(p_admin, 'property.classification', 'property', p_property::text, p_reason,
                     jsonb_build_object('category_id', p.category_id, 'classification_status', p.classification_status),
                     jsonb_build_object('category', p_category_code, 'subtype', p_subtype), v_op.op_id);
  return game.op_succeed(v_op.op_id, jsonb_build_object('property_id', p_property, 'category', p_category_code));
end $$;

-- Ajuste compensatório: único meio de corrigir saldo. Liga-se ao evento original.
create or replace function admin_api.compensating_adjustment(p_admin uuid, p_key text, p_hash text, p_user uuid,
                                                             p_amount numeric, p_reason text, p_reference_tx uuid)
returns jsonb language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare v_op record; v_tx uuid; v_before jsonb;
begin
  perform game.require_admin(p_admin, array['finance']);
  select * into v_op from game.admin_op(p_admin, p_key, 'wallet.adjustment', p_hash);
  if v_op.replay is not null then return v_op.replay; end if;
  if p_amount is null or p_amount = 0 or p_amount <> round(p_amount, 8) then perform game.err('INVALID_AMOUNT', 'Valor inválido'); end if;
  if p_reference_tx is not null and not exists (select 1 from game.ledger_transactions where id = p_reference_tx) then
    perform game.err('VALIDATION_FAILED', 'Transação de referência inexistente');
  end if;
  perform game.lock_wallets(array[p_user]);
  v_before := game.wallet_json(p_user);
  if v_before is null then perform game.err('WALLET_NOT_FOUND', 'Carteira não encontrada'); end if;
  v_tx := game.ledger_tx('adjustment', v_op.op_id, jsonb_build_object('reason', p_reason, 'admin_id', p_admin, 'reference_transaction_id', p_reference_tx));
  perform game.ledger_post(v_tx, game.acct_adjustments(), -p_amount, 'adjustment');
  perform game.ledger_post(v_tx, game.account_of(p_user), p_amount, 'adjustment');
  -- Ajuste não respeita bloqueio (é a correção), mas nunca deixa saldo abaixo das reservas.
  update game.wallets set balance = balance + p_amount, version = version + 1, updated_at = clock_timestamp() where user_id = p_user;
  if exists (select 1 from game.wallets where user_id = p_user and balance < reserved) then
    perform game.err('INSUFFICIENT_FUNDS', 'Ajuste deixaria saldo abaixo das reservas ativas');
  end if;
  perform game.audit(p_admin, 'wallet.adjustment', 'wallet', p_user::text, p_reason, v_before, game.wallet_json(p_user), v_op.op_id);
  perform game.notify(p_user, 'wallet.adjustment', v_tx::text, jsonb_build_object('amount', game.money_text(p_amount)), true);
  return game.op_succeed(v_op.op_id, jsonb_build_object('ledger_transaction_id', v_tx, 'wallet', game.wallet_json(p_user)));
end $$;

create or replace function admin_api.unfreeze_wallet(p_admin uuid, p_key text, p_hash text, p_user uuid, p_reason text)
returns jsonb language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare v_op record; w game.wallets; v_led numeric; v_hold numeric;
begin
  perform game.require_admin(p_admin, array['finance']);
  select * into v_op from game.admin_op(p_admin, p_key, 'wallet.unfreeze', p_hash);
  if v_op.replay is not null then return v_op.replay; end if;
  select * into w from game.wallets where user_id = p_user for update;
  if not found then perform game.err('WALLET_NOT_FOUND', 'Carteira não encontrada'); end if;
  select coalesce(sum(amount), 0) into v_led from game.ledger_entries where account_id = w.account_id;
  select coalesce(sum(amount), 0) into v_hold from game.wallet_holds where user_id = p_user and status = 'active';
  if v_led <> w.balance or v_hold <> w.reserved then
    perform game.err('RECONCILIATION_PENDING', 'Carteira ainda diverge do ledger; faça ajuste compensatório antes',
                     jsonb_build_object('balance', w.balance, 'ledger', v_led, 'reserved', w.reserved, 'holds', v_hold));
  end if;
  update game.wallets set frozen = false, frozen_reason = null where user_id = p_user;
  update game.reconciliation_alerts set status = 'resolved', resolved_by = p_admin, resolved_at = now(), note = p_reason
   where user_id = p_user and kind = 'wallet_mismatch' and status <> 'resolved';
  perform game.audit(p_admin, 'wallet.unfreeze', 'wallet', p_user::text, p_reason, jsonb_build_object('frozen', true), jsonb_build_object('frozen', false), v_op.op_id);
  return game.op_succeed(v_op.op_id, jsonb_build_object('wallet', game.wallet_json(p_user)));
end $$;

create or replace function admin_api.resolve_alert(p_admin uuid, p_alert uuid, p_status text, p_note text) returns jsonb
language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare a game.reconciliation_alerts;
begin
  perform game.require_admin(p_admin, array['finance']);
  if p_status not in ('acknowledged', 'resolved') then perform game.err('VALIDATION_FAILED', 'Status inválido'); end if;
  select * into a from game.reconciliation_alerts where id = p_alert for update;
  if not found then perform game.err('NOT_FOUND', 'Alerta não encontrado'); end if;
  update game.reconciliation_alerts set status = p_status, resolved_by = p_admin,
         resolved_at = case when p_status = 'resolved' then now() end, note = p_note where id = p_alert;
  perform game.audit(p_admin, 'alert.' || p_status, 'reconciliation_alert', p_alert::text, p_note, to_jsonb(a), jsonb_build_object('status', p_status));
  return jsonb_build_object('alert_id', p_alert, 'status', p_status);
end $$;

-- Cancelamento administrativo de proposta pendente (libera reserva).
create or replace function admin_api.cancel_proposal(p_admin uuid, p_key text, p_hash text, p_proposal uuid, p_reason text)
returns jsonb language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare v_op record; v_p game.proposals;
begin
  perform game.require_admin(p_admin, array['support','finance']);
  select * into v_op from game.admin_op(p_admin, p_key, 'proposal.cancel', p_hash);
  if v_op.replay is not null then return v_op.replay; end if;
  select * into v_p from game.proposals where id = p_proposal for update;
  if not found then perform game.err('PROPOSAL_NOT_FOUND', 'Proposta não encontrada'); end if;
  if v_p.status not in ('pending', 'expired') then perform game.err('PROPOSAL_NOT_PENDING', 'Proposta já encerrada'); end if;
  perform game.lock_wallets(array[v_p.buyer_id]);
  update game.proposals
     set status = case when v_p.status = 'pending' then 'cancelled' else 'superseded' end,
         closed_at = coalesce(closed_at, clock_timestamp()), close_reason = 'admin', version = version + 1
   where id = p_proposal;
  perform game.hold_release(p_proposal, 'admin');
  perform game.audit(p_admin, 'proposal.cancel', 'proposal', p_proposal::text, p_reason,
                     jsonb_build_object('status', v_p.status), jsonb_build_object('status', 'cancelled'), v_op.op_id);
  perform game.notify(v_p.buyer_id, 'proposal.cancelled_by_support', p_proposal::text, jsonb_build_object('proposal_id', p_proposal), true);
  return game.op_succeed(v_op.op_id, jsonb_build_object('proposal_id', p_proposal));
end $$;

create or replace function admin_api.decide_risk_review(p_admin uuid, p_review uuid, p_decision text, p_note text) returns jsonb
language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare r game.risk_reviews;
begin
  perform game.require_admin(p_admin, array['finance','support']);
  if p_decision not in ('approved', 'rejected') then perform game.err('VALIDATION_FAILED', 'Decisão inválida'); end if;
  select * into r from game.risk_reviews where id = p_review for update;
  if not found or r.status <> 'pending' then perform game.err('NOT_FOUND', 'Revisão pendente não encontrada'); end if;
  update game.risk_reviews set status = p_decision, decided_by = p_admin, decided_at = now(), note = p_note where id = p_review;
  perform game.audit(p_admin, 'risk.' || p_decision, 'risk_review', p_review::text, p_note, to_jsonb(r), jsonb_build_object('status', p_decision));
  perform game.notify(r.buyer_id, 'risk.review_decided', p_review::text, jsonb_build_object('decision', p_decision, 'property_id', r.property_id), true);
  return jsonb_build_object('review_id', p_review, 'status', p_decision);
end $$;

-- Encerramento compensado de titularidade (imóvel inacessível/removido): liquida renda,
-- credita compensação explícita e devolve o imóvel ao sistema. Nunca some com o ativo.
create or replace function game.admin_compensate_close(p_admin uuid, p_op uuid, p_property uuid, p_amount numeric, p_reason text)
returns jsonb language plpgsql set search_path = pg_catalog, pg_temp as $$
declare v_prop game.properties; v_own game.ownerships; v_users uuid[]; v_now timestamptz; v_tx uuid; v_c numeric; r record; v_trade uuid;
begin
  select * into v_prop from game.properties where id = p_property for update;
  if v_prop.current_ownership_id is null then perform game.err('PROPERTY_CHANGED', 'Imóvel sem dono'); end if;
  select * into v_own from game.ownerships where id = v_prop.current_ownership_id;
  v_users := game.transfer_lock_set(v_own.id, null);
  perform game.lock_wallets(v_users || v_own.owner_id);
  v_now := clock_timestamp();
  v_tx := game.ledger_tx('compensation', p_op, jsonb_build_object('property_id', p_property, 'reason', p_reason, 'admin_id', p_admin));
  select s.credited into v_c from game.settle_income(v_own.id, v_now, true, v_tx, 'compensation', p_op) s;
  if p_amount > 0 then
    perform game.ledger_post(v_tx, game.acct_adjustments(), -p_amount, 'adjustment', p_property, v_own.id);
    perform game.ledger_post(v_tx, game.account_of(v_own.owner_id), p_amount, 'adjustment', p_property, v_own.id);
    perform game.wallet_apply(v_own.owner_id, p_amount, 0);
  end if;
  update game.ownerships set ended_at = v_now, end_reason = 'compensation' where id = v_own.id;
  update game.properties set current_ownership_id = null, version = version + 1 where id = p_property;
  for r in select id, status, buyer_id from game.proposals where ownership_id = v_own.id and status in ('pending','expired') order by id loop
    update game.proposals set status = 'superseded', closed_at = coalesce(closed_at, v_now), close_reason = 'compensation', version = version + 1 where id = r.id;
    if r.status = 'pending' then perform game.hold_release(r.id, 'superseded'); end if;
  end loop;
  insert into game.trades (property_id, old_ownership_id, seller_id, price, type, ledger_transaction_id, operation_id, occurred_at)
  values (p_property, v_own.id, v_own.owner_id, 0, 'returned_to_system', v_tx, p_op, v_now) returning id into v_trade;
  perform game.notify(v_own.owner_id, 'property.compensated', v_trade::text,
                      jsonb_build_object('property_id', p_property, 'compensation', game.money_text(p_amount), 'income_settled', game.money_text(v_c)), true);
  return jsonb_build_object('trade_id', v_trade, 'compensation', game.money_text(p_amount), 'income_settled', game.money_text(v_c));
end $$;

create or replace function admin_api.resolve_review_case(p_admin uuid, p_key text, p_hash text, p_case uuid, p_action text,
                                                         p_note text, p_amount numeric default null)
returns jsonb language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare v_op record; c geo.review_cases; v_res jsonb := '{}'::jsonb; p game.properties;
begin
  perform game.require_admin(p_admin, case when p_action = 'compensate_and_close' then array['finance'] else array['geo'] end);
  select * into v_op from game.admin_op(p_admin, p_key, 'review.' || coalesce(p_action, ''), p_hash);
  if v_op.replay is not null then return v_op.replay; end if;
  if p_action not in ('dismiss','restore_eligibility','keep_suspended','verify_entrance','compensate_and_close') then
    perform game.err('VALIDATION_FAILED', 'Ação inválida');
  end if;
  select * into c from geo.review_cases where id = p_case for update;
  if not found or c.status <> 'open' then perform game.err('NOT_FOUND', 'Caso aberto não encontrado'); end if;
  if c.property_id is not null then select * into p from game.properties where id = c.property_id for update; end if;

  if p_action = 'restore_eligibility' then
    if p.category_id is null or p.classification_status <> 'resolved' then
      perform game.err('VALIDATION_FAILED', 'Classifique o imóvel antes de liberar');
    end if;
    update game.properties set eligibility = 'eligible', eligibility_reason = null, version = version + 1 where id = p.id;
  elsif p_action = 'verify_entrance' then
    update game.properties set interaction_quality = 'entrance_verified',
           eligibility = case when category_id is not null and classification_status = 'resolved' and eligibility = 'under_review' then 'eligible' else eligibility end,
           version = version + 1 where id = p.id;
  elsif p_action = 'compensate_and_close' then
    if p_amount is null or p_amount < 0 or p_amount <> round(p_amount, 2) then perform game.err('INVALID_AMOUNT', 'Compensação inválida'); end if;
    v_res := game.admin_compensate_close(p_admin, v_op.op_id, c.property_id, p_amount, p_note);
  end if;

  update geo.review_cases set status = case when p_action = 'dismiss' then 'dismissed' else 'resolved' end,
         resolution = jsonb_build_object('action', p_action, 'note', p_note, 'amount', p_amount) || v_res,
         resolved_at = now(), resolved_by = p_admin
   where id = p_case;
  perform game.audit(p_admin, 'review.' || p_action, 'review_case', p_case::text, p_note, to_jsonb(c), v_res, v_op.op_id);
  return game.op_succeed(v_op.op_id, jsonb_build_object('case_id', p_case, 'action', p_action) || v_res);
end $$;

create or replace function admin_api.approve_import_batch(p_admin uuid, p_batch uuid, p_note text) returns jsonb
language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare v jsonb;
begin
  perform game.require_admin(p_admin, array['geo']);
  v := geo_api.approve_batch(p_batch, p_admin, p_note);
  perform game.audit(p_admin, 'import.approve', 'import_batch', p_batch::text, p_note, null, v);
  return v;
end $$;

create or replace function admin_api.reject_import_batch(p_admin uuid, p_batch uuid, p_note text) returns jsonb
language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare v jsonb;
begin
  perform game.require_admin(p_admin, array['geo']);
  v := geo_api.reject_batch(p_batch, p_admin, p_note);
  perform game.audit(p_admin, 'import.reject', 'import_batch', p_batch::text, p_note, null, v);
  return v;
end $$;

create or replace function admin_api.publish_import_batch(p_admin uuid, p_batch uuid, p_note text) returns jsonb
language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare v jsonb;
begin
  perform game.require_admin(p_admin, array['geo']);
  v := geo_api.publish_batch(p_batch);
  perform game.audit(p_admin, 'import.publish', 'import_batch', p_batch::text, coalesce(p_note, 'publicação do lote'), null, v);
  return v;
end $$;

create or replace function admin_api.set_region_release(p_admin uuid, p_region uuid, p_status text, p_reason text) returns jsonb
language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare r game.regions;
begin
  perform game.require_admin(p_admin, array['geo']);
  if p_status not in ('draft','qa','released','paused') then perform game.err('VALIDATION_FAILED', 'Status inválido'); end if;
  select * into r from game.regions where id = p_region for update;
  if not found then perform game.err('REGION_NOT_FOUND', 'Região não encontrada'); end if;
  if p_status = 'released' and r.active_dataset_version is null then
    perform game.err('VALIDATION_FAILED', 'Publique um lote importado antes de liberar a região');
  end if;
  update game.regions set release_status = p_status where id = p_region;
  perform game.audit(p_admin, 'region.release', 'region', p_region::text, p_reason,
                     jsonb_build_object('release_status', r.release_status), jsonb_build_object('release_status', p_status));
  return jsonb_build_object('region_id', p_region, 'release_status', p_status);
end $$;

-- Nova versão imutável de configuração (patch sobre a vigente). Não reescreve propostas antigas.
create or replace function admin_api.publish_config(p_admin uuid, p_patch jsonb, p_effective_at timestamptz, p_note text) returns jsonb
language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare v_cur jsonb := game.cfg(); v_new jsonb; v_version int; k text;
begin
  perform game.require_admin(p_admin, array['superadmin']);
  if p_patch is null or jsonb_typeof(p_patch) <> 'object' then perform game.err('VALIDATION_FAILED', 'Patch inválido'); end if;
  for k in select jsonb_object_keys(p_patch) loop
    if not (v_cur ? k) or k = '_version' then perform game.err('VALIDATION_FAILED', 'Chave desconhecida: ' || k); end if;
    if jsonb_typeof(p_patch->k) <> jsonb_typeof(v_cur->k) then perform game.err('VALIDATION_FAILED', 'Tipo inválido para ' || k); end if;
  end loop;
  v_new := (v_cur - '_version') || p_patch;
  select coalesce(max(version), 0) + 1 into v_version from game.game_config;
  insert into game.game_config (version, values, effective_at, note, created_by)
  values (v_version, v_new, coalesce(p_effective_at, now()), p_note, p_admin);
  perform game.audit(p_admin, 'config.publish', 'game_config', v_version::text, coalesce(p_note, 'nova versão'), v_cur, v_new);
  return jsonb_build_object('version', v_version, 'effective_at', coalesce(p_effective_at, now()), 'values', v_new);
end $$;

create or replace function admin_api.update_report(p_admin uuid, p_report bigint, p_status text, p_resolution text) returns jsonb
language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare r game.reports;
begin
  perform game.require_admin(p_admin, array['support','geo']);
  if p_status not in ('open','in_progress','resolved','rejected') then perform game.err('VALIDATION_FAILED', 'Status inválido'); end if;
  select * into r from game.reports where id = p_report for update;
  if not found then perform game.err('NOT_FOUND', 'Chamado não encontrado'); end if;
  update game.reports set status = p_status, resolution = p_resolution where id = p_report;
  perform game.audit(p_admin, 'report.' || p_status, 'report', p_report::text, coalesce(p_resolution, p_status),
                     jsonb_build_object('status', r.status), jsonb_build_object('status', p_status));
  perform game.notify(r.reporter_id, 'report.updated', p_report::text || ':' || p_status,
                      jsonb_build_object('protocol', 'DV-' || lpad(p_report::text, 6, '0'), 'status', p_status), true);
  return jsonb_build_object('report_id', p_report, 'status', p_status);
end $$;

create or replace function admin_api.set_user_status(p_admin uuid, p_user uuid, p_status text, p_reason text) returns jsonb
language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare v game.profiles;
begin
  perform game.require_admin(p_admin, array['support']);
  if p_status not in ('active', 'suspended') then perform game.err('VALIDATION_FAILED', 'Status inválido'); end if;
  select * into v from game.profiles where id = p_user for update;
  if not found or v.status in ('deletion_pending', 'deleted') then perform game.err('NOT_FOUND', 'Usuário não encontrado ou em exclusão'); end if;
  update game.profiles set status = p_status where id = p_user;
  perform game.audit(p_admin, 'user.' || p_status, 'profile', p_user::text, p_reason,
                     jsonb_build_object('status', v.status), jsonb_build_object('status', p_status));
  return jsonb_build_object('user_id', p_user, 'status', p_status);
end $$;

create or replace function admin_api.set_role(p_admin uuid, p_user uuid, p_role text, p_grant boolean, p_reason text) returns jsonb
language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
begin
  perform game.require_admin(p_admin, array['superadmin']);
  if p_role not in ('support','geo','finance','superadmin') then perform game.err('VALIDATION_FAILED', 'Papel inválido'); end if;
  if p_grant then
    insert into game.admin_roles (user_id, role, granted_by) values (p_user, p_role, p_admin)
    on conflict (user_id, role) do update set revoked_at = null, granted_by = p_admin, granted_at = now();
  else
    if p_user = p_admin and p_role = 'superadmin' then perform game.err('VALIDATION_FAILED', 'Não remova o próprio superadmin'); end if;
    update game.admin_roles set revoked_at = now() where user_id = p_user and role = p_role;
  end if;
  perform game.audit(p_admin, case when p_grant then 'role.grant' else 'role.revoke' end, 'profile', p_user::text, p_reason,
                     null, jsonb_build_object('role', p_role));
  return jsonb_build_object('user_id', p_user, 'role', p_role, 'granted', p_grant);
end $$;

create or replace function admin_api.run_reconciliation(p_admin uuid) returns jsonb
language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare v jsonb;
begin
  perform game.require_admin(p_admin, array['finance']);
  v := game_internal.reconcile('admin:' || p_admin::text);
  perform game.audit(p_admin, 'reconcile.run', 'reconciliation', v->>'run_id', 'execução sob demanda', null, v);
  return v;
end $$;

create or replace function admin_api.config_versions(p_admin uuid) returns jsonb
language plpgsql stable security definer set search_path = pg_catalog, pg_temp as $$
begin
  perform game.require_admin(p_admin, array['superadmin','finance']);
  return jsonb_build_object(
    'current', game.cfg(),
    'versions', coalesce((select jsonb_agg(jsonb_build_object('version', c.version, 'effective_at', c.effective_at, 'note', c.note,
                                                              'created_by', c.created_by, 'created_at', c.created_at, 'values', c.values)
                                           order by c.version desc)
                            from (select * from game.game_config order by version desc limit 20) c), '[]'::jsonb));
end $$;
