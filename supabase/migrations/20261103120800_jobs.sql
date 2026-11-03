-- Dono da Vez — 0009: jobs agendados (lotes pequenos, SKIP LOCKED, idempotentes).
-- Não existe job que credite 1% ao dia: a renda é calculada sob demanda.

-- Expira propostas vencidas e libera reservas. Ordem: propostas (ID) → carteiras (UUID).
create or replace function game_internal.expire_due_proposals(p_limit integer default 200) returns integer
language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare v_ids uuid[]; v_buyers uuid[]; v_now timestamptz; r record; n int := 0;
begin
  select coalesce(array_agg(s.id order by s.id), '{}'), coalesce(array_agg(distinct s.buyer_id), '{}')
    into v_ids, v_buyers
    from (select id, buyer_id from game.proposals
           where status = 'pending' and expires_at <= clock_timestamp()
           order by id limit p_limit
           for update skip locked) s;
  if cardinality(v_ids) = 0 then return 0; end if;
  perform game.lock_wallets(v_buyers);
  v_now := clock_timestamp();
  for r in select id from game.proposals where id = any(v_ids) order by id loop
    if game.expire_proposal(r.id, v_now) then n := n + 1; end if;
  end loop;
  return n;
end $$;

-- Aviso 2h antes do vencimento (aviso inicial é criado junto com a proposta).
create or replace function game_internal.send_expiry_reminders() returns integer
language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare r record; n int := 0; v_before int := coalesce((game.cfg()->>'reminder_before_expiry_s')::int, 7200);
begin
  for r in select id, seller_id, property_id, amount, expires_at from game.proposals
            where status = 'pending' and expires_at > now() and expires_at <= now() + make_interval(secs => v_before)
            order by expires_at limit 500 loop
    perform game.notify(r.seller_id, 'proposal.expiring', r.id::text,
      jsonb_build_object('proposal_id', r.id, 'property_id', r.property_id,
                         'amount', game.money_text(r.amount), 'expires_at', r.expires_at));
    n := n + 1;
  end loop;
  return n;
end $$;

-- Dispara a Edge Function outbox-worker via pg_net quando há eventos pendentes.
-- URL e segredo vêm do Vault: dv_outbox_worker_url, dv_cron_secret.
create or replace function game_internal.trigger_outbox_worker() returns boolean
language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare v_url text; v_secret text;
begin
  if not exists (select 1 from game.outbox
                  where (status in ('pending','failed') and next_attempt_at <= now())
                     or (status = 'processing' and locked_until < now())) then
    return false;
  end if;
  if to_regclass('vault.decrypted_secrets') is null or to_regnamespace('net') is null then
    return false;
  end if;
  execute $q$select decrypted_secret from vault.decrypted_secrets where name = 'dv_outbox_worker_url'$q$ into v_url;
  execute $q$select decrypted_secret from vault.decrypted_secrets where name = 'dv_cron_secret'$q$ into v_secret;
  if v_url is null or v_secret is null then return false; end if;
  execute $q$select net.http_post(url := $1, body := '{}'::jsonb,
                                  headers := jsonb_build_object('content-type', 'application/json', 'x-cron-secret', $2),
                                  timeout_milliseconds := 10000)$q$
    using v_url, v_secret;
  return true;
end $$;

-- Reconciliação: compara saldo materializado × ledger e reserva × holds ativos.
-- Somente alerta e bloqueio preventivo; reparo é operação explícita separada.
create or replace function game_internal.reconcile(p_triggered_by text default 'cron') returns jsonb
language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare v_run uuid; v_alerts int := 0; v_checked int; r record; v_total numeric;
begin
  insert into game.reconciliation_runs (triggered_by) values (left(p_triggered_by, 40)) returning id into v_run;
  select count(*) into v_checked from game.wallets;

  for r in
    with led as (select account_id, sum(amount) s from game.ledger_entries group by account_id),
         hol as (select user_id, sum(amount) s from game.wallet_holds where status = 'active' group by user_id)
    select w.user_id, w.balance, w.reserved, coalesce(led.s, 0) as ledger_balance, coalesce(hol.s, 0) as holds
      from game.wallets w
      left join led on led.account_id = w.account_id
      left join hol on hol.user_id = w.user_id
     where w.balance <> coalesce(led.s, 0) or w.reserved <> coalesce(hol.s, 0)
  loop
    insert into game.reconciliation_alerts (run_id, kind, user_id, details)
    values (v_run, 'wallet_mismatch', r.user_id, jsonb_build_object(
      'balance', r.balance, 'ledger_balance', r.ledger_balance, 'reserved', r.reserved, 'active_holds', r.holds));
    update game.wallets set frozen = true, frozen_reason = 'reconciliation_mismatch' where user_id = r.user_id;
    v_alerts := v_alerts + 1;
  end loop;

  select coalesce(sum(amount), 0) into v_total from game.ledger_entries;
  if v_total <> 0 then
    insert into game.reconciliation_alerts (run_id, kind, details) values (v_run, 'ledger_global_unbalanced', jsonb_build_object('sum', v_total));
    v_alerts := v_alerts + 1;
  end if;

  for r in select h.id, h.user_id, h.proposal_id, p.status from game.wallet_holds h join game.proposals p on p.id = h.proposal_id
            where h.status = 'active' and p.status <> 'pending' loop
    insert into game.reconciliation_alerts (run_id, kind, user_id, subject_id, details)
    values (v_run, 'orphan_hold', r.user_id, r.id::text, jsonb_build_object('proposal_id', r.proposal_id, 'proposal_status', r.status));
    v_alerts := v_alerts + 1;
  end loop;

  for r in select p.id, p.buyer_id from game.proposals p
            where p.status = 'pending'
              and not exists (select 1 from game.wallet_holds h where h.proposal_id = p.id and h.status = 'active') loop
    insert into game.reconciliation_alerts (run_id, kind, user_id, subject_id, details)
    values (v_run, 'pending_without_hold', r.buyer_id, r.id::text, '{}'::jsonb);
    v_alerts := v_alerts + 1;
  end loop;

  for r in select p.id from game.properties p
            where p.current_ownership_id is distinct from
                  (select o.id from game.ownerships o where o.property_id = p.id and o.ended_at is null) loop
    insert into game.reconciliation_alerts (run_id, kind, subject_id, details)
    values (v_run, 'ownership_pointer_mismatch', r.id::text, '{}'::jsonb);
    v_alerts := v_alerts + 1;
  end loop;

  update game.reconciliation_runs set finished_at = clock_timestamp(), wallets_checked = v_checked, alerts_opened = v_alerts
   where id = v_run;
  return jsonb_build_object('run_id', v_run, 'wallets_checked', v_checked, 'alerts_opened', v_alerts);
end $$;

-- Retenção: coordenadas de prova eliminadas após o prazo; metadados mínimos ficam.
create or replace function game_internal.purge_presence() returns jsonb
language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare v_ret interval := make_interval(secs => coalesce((game.cfg()->>'presence_retention_s')::int, 604800));
        v_purged int; v_deleted int; v_rej int; v_rl int;
begin
  update game.presence_proofs set location = null, coords_purged_at = now()
   where location is not null and recorded_at < now() - v_ret;
  get diagnostics v_purged = row_count;
  delete from game.presence_proofs where consumed_by_operation is null and recorded_at < now() - v_ret;
  get diagnostics v_deleted = row_count;
  delete from game.presence_rejections where created_at < now() - interval '30 days';
  get diagnostics v_rej = row_count;
  delete from game.rate_limits where window_start < now() - interval '1 day';
  get diagnostics v_rl = row_count;
  return jsonb_build_object('coords_purged', v_purged, 'unused_deleted', v_deleted, 'rejections_deleted', v_rej, 'rate_limit_rows_deleted', v_rl);
end $$;

-- Relatório econômico diário agregado (sem posição individual).
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

-- Exclusão de conta em lotes retomáveis: devolve imóveis ao sistema, liquida renda,
-- encerra saldo na tesouraria, anonimiza perfil e agenda remoção da credencial.
create or replace function game_internal.process_account_deletions(p_batch integer default 20) returns jsonb
language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare
  v_user uuid; v_prop_ids uuid[]; v_own_ids uuid[]; v_proposal_ids uuid[]; v_users uuid[]; v_tx uuid; v_now timestamptz;
  r record; r2 record; v_c numeric; v_bal numeric; v_auth uuid; v_done int := 0; v_closed boolean := false;
begin
  select id into v_user from game.profiles where status = 'deletion_pending'
   order by deletion_requested_at limit 1 for update skip locked;
  if v_user is null then return jsonb_build_object('processed', 0); end if;

  select coalesce(array_agg(property_id order by property_id), '{}') into v_prop_ids
    from (select property_id from game.ownerships where owner_id = v_user and ended_at is null
           order by property_id limit p_batch) s;
  if cardinality(v_prop_ids) > 0 then
    perform 1 from game.properties where id = any(v_prop_ids) order by id for update;
    select coalesce(array_agg(id), '{}') into v_own_ids from game.ownerships
     where owner_id = v_user and ended_at is null and property_id = any(v_prop_ids);
    select coalesce(array_agg(id order by id), '{}') into v_proposal_ids from game.proposals
     where ownership_id = any(v_own_ids) and status in ('pending','expired');
    perform game.lock_proposals(v_proposal_ids);
    select coalesce(array_agg(distinct buyer_id), '{}') into v_users from game.proposals
     where ownership_id = any(v_own_ids) and status = 'pending';
    perform game.lock_wallets(v_users || v_user);
    v_now := clock_timestamp();
    for r in select o.id, o.property_id from game.ownerships o
              where o.id = any(v_own_ids) order by o.property_id loop
      select s.p_tx, s.credited into v_tx, v_c from game.settle_income(r.id, v_now, true, v_tx, 'income', null) s;
      update game.ownerships set ended_at = v_now, end_reason = 'account_deleted' where id = r.id;
      update game.properties set current_ownership_id = null, version = version + 1 where id = r.property_id;
      for r2 in select id, status, buyer_id from game.proposals
                 where ownership_id = r.id and status in ('pending','expired') order by id loop
        update game.proposals set status = 'superseded', closed_at = coalesce(closed_at, v_now),
                                  close_reason = 'owner_account_deleted', version = version + 1 where id = r2.id;
        if r2.status = 'pending' then perform game.hold_release(r2.id, 'superseded'); end if;
        perform game.notify(r2.buyer_id, 'proposal.superseded', r2.id::text,
                            jsonb_build_object('proposal_id', r2.id, 'property_id', r.property_id));
      end loop;
      insert into game.trades (property_id, old_ownership_id, seller_id, price, type, occurred_at)
      values (r.property_id, r.id, v_user, 0, 'returned_to_system', v_now);
      v_done := v_done + 1;
    end loop;
  end if;

  if not exists (select 1 from game.ownerships where owner_id = v_user and ended_at is null) then
    perform game.lock_wallets(array[v_user]);
    select balance into v_bal from game.wallets where user_id = v_user;
    if v_bal > 0 then
      v_tx := game.ledger_tx('account_closure', null, jsonb_build_object('user_id', v_user));
      perform game.ledger_post(v_tx, game.account_of(v_user), -v_bal, 'closure');
      perform game.ledger_post(v_tx, game.acct_treasury(), v_bal, 'closure');
      update game.wallets set balance = balance - v_bal, version = version + 1, updated_at = clock_timestamp()
       where user_id = v_user;
    end if;
    select auth_user_id into v_auth from game.profiles where id = v_user;
    update game.profiles
       set status = 'deleted', deleted_at = clock_timestamp(),
           nickname = 'removido_' || substr(md5(id::text), 1, 8), avatar_id = 'avatar_00',
           email_sha256 = null, notification_prefs = '{"proposals": false, "transfers": false, "reminders": false}'::jsonb
     where id = v_user;
    delete from game.device_tokens where user_id = v_user;
    if v_auth is not null then
      insert into game.outbox (topic, user_id, payload) values ('auth.delete_user', v_user, jsonb_build_object('auth_user_id', v_auth));
    end if;
    v_closed := true;
  end if;
  return jsonb_build_object('processed', v_done, 'user_id', v_user, 'closed', v_closed);
end $$;

-- ---------------------------------------------------------------------------
-- Agenda (pg_cron). Ignorado em Postgres sem a extensão.
-- ---------------------------------------------------------------------------
do $$
begin
  if exists (select 1 from pg_extension where extname = 'pg_cron') then
    perform cron.schedule('dv-expire-proposals',   '* * * * *',   'select game_internal.expire_due_proposals(200)');
    perform cron.schedule('dv-expiry-reminders',   '*/5 * * * *', 'select game_internal.send_expiry_reminders()');
    perform cron.schedule('dv-outbox-dispatch',    '* * * * *',   'select game_internal.trigger_outbox_worker()');
    perform cron.schedule('dv-account-deletions',  '*/2 * * * *', 'select game_internal.process_account_deletions(20)');
    perform cron.schedule('dv-reconcile',          '17 3 * * *',  $c$select game_internal.reconcile('cron')$c$);
    perform cron.schedule('dv-presence-cleanup',   '41 3 * * *',  'select game_internal.purge_presence()');
    perform cron.schedule('dv-economy-report',     '5 0 * * *',   'select game_internal.economy_daily_report()');
  end if;
end $$;
