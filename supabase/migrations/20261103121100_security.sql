-- Dono da Vez — 0012: posse das funções, grants mínimos e RLS.
-- RLS não é a única defesa: os papéis da Data API (anon/authenticated/service_role)
-- não recebem USAGE nos schemas do jogo nem EXECUTE nas funções mutadoras.

-- O disparo da outbox usa Vault/pg_net: roda como o papel do cron (invoker), não como dv_engine.
alter function game_internal.trigger_outbox_worker() security invoker;

-- O gatilho diferido de balanço roda no COMMIT (fora da função definer): precisa ser definer.
alter function game.check_ledger_balanced() security definer;

-- ---------------------------------------------------------------------------
-- 1. Posse das funções SECURITY DEFINER
-- ---------------------------------------------------------------------------
grant create on schema game_api, game_internal, admin_api, game to dv_engine;
grant create on schema geo_api to dv_geo_publisher;

do $$
declare f record;
begin
  for f in select p.oid::regprocedure as sig
             from pg_proc p join pg_namespace n on n.oid = p.pronamespace
            where n.nspname in ('game_api', 'game_internal', 'admin_api')
              and p.proname <> 'trigger_outbox_worker' loop
    execute format('alter function %s owner to dv_engine', f.sig);
  end loop;
  for f in select p.oid::regprocedure as sig
             from pg_proc p join pg_namespace n on n.oid = p.pronamespace
            where n.nspname = 'geo_api' loop
    execute format('alter function %s owner to dv_geo_publisher', f.sig);
  end loop;
end $$;
alter function game.on_auth_user_change() owner to dv_engine;
alter function game.check_ledger_balanced() owner to dv_engine;

revoke create on schema game_api, game_internal, admin_api, game from dv_engine;
revoke create on schema geo_api from dv_geo_publisher;

-- ---------------------------------------------------------------------------
-- 2. Nada para PUBLIC / papéis da Data API
-- ---------------------------------------------------------------------------
revoke execute on all functions in schema game, game_api, game_internal, admin_api, geo, geo_api from public;
revoke all on all tables in schema game, geo from public;

do $$
declare r text;
begin
  foreach r in array array['anon', 'authenticated', 'service_role'] loop
    if exists (select 1 from pg_roles where rolname = r) then
      execute format('revoke all on schema game, game_api, game_internal, admin_api, geo, geo_api from %I', r);
      execute format('revoke all on all tables in schema game, geo from %I', r);
      execute format('revoke all on all sequences in schema game, geo from %I', r);
      execute format('revoke all on all functions in schema game, game_api, game_internal, admin_api, geo, geo_api from %I', r);
    end if;
  end loop;
end $$;

-- ---------------------------------------------------------------------------
-- 3. dv_engine (dono das regras econômicas)
-- ---------------------------------------------------------------------------
grant usage on schema game, geo, game_internal, geo_api to dv_engine;
grant select, insert, update on all tables in schema game to dv_engine;
grant delete on game.presence_proofs, game.presence_rejections, game.rate_limits, game.device_tokens to dv_engine;
-- Imutáveis: somente inserção
revoke update on game.ledger_transactions, game.ledger_entries, game.trades, game.admin_audit, game.game_config from dv_engine;
grant usage, select on all sequences in schema game, geo to dv_engine;
grant execute on all functions in schema game to dv_engine;
grant select on all tables in schema geo to dv_engine;
grant insert, update on geo.review_cases to dv_engine;
grant execute on function geo_api.approve_batch(uuid, uuid, text), geo_api.reject_batch(uuid, uuid, text),
                         geo_api.publish_batch(uuid) to dv_engine;
grant execute on all functions in schema game_internal to dv_engine;

-- ---------------------------------------------------------------------------
-- 4. dv_geo_publisher (somente colunas geográficas; nunca carteiras/titularidades)
-- ---------------------------------------------------------------------------
-- geo_api: as funções do próprio schema chamam umas às outras (ex.: upsert_region → get_region).
grant usage on schema game, geo, geo_api to dv_geo_publisher;
grant select on game.properties, game.regions, game.categories, game.game_config to dv_geo_publisher;
grant insert (region_id, category_id, subtype, classification_status, classification_confidence,
              classification_rules_version, address_label, point, footprint, interaction_point,
              interaction_quality, height_m, height_source, visual_model, units_count,
              source_tags_summary, eligibility, eligibility_reason, dataset_batch_id)
  on game.properties to dv_geo_publisher;
grant update (category_id, subtype, classification_status, classification_confidence,
              classification_rules_version, address_label, point, footprint, interaction_point,
              interaction_quality, height_m, height_source, visual_model, units_count,
              source_tags_summary, eligibility, eligibility_reason, version, dataset_batch_id,
              geo_updated_at, updated_at)
  on game.properties to dv_geo_publisher;
grant insert (code, name, polygon) on game.regions to dv_geo_publisher;
grant update (name, polygon, import_status, active_dataset_version, updated_at) on game.regions to dv_geo_publisher;
grant select, insert, update on all tables in schema geo to dv_geo_publisher;
grant delete on geo.staging_properties, geo.staging_rejects to dv_geo_publisher;
grant usage, select on all sequences in schema geo to dv_geo_publisher;
grant execute on function game.cfg(), game.err(text, text, jsonb), geo.batch_matches(uuid) to dv_geo_publisher;

-- ---------------------------------------------------------------------------
-- 5. Papéis de login (credenciais definidas por ambiente, fora das migrações)
-- ---------------------------------------------------------------------------
grant usage on schema game_api to dv_backend;
grant execute on all functions in schema game_api to dv_backend;

grant usage on schema admin_api to dv_admin_backend;
grant execute on all functions in schema admin_api to dv_admin_backend;

grant usage on schema geo, geo_api, extensions to dv_importer;
grant select, insert, delete on geo.staging_properties, geo.staging_rejects to dv_importer;
grant select on geo.import_batches to dv_importer;
grant usage, select on all sequences in schema geo to dv_importer;
grant execute on function geo_api.get_region(text), geo_api.upsert_region(text, text, jsonb),
                         geo_api.begin_batch(text, text, text, text, text, integer),
                         geo_api.complete_staging(uuid, jsonb, jsonb), geo_api.fail_batch(uuid, jsonb),
                         geo_api.validate_batch(uuid), geo_api.publish_batch(uuid) to dv_importer;

-- Jobs do pg_cron rodam como o papel de migração.
do $$
begin
  execute format('grant usage on schema game_internal to %I', current_user);
  execute format('grant execute on all functions in schema game_internal to %I', current_user);
end $$;

-- ---------------------------------------------------------------------------
-- 6. RLS em todas as tabelas (negação por padrão; políticas por papel interno)
-- ---------------------------------------------------------------------------
do $$
declare t record;
begin
  for t in select schemaname, tablename from pg_tables where schemaname in ('game', 'geo') loop
    execute format('alter table %I.%I enable row level security', t.schemaname, t.tablename);
    execute format('drop policy if exists dv_engine_all on %I.%I', t.schemaname, t.tablename);
    if t.schemaname = 'game' then
      execute format('create policy dv_engine_all on %I.%I for all to dv_engine using (true) with check (true)', t.schemaname, t.tablename);
    else
      execute format('create policy dv_engine_all on %I.%I for select to dv_engine using (true)', t.schemaname, t.tablename);
      execute format('drop policy if exists dv_geo_publisher_all on %I.%I', t.schemaname, t.tablename);
      execute format('create policy dv_geo_publisher_all on %I.%I for all to dv_geo_publisher using (true) with check (true)', t.schemaname, t.tablename);
    end if;
  end loop;
end $$;

create policy dv_engine_review_write on geo.review_cases for insert to dv_engine with check (true);
create policy dv_engine_review_update on geo.review_cases for update to dv_engine using (true) with check (true);

create policy dv_geo_publisher_properties on game.properties for all to dv_geo_publisher using (true) with check (true);
create policy dv_geo_publisher_regions on game.regions for all to dv_geo_publisher using (true) with check (true);
create policy dv_geo_publisher_categories on game.categories for select to dv_geo_publisher using (true);
create policy dv_geo_publisher_config on game.game_config for select to dv_geo_publisher using (true);

create policy dv_importer_staging on geo.staging_properties for all to dv_importer using (true) with check (true);
create policy dv_importer_rejects on geo.staging_rejects for all to dv_importer using (true) with check (true);
create policy dv_importer_batches on geo.import_batches for select to dv_importer using (true);
