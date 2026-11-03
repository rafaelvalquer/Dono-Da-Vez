-- Dono da Vez — 0008: API do jogo (SECURITY DEFINER, executável apenas por dv_backend).
-- Mutações econômicas: idempotência (game.operations) + subtransação. Erro de domínio
-- desfaz todo o trabalho e grava apenas o resultado da operação.

-- ---------------------------------------------------------------------------
-- Utilidades públicas para a Edge Function
-- ---------------------------------------------------------------------------
create or replace function game_api.health() returns jsonb
language sql stable security definer set search_path = pg_catalog, pg_temp as $$
  select jsonb_build_object('db', 'ok', 'server_time', now(), 'config_version', (game.cfg()->>'_version')::int)
$$;

create or replace function game.cursor_encode(p_ts timestamptz, p_id text) returns text
language sql stable set search_path = pg_catalog as $$
  select translate(encode(convert_to(
           to_char(p_ts at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') || '|' || p_id, 'UTF8'), 'base64'), E'\n=', '')
$$;

create or replace function game.cursor_decode(p_cursor text, out ts timestamptz, out id text)
language plpgsql stable set search_path = pg_catalog, pg_temp as $$
declare v text; v_pad text;
begin
  if p_cursor is null or p_cursor = '' then ts := null; id := null; return; end if;
  v_pad := p_cursor || repeat('=', (4 - length(p_cursor) % 4) % 4);
  v := convert_from(decode(v_pad, 'base64'), 'UTF8');
  ts := split_part(v, '|', 1)::timestamptz;
  id := split_part(v, '|', 2);
exception when others then
  perform game.err('VALIDATION_FAILED', 'Cursor inválido');
end $$;

-- Rate limit em janela fixa + registro de atividade qualificada (transação curta separada).
create or replace function game_api.preflight(p_actor uuid, p_bucket text, p_limit integer, p_window_s integer, p_qualifies boolean)
returns jsonb language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare v_start timestamptz; v_hits int; v_retry int := 0;
begin
  v_start := to_timestamp(floor(extract(epoch from now()) / p_window_s) * p_window_s);
  insert into game.rate_limits (actor_id, bucket, window_start, hits) values (p_actor, p_bucket, v_start, 1)
  on conflict (actor_id, bucket, window_start) do update set hits = game.rate_limits.hits + 1
  returning hits into v_hits;
  if v_hits > p_limit then
    v_retry := greatest(1, ceil(extract(epoch from (v_start + make_interval(secs => p_window_s) - now())))::int);
  end if;
  if p_qualifies then perform game.touch_activity(p_actor, p_bucket); end if;
  return jsonb_build_object('allowed', v_retry = 0, 'retry_after_s', v_retry);
end $$;

-- POST /activity — heartbeat em primeiro plano (limitado pelo intervalo mínimo)
create or replace function game_api.record_activity(p_actor uuid, p_source text) returns jsonb
language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare v timestamptz;
begin
  perform game.require_player(p_actor, false);
  perform game.touch_activity(p_actor, coalesce(p_source, 'heartbeat'));
  select last_qualified_activity_at into v from game.player_activity where user_id = p_actor;
  return jsonb_build_object('last_qualified_activity_at', v, 'server_time', now());
end $$;

-- ---------------------------------------------------------------------------
-- GET /bootstrap
-- ---------------------------------------------------------------------------
create or replace function game.profile_json(p_actor uuid) returns jsonb
language sql stable set search_path = pg_catalog, pg_temp as $$
  select jsonb_build_object(
    'id', p.id, 'nickname', p.nickname, 'avatar_id', p.avatar_id, 'status', p.status,
    'email_confirmed', p.email_confirmed_at is not null,
    'initial_grant_received', p.initial_grant_at is not null,
    'notification_prefs', p.notification_prefs,
    'created_at', p.created_at,
    'deletion_requested_at', p.deletion_requested_at,
    'inactivity', jsonb_build_object(
       'last_qualified_activity_at', a.last_qualified_activity_at,
       'inactive_after', a.last_qualified_activity_at + make_interval(secs => (game.cfg()->>'owner_inactivity_s')::int)))
  from game.profiles p left join game.player_activity a on a.user_id = p.id
  where p.id = p_actor
$$;

create or replace function game_api.bootstrap(p_actor uuid, p_app_version integer) returns jsonb
language plpgsql security definer set search_path = pg_catalog, extensions, pg_temp as $$
declare v_cfg jsonb := game.cfg(); v_prof game.profiles;
begin
  select * into v_prof from game.profiles where id = p_actor;
  if not found then perform game.err('PROFILE_NOT_FOUND', 'Perfil ainda não criado; tente novamente em instantes'); end if;
  perform game.grant_initial_balance(p_actor);
  return jsonb_build_object(
    'server_time', now(),
    'config', jsonb_build_object(
       'version', (v_cfg->>'_version')::int,
       'initial_balance', v_cfg->>'initial_balance',
       'daily_income_rate', v_cfg->>'daily_income_rate',
       'interaction_radius_m', (v_cfg->>'interaction_radius_m')::int,
       'max_gps_accuracy_m', (v_cfg->>'max_gps_accuracy_m')::int,
       'max_location_age_s', (v_cfg->>'max_location_age_s')::int,
       'presence_proof_ttl_s', (v_cfg->>'presence_proof_ttl_s')::int,
       'max_purchase_speed_kmh', (v_cfg->>'max_purchase_speed_kmh')::int,
       'proposal_response_window_s', (v_cfg->>'proposal_response_window_s')::int,
       'owner_inactivity_s', (v_cfg->>'owner_inactivity_s')::int,
       'forced_purchase_window_s', (v_cfg->>'forced_purchase_window_s')::int,
       'transfer_protection_s', (v_cfg->>'transfer_protection_s')::int,
       'forced_premium', v_cfg->>'forced_premium',
       'max_open_proposals_per_buyer', (v_cfg->>'max_open_proposals_per_buyer')::int,
       'allow_mock_location', coalesce((v_cfg->>'allow_mock_location')::boolean, false),
       'categories', (select jsonb_agg(jsonb_build_object(
                         'code', c.code, 'name', c.name, 'base_price', game.money_text(c.base_price),
                         'daily_income', game.money_text(c.base_price * (v_cfg->>'daily_income_rate')::numeric),
                         'visual_key', c.visual_key) order by c.id)
                        from game.categories c where c.active)),
    'min_app_version', (v_cfg->>'min_app_version')::int,
    'update_required', coalesce(p_app_version, 0) < (v_cfg->>'min_app_version')::int,
    'profile', game.profile_json(p_actor),
    'wallet', game.wallet_json(p_actor),
    'regions', (select coalesce(jsonb_agg(jsonb_build_object(
                  'id', r.id, 'code', r.code, 'name', r.name, 'attribution', r.attribution,
                  'dataset_version', r.active_dataset_version,
                  'bbox', jsonb_build_array(round(st_xmin(r.polygon)::numeric, 6), round(st_ymin(r.polygon)::numeric, 6),
                                            round(st_xmax(r.polygon)::numeric, 6), round(st_ymax(r.polygon)::numeric, 6)),
                  'center', jsonb_build_object('lat', round(st_y(st_centroid(r.polygon))::numeric, 6),
                                               'lon', round(st_x(st_centroid(r.polygon))::numeric, 6)))
                  order by r.code), '[]'::jsonb)
                from game.regions r where r.release_status = 'released'));
end $$;

-- ---------------------------------------------------------------------------
-- Perfil
-- ---------------------------------------------------------------------------
create or replace function game_api.get_profile(p_actor uuid) returns jsonb
language plpgsql stable security definer set search_path = pg_catalog, pg_temp as $$
declare v jsonb := game.profile_json(p_actor);
begin
  if v is null then perform game.err('PROFILE_NOT_FOUND', 'Perfil não encontrado'); end if;
  return jsonb_build_object('profile', v, 'server_time', now());
end $$;

create or replace function game_api.update_profile(p_actor uuid, p_nickname text, p_avatar_id text, p_prefs jsonb)
returns jsonb language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare v game.profiles;
begin
  v := game.require_player(p_actor, false);
  if p_nickname is not null then
    if p_nickname !~ '^[A-Za-z0-9_.-]{3,24}$' then
      perform game.err('VALIDATION_FAILED', 'Apelido deve ter 3 a 24 letras, números, ponto, hífen ou sublinhado');
    end if;
    if exists (select 1 from game.profiles where lower(nickname) = lower(p_nickname) and id <> p_actor) then
      perform game.err('NICKNAME_TAKEN', 'Este apelido já está em uso');
    end if;
  end if;
  if p_avatar_id is not null and p_avatar_id !~ '^avatar_[0-9]{2}$' then
    perform game.err('VALIDATION_FAILED', 'Avatar inválido');
  end if;
  if p_prefs is not null and (jsonb_typeof(p_prefs) <> 'object'
       or exists (select 1 from jsonb_each(p_prefs) e
                   where e.key not in ('proposals','transfers','reminders') or jsonb_typeof(e.value) <> 'boolean')) then
    perform game.err('VALIDATION_FAILED', 'Preferências inválidas');
  end if;
  update game.profiles
     set nickname = coalesce(p_nickname, nickname),
         avatar_id = coalesce(p_avatar_id, avatar_id),
         notification_prefs = case when p_prefs is null then notification_prefs else notification_prefs || p_prefs end
   where id = p_actor;
  return jsonb_build_object('profile', game.profile_json(p_actor));
exception when unique_violation then
  perform game.err('NICKNAME_TAKEN', 'Este apelido já está em uso');
end $$;

-- ---------------------------------------------------------------------------
-- GET /map — viewport limitado; clusters em zoom distante; geometria simplificada
-- ---------------------------------------------------------------------------
create or replace function game.viewer_state(p_actor uuid, p_property_id uuid, p_eligibility text, p_owner uuid)
returns text language sql stable set search_path = pg_catalog, pg_temp as $$
  select case
    when p_owner = p_actor then 'mine'
    when exists (select 1 from game.proposals pr
                  where pr.property_id = p_property_id and pr.buyer_id = p_actor
                    and pr.status = 'pending' and pr.expires_at > now()) then 'proposal'
    when p_eligibility <> 'eligible' then 'unavailable'
    when p_owner is not null then 'other'
    else 'available' end
$$;

create or replace function game_api.map_features(p_actor uuid, p_min_lon double precision, p_min_lat double precision,
                                                 p_max_lon double precision, p_max_lat double precision,
                                                 p_zoom numeric, p_cursor uuid default null, p_limit integer default null)
returns jsonb language plpgsql security definer set search_path = pg_catalog, extensions, pg_temp as $$
declare
  v_cfg jsonb := game.cfg(); v_env geometry; v_area numeric; v_regions jsonb; v_limit int; v_tol double precision;
  v_features jsonb; v_next uuid; v_n int; v_cell double precision;
begin
  if p_min_lon is null or p_min_lat is null or p_max_lon is null or p_max_lat is null
     or p_min_lon >= p_max_lon or p_min_lat >= p_max_lat
     or p_min_lon < -180 or p_max_lon > 180 or p_min_lat < -90 or p_max_lat > 90 then
    perform game.err('VALIDATION_FAILED', 'bbox inválido');
  end if;
  v_env := st_makeenvelope(p_min_lon, p_min_lat, p_max_lon, p_max_lat, 4326);
  v_area := (st_area(v_env::geography) / 1e6)::numeric;

  select coalesce(jsonb_agg(jsonb_build_object('id', r.id, 'code', r.code, 'name', r.name,
                                               'attribution', r.attribution, 'dataset_version', r.active_dataset_version)
                            order by r.code), '[]'::jsonb)
    into v_regions
    from game.regions r
   where r.release_status = 'released' and r.polygon && v_env and st_intersects(r.polygon, v_env);

  if jsonb_array_length(v_regions) = 0 then
    -- Interesse agregado em célula de ~1 km (sem coordenada exata).
    if p_actor is not null and exists (select 1 from game.profiles where id = p_actor) then
      insert into game.region_interest (actor_id, cell)
      values (p_actor, round(((p_min_lat + p_max_lat) / 2)::numeric, 2)::text || ',' || round(((p_min_lon + p_max_lon) / 2)::numeric, 2)::text)
      on conflict do nothing;
    end if;
    return jsonb_build_object('type', 'FeatureCollection', 'mode', 'none', 'region_status', 'unavailable',
                              'regions', '[]'::jsonb, 'features', '[]'::jsonb, 'next_cursor', null, 'server_time', now());
  end if;

  if coalesce(p_zoom, 0) < 15 then
    if v_area > (v_cfg->>'map_cluster_max_bbox_km2')::numeric then
      perform game.err('AREA_TOO_LARGE', 'Aproxime o mapa para ver os imóveis', jsonb_build_object('max_km2', v_cfg->'map_cluster_max_bbox_km2'));
    end if;
    v_cell := greatest((p_max_lon - p_min_lon) / 16, 0.0005);
    select coalesce(jsonb_agg(jsonb_build_object(
             'type', 'Feature',
             'geometry', st_asgeojson(c.center, 6)::jsonb,
             'properties', jsonb_build_object('cluster', true, 'count', c.n, 'available', c.available))), '[]'::jsonb)
      into v_features
      from (select st_centroid(st_collect(p.point)) as center, count(*) as n,
                   count(*) filter (where p.current_ownership_id is null and p.eligibility = 'eligible') as available
              from game.properties p
              join game.regions r on r.id = p.region_id and r.release_status = 'released'
             where p.point && v_env and p.eligibility <> 'removed_from_source'
             group by st_snaptogrid(p.point, v_cell)
             limit 2000) c;
    return jsonb_build_object('type', 'FeatureCollection', 'mode', 'clusters', 'region_status', 'available',
                              'regions', v_regions, 'features', v_features, 'next_cursor', null, 'server_time', now());
  end if;

  if v_area > (v_cfg->>'map_max_bbox_km2')::numeric then
    perform game.err('AREA_TOO_LARGE', 'Aproxime o mapa para ver os imóveis', jsonb_build_object('max_km2', v_cfg->'map_max_bbox_km2'));
  end if;
  v_limit := least(greatest(coalesce(p_limit, 500), 1), (v_cfg->>'map_max_features')::int);
  v_tol := case when p_zoom >= 17 then 0 when p_zoom >= 16 then 0.000005 else 0.00002 end;

  select coalesce(jsonb_agg(f.feature order by f.id), '[]'::jsonb), count(*)
    into v_features, v_n
    from (select p.id,
                 jsonb_build_object(
                   'type', 'Feature',
                   'id', p.id,
                   'geometry', st_asgeojson(coalesce(
                                 case when v_tol > 0 then st_simplifypreservetopology(p.footprint, v_tol) else p.footprint end,
                                 p.point), 6)::jsonb,
                   'properties', jsonb_build_object(
                     'id', p.id,
                     'state', game.viewer_state(p_actor, p.id, p.eligibility, o.owner_id),
                     'category', c.code,
                     'subtype', p.subtype,
                     'height_m', p.height_m,
                     'height_source', p.height_source,
                     'model', p.visual_model,
                     'version', p.version,
                     'eligibility', p.eligibility,
                     'has_footprint', p.footprint is not null,
                     'lat', round(st_y(p.point)::numeric, 6),
                     'lon', round(st_x(p.point)::numeric, 6))) as feature
            from game.properties p
            join game.regions r on r.id = p.region_id and r.release_status = 'released'
            left join game.ownerships o on o.id = p.current_ownership_id
            left join game.categories c on c.id = p.category_id
           where p.point && v_env and p.eligibility <> 'removed_from_source'
             and (p_cursor is null or p.id > p_cursor)
           order by p.id
           limit v_limit + 1) f;

  if v_n > v_limit then
    v_features := v_features - v_limit;
    v_next := (v_features -> (v_limit - 1) ->> 'id')::uuid;
  end if;
  return jsonb_build_object('type', 'FeatureCollection', 'mode', 'features', 'region_status', 'available',
                            'regions', v_regions, 'features', v_features, 'next_cursor', v_next, 'server_time', now());
end $$;

-- GET /nearby — candidatos próximos (ST_DWithin em geography com índice GiST)
create or replace function game_api.nearby(p_actor uuid, p_lat double precision, p_lon double precision, p_radius_m numeric default 80)
returns jsonb language plpgsql stable security definer set search_path = pg_catalog, extensions, pg_temp as $$
declare v_pt geography; v_r numeric := least(greatest(coalesce(p_radius_m, 80), 1), 150);
begin
  if p_lat is null or p_lon is null or p_lat not between -90 and 90 or p_lon not between -180 and 180 then
    perform game.err('VALIDATION_FAILED', 'Coordenadas inválidas');
  end if;
  v_pt := st_setsrid(st_makepoint(p_lon, p_lat), 4326)::geography;
  return jsonb_build_object('server_time', now(), 'items', coalesce((
    select jsonb_agg(x.item order by x.d)
      from (select st_distance(p.interaction_geog, v_pt) as d,
                   game.property_summary(p.id) || jsonb_build_object(
                     'distance_m', round(st_distance(p.interaction_geog, v_pt)::numeric, 1),
                     'state', game.viewer_state(p_actor, p.id, p.eligibility, o.owner_id),
                     'interaction_quality', p.interaction_quality) as item
              from game.properties p
              join game.regions r on r.id = p.region_id and r.release_status = 'released'
              left join game.ownerships o on o.id = p.current_ownership_id
             where st_dwithin(p.interaction_geog, v_pt, v_r) and p.eligibility <> 'removed_from_source'
             order by p.interaction_geog <-> v_pt
             limit 20) x), '[]'::jsonb));
end $$;

-- ---------------------------------------------------------------------------
-- GET /properties/{id} — ficha e ações permitidas no instante atual
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
      v_accrued := game.accrued_income(v_own.purchase_price, v_own.rate_snapshot, v_own.income_checkpoint_at, v_now, v_own.income_fraction);
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
       'pending_offers', v_received) end,
    'my_proposal', case when v_my.id is null then null else game.proposal_json(v_my.id, p_actor) end,
    'actions', jsonb_build_object(
       'purchase', jsonb_build_object(
          'allowed', v_open and v_own.id is null and v_cat.id is not null,
          'price', case when v_own.id is null then game.money_text(v_cat.base_price) end,
          'daily_income', case when v_own.id is null then game.money_text(v_cat.base_price * v_rate) end,
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
-- POST /presence — prova de uso único (~30 s). Servidor é a autoridade da distância.
-- ---------------------------------------------------------------------------
create or replace function game.presence_message(p_code text) returns text
language sql immutable set search_path = pg_catalog as $$
  select case p_code
    when 'OUT_OF_RANGE'        then 'Aproxime-se do imóvel para continuar'
    when 'LOCATION_INACCURATE' then 'Sinal de GPS impreciso; vá para um local aberto'
    when 'LOCATION_STALE'      then 'Localização antiga; aguarde uma nova leitura do GPS'
    when 'SPEED_LIMIT'         then 'Pare em um local seguro para negociar'
    when 'LOCATION_SUSPICIOUS' then 'Não foi possível validar sua localização'
    when 'NONCE_REUSED'        then 'Leitura de localização repetida; tente novamente'
    when 'RATE_LIMITED'        then 'Muitas tentativas; aguarde um instante'
    else 'Não foi possível confirmar sua presença' end
$$;

create or replace function game_api.create_presence_proof(
  p_actor uuid, p_property_id uuid, p_lat double precision, p_lon double precision, p_accuracy_m numeric,
  p_fix_age_ms integer, p_speed_mps numeric, p_nonce text, p_mock boolean, p_integrity text)
returns jsonb language plpgsql security definer set search_path = pg_catalog, extensions, pg_temp as $$
declare
  v_cfg jsonb := game.cfg(); v_prop game.properties; v_release text; v_now timestamptz := clock_timestamp();
  v_pt geography; v_dist numeric; v_prev game.presence_proofs; v_dt numeric; v_speed numeric; v_code text; v_id uuid;
  v_recent int; v_radius numeric; v_max_speed numeric; v_exp timestamptz;
begin
  perform game.require_player(p_actor);
  if p_lat is null or p_lon is null or p_lat not between -90 and 90 or p_lon not between -180 and 180 then
    perform game.err('VALIDATION_FAILED', 'Coordenadas inválidas');
  end if;
  if p_nonce is null or length(p_nonce) not between 16 and 128 then
    perform game.err('VALIDATION_FAILED', 'nonce inválido');
  end if;
  select * into v_prop from game.properties where id = p_property_id;
  if not found then perform game.err('PROPERTY_NOT_FOUND', 'Imóvel não encontrado'); end if;
  select release_status into v_release from game.regions where id = v_prop.region_id;
  if v_release is distinct from 'released' then perform game.err('REGION_UNAVAILABLE', 'Região ainda não disponível'); end if;

  v_radius := (v_cfg->>'interaction_radius_m')::numeric;
  v_max_speed := (v_cfg->>'max_purchase_speed_kmh')::numeric;
  v_pt := st_setsrid(st_makepoint(p_lon, p_lat), 4326)::geography;
  v_dist := round(st_distance(v_prop.interaction_geog, v_pt)::numeric, 2);

  select count(*) into v_recent from game.presence_proofs
   where actor_id = p_actor and recorded_at > v_now - interval '1 minute';
  if v_recent >= (v_cfg->>'presence_rate_limit_per_min')::int then
    v_code := 'RATE_LIMITED';
  elsif coalesce(p_mock, false) and not coalesce((v_cfg->>'allow_mock_location')::boolean, false) then
    v_code := 'LOCATION_SUSPICIOUS';
  elsif p_accuracy_m is null or p_accuracy_m <= 0 or p_accuracy_m > (v_cfg->>'max_gps_accuracy_m')::numeric then
    v_code := 'LOCATION_INACCURATE';
  elsif p_fix_age_ms is null or p_fix_age_ms < 0 or p_fix_age_ms > (v_cfg->>'max_location_age_s')::int * 1000 then
    v_code := 'LOCATION_STALE';
  elsif coalesce(p_speed_mps, 0) * 3.6 > v_max_speed then
    v_code := 'SPEED_LIMIT';
  end if;

  -- Deslocamento entre amostras: descontar a incerteza das duas leituras.
  if v_code is null then
    select * into v_prev from game.presence_proofs
     where actor_id = p_actor and location is not null and recorded_at > v_now - interval '1 hour'
     order by recorded_at desc limit 1;
    if found then
      v_dt := extract(epoch from (v_now - v_prev.recorded_at));
      if v_dt >= 2 then
        v_speed := greatest(st_distance(v_prev.location, v_pt)::numeric - v_prev.accuracy_m - p_accuracy_m, 0) / v_dt * 3.6;
        if v_speed > (v_cfg->>'impossible_speed_kmh')::numeric then
          v_code := 'LOCATION_SUSPICIOUS';
        elsif v_dt <= 60 and v_speed > v_max_speed then
          v_code := 'SPEED_LIMIT';
        end if;
      end if;
    end if;
  end if;

  if v_code is null and not st_dwithin(v_prop.interaction_geog, v_pt, v_radius) then
    v_code := 'OUT_OF_RANGE';
  end if;

  if v_code is null then
    v_exp := v_now + make_interval(secs => (v_cfg->>'presence_proof_ttl_s')::int);
    insert into game.presence_proofs (nonce, actor_id, property_id, recorded_at, expires_at, location, accuracy_m,
                                      distance_m, fix_age_ms, speed_kmh, mock_location, integrity_verdict)
    values (p_nonce, p_actor, p_property_id, v_now, v_exp, v_pt, p_accuracy_m, v_dist, p_fix_age_ms,
            round(coalesce(p_speed_mps, 0) * 3.6, 2), coalesce(p_mock, false), left(p_integrity, 64))
    on conflict (nonce) do nothing
    returning id into v_id;
    if v_id is null then v_code := 'NONCE_REUSED'; end if;
  end if;

  if v_code is not null then
    insert into game.presence_rejections (actor_id, property_id, reason, accuracy_m, distance_m, fix_age_ms)
    values (p_actor, p_property_id, v_code, p_accuracy_m, v_dist, p_fix_age_ms);
    if v_code = 'LOCATION_SUSPICIOUS' then
      insert into game.risk_signals (context, buyer_id, property_id, score, reasons)
      values ('presence', p_actor, p_property_id, 30, array['location_suspicious']);
    end if;
    return jsonb_build_object('ok', false, 'server_time', v_now, 'error', jsonb_build_object(
      'code', v_code, 'message', game.presence_message(v_code),
      'detail', jsonb_build_object('distance_m', v_dist, 'radius_m', v_radius,
                                   'max_accuracy_m', (v_cfg->>'max_gps_accuracy_m')::int,
                                   'max_location_age_s', (v_cfg->>'max_location_age_s')::int)));
  end if;
  return jsonb_build_object('ok', true, 'proof_id', v_id, 'expires_at', v_exp, 'distance_m', v_dist,
                            'radius_m', v_radius, 'server_time', v_now);
end $$;

-- ---------------------------------------------------------------------------
-- Mutações econômicas idempotentes
-- ---------------------------------------------------------------------------
create or replace function game_api.purchase_property(p_actor uuid, p_key text, p_hash text, p_property_id uuid,
                                                      p_proof_id uuid, p_expected_version bigint default null)
returns jsonb language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare v_op record; v_code text; v_msg text; v_detail text;
begin
  select * into v_op from game.op_begin(p_actor, p_key, 'purchase', p_hash);
  if v_op.replay is not null then return v_op.replay; end if;
  begin
    return game.op_succeed(v_op.op_id, game.do_purchase(p_actor, v_op.op_id, p_property_id, p_proof_id, p_expected_version));
  exception when sqlstate 'DV000' then
    get stacked diagnostics v_code = message_text, v_msg = pg_exception_hint, v_detail = pg_exception_detail;
    return game.op_fail(v_op.op_id, v_code, v_msg, v_detail);
  end;
end $$;

create or replace function game_api.create_proposal(p_actor uuid, p_key text, p_hash text, p_property_id uuid,
                                                    p_ownership_id uuid, p_amount numeric, p_proof_id uuid)
returns jsonb language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare v_op record; v_code text; v_msg text; v_detail text;
begin
  select * into v_op from game.op_begin(p_actor, p_key, 'proposal.create', p_hash);
  if v_op.replay is not null then return v_op.replay; end if;
  begin
    return game.op_succeed(v_op.op_id, game.do_create_proposal(p_actor, v_op.op_id, p_property_id, p_ownership_id, p_amount, p_proof_id));
  exception when sqlstate 'DV000' then
    get stacked diagnostics v_code = message_text, v_msg = pg_exception_hint, v_detail = pg_exception_detail;
    return game.op_fail(v_op.op_id, v_code, v_msg, v_detail);
  end;
end $$;

create or replace function game_api.accept_proposal(p_actor uuid, p_key text, p_hash text, p_proposal_id uuid,
                                                    p_expected_version integer default null)
returns jsonb language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare v_op record; v_code text; v_msg text; v_detail text;
begin
  select * into v_op from game.op_begin(p_actor, p_key, 'proposal.accept', p_hash);
  if v_op.replay is not null then return v_op.replay; end if;
  begin
    return game.op_succeed(v_op.op_id, game.do_accept_proposal(p_actor, v_op.op_id, p_proposal_id, p_expected_version));
  exception when sqlstate 'DV000' then
    get stacked diagnostics v_code = message_text, v_msg = pg_exception_hint, v_detail = pg_exception_detail;
    return game.op_fail(v_op.op_id, v_code, v_msg, v_detail);
  end;
end $$;

create or replace function game_api.reject_proposal(p_actor uuid, p_key text, p_hash text, p_proposal_id uuid,
                                                    p_expected_version integer default null)
returns jsonb language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare v_op record; v_code text; v_msg text; v_detail text;
begin
  select * into v_op from game.op_begin(p_actor, p_key, 'proposal.reject', p_hash);
  if v_op.replay is not null then return v_op.replay; end if;
  begin
    return game.op_succeed(v_op.op_id, game.do_reject_proposal(p_actor, v_op.op_id, p_proposal_id, p_expected_version));
  exception when sqlstate 'DV000' then
    get stacked diagnostics v_code = message_text, v_msg = pg_exception_hint, v_detail = pg_exception_detail;
    return game.op_fail(v_op.op_id, v_code, v_msg, v_detail);
  end;
end $$;

create or replace function game_api.cancel_proposal(p_actor uuid, p_key text, p_hash text, p_proposal_id uuid,
                                                    p_expected_version integer default null)
returns jsonb language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare v_op record; v_code text; v_msg text; v_detail text;
begin
  select * into v_op from game.op_begin(p_actor, p_key, 'proposal.cancel', p_hash);
  if v_op.replay is not null then return v_op.replay; end if;
  begin
    return game.op_succeed(v_op.op_id, game.do_cancel_proposal(p_actor, v_op.op_id, p_proposal_id, p_expected_version));
  exception when sqlstate 'DV000' then
    get stacked diagnostics v_code = message_text, v_msg = pg_exception_hint, v_detail = pg_exception_detail;
    return game.op_fail(v_op.op_id, v_code, v_msg, v_detail);
  end;
end $$;

create or replace function game_api.forced_purchase(p_actor uuid, p_key text, p_hash text, p_property_id uuid,
                                                    p_reason text, p_proposal_id uuid, p_proof_id uuid,
                                                    p_expected_version bigint default null)
returns jsonb language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare v_op record; v_code text; v_msg text; v_detail text;
begin
  select * into v_op from game.op_begin(p_actor, p_key, 'forced_purchase', p_hash);
  if v_op.replay is not null then return v_op.replay; end if;
  begin
    return game.op_succeed(v_op.op_id, game.do_forced_purchase(p_actor, v_op.op_id, p_property_id, p_reason,
                                                               p_proposal_id, p_proof_id, p_expected_version));
  exception when sqlstate 'DV000' then
    get stacked diagnostics v_code = message_text, v_msg = pg_exception_hint, v_detail = pg_exception_detail;
    return game.op_fail(v_op.op_id, v_code, v_msg, v_detail);
  end;
end $$;

create or replace function game_api.collect_income(p_actor uuid, p_key text, p_hash text, p_ownership_ids uuid[])
returns jsonb language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare v_op record; v_code text; v_msg text; v_detail text;
begin
  select * into v_op from game.op_begin(p_actor, p_key, 'income.collect', p_hash);
  if v_op.replay is not null then return v_op.replay; end if;
  begin
    return game.op_succeed(v_op.op_id, game.do_collect_income(p_actor, v_op.op_id, p_ownership_ids));
  exception when sqlstate 'DV000' then
    get stacked diagnostics v_code = message_text, v_msg = pg_exception_hint, v_detail = pg_exception_detail;
    return game.op_fail(v_op.op_id, v_code, v_msg, v_detail);
  end;
end $$;

create or replace function game_api.request_account_deletion(p_actor uuid, p_key text, p_hash text)
returns jsonb language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare v_op record; v_code text; v_msg text; v_detail text;
begin
  select * into v_op from game.op_begin(p_actor, p_key, 'account.deletion', p_hash);
  if v_op.replay is not null then return v_op.replay; end if;
  begin
    return game.op_succeed(v_op.op_id, game.do_request_account_deletion(p_actor, v_op.op_id));
  exception when sqlstate 'DV000' then
    get stacked diagnostics v_code = message_text, v_msg = pg_exception_hint, v_detail = pg_exception_detail;
    return game.op_fail(v_op.op_id, v_code, v_msg, v_detail);
  end;
end $$;

-- GET /operations/{key} — consulta após timeout antes de tentar de novo
create or replace function game_api.get_operation(p_actor uuid, p_key text) returns jsonb
language sql stable security definer set search_path = pg_catalog, pg_temp as $$
  select coalesce(
    (select jsonb_build_object('found', true, 'operation_id', o.id, 'kind', o.kind, 'status', o.status,
                               'created_at', o.created_at, 'completed_at', o.completed_at, 'response', o.response)
       from game.operations o where o.actor_id = p_actor and o.idempotency_key = p_key),
    jsonb_build_object('found', false))
$$;

-- ---------------------------------------------------------------------------
-- Leituras: propostas, portfólio, carteira, histórico, avisos
-- ---------------------------------------------------------------------------
create or replace function game_api.list_proposals(p_actor uuid, p_direction text, p_status text,
                                                   p_cursor text default null, p_limit integer default 50)
returns jsonb language plpgsql stable security definer set search_path = pg_catalog, pg_temp as $$
declare v_lim int := least(greatest(coalesce(p_limit, 50), 1), 100); c record; v_items jsonb; v_n int; v_next text;
begin
  if p_direction not in ('received', 'sent') then perform game.err('VALIDATION_FAILED', 'direction deve ser received ou sent'); end if;
  if p_status is not null and p_status not in ('open', 'closed', 'all') then perform game.err('VALIDATION_FAILED', 'status inválido'); end if;
  select * into c from game.cursor_decode(p_cursor);
  select coalesce(jsonb_agg(x.j order by x.created_at desc, x.id desc), '[]'::jsonb), count(*)
    into v_items, v_n
    from (select pr.id, pr.created_at, game.proposal_json(pr.id, p_actor) as j
            from game.proposals pr
           where ((p_direction = 'received' and pr.seller_id = p_actor) or (p_direction = 'sent' and pr.buyer_id = p_actor))
             and (coalesce(p_status, 'all') = 'all'
                  or (p_status = 'open' and (pr.status = 'pending'
                                             or (pr.status = 'expired' and pr.eligible_until > now()))
                  or (p_status = 'closed' and pr.status not in ('pending')
                                          and not (pr.status = 'expired' and pr.eligible_until > now()))))
             and (c.ts is null or (pr.created_at, pr.id) < (c.ts, c.id::uuid))
           order by pr.created_at desc, pr.id desc
           limit v_lim + 1) x;
  if v_n > v_lim then
    v_items := v_items - v_lim;
    v_next := game.cursor_encode((v_items -> (v_lim - 1) ->> 'created_at')::timestamptz, v_items -> (v_lim - 1) ->> 'id');
  end if;
  return jsonb_build_object('items', v_items, 'next_cursor', v_next, 'server_time', now());
end $$;

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
                   'accrued', game.money_text(trunc(game.accrued_income(o.purchase_price, o.rate_snapshot, o.income_checkpoint_at, v_now, o.income_fraction), 8)),
                   'collectable', game.money_text(game.floor_cents(game.accrued_income(o.purchase_price, o.rate_snapshot, o.income_checkpoint_at, v_now, o.income_fraction))),
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
           'collectable', game.money_text(coalesce(sum(game.floor_cents(game.accrued_income(o.purchase_price, o.rate_snapshot, o.income_checkpoint_at, v_now, o.income_fraction))), 0)),
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
  select coalesce(sum(game.floor_cents(game.accrued_income(o.purchase_price, o.rate_snapshot, o.income_checkpoint_at, now(), o.income_fraction))), 0)
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

create or replace function game_api.history(p_actor uuid, p_kind text default 'all', p_cursor text default null, p_limit integer default 50)
returns jsonb language plpgsql stable security definer set search_path = pg_catalog, pg_temp as $$
declare v_lim int := least(greatest(coalesce(p_limit, 50), 1), 100); c record; v_items jsonb; v_n int; v_next text; v_acct uuid;
        v_kind text := coalesce(p_kind, 'all');
begin
  if v_kind not in ('all', 'purchases', 'sales', 'proposals', 'income') then
    perform game.err('VALIDATION_FAILED', 'Filtro inválido');
  end if;
  select account_id into v_acct from game.wallets where user_id = p_actor;
  select * into c from game.cursor_decode(p_cursor);
  with ev as (
    select 'purchase'::text as kind, t.occurred_at as at, t.id::text as ref, t.price as amount, t.property_id,
           jsonb_build_object('trade_type', t.type, 'counterpart', s.nickname, 'operation_id', t.operation_id) as detail
      from game.trades t left join game.profiles s on s.id = t.seller_id
     where t.buyer_id = p_actor and v_kind in ('all', 'purchases')
    union all
    select 'sale', t.occurred_at, t.id::text, t.price, t.property_id,
           jsonb_build_object('trade_type', t.type, 'counterpart', b.nickname, 'operation_id', t.operation_id)
      from game.trades t left join game.profiles b on b.id = t.buyer_id
     where t.seller_id = p_actor and v_kind in ('all', 'sales')
    union all
    select 'proposal', pr.created_at, pr.id::text, pr.amount, pr.property_id,
           jsonb_build_object('status', case when pr.status = 'pending' and pr.expires_at <= now() then 'expired' else pr.status end,
                              'direction', case when pr.buyer_id = p_actor then 'sent' else 'received' end)
      from game.proposals pr
     where (pr.buyer_id = p_actor or pr.seller_id = p_actor) and v_kind in ('all', 'proposals')
    union all
    select 'income', t.created_at, e.id::text, e.amount, e.property_id,
           jsonb_build_object('transaction_kind', t.kind, 'operation_id', t.operation_id)
      from game.ledger_entries e join game.ledger_transactions t on t.id = e.transaction_id
     where e.account_id = v_acct and e.entry_type = 'income' and v_kind in ('all', 'income')
  )
  select coalesce(jsonb_agg(jsonb_build_object('kind', ev.kind, 'at', ev.at, 'ref', ev.ref,
                                               'amount', game.money_text(ev.amount),
                                               'property', game.property_summary(ev.property_id),
                                               'detail', ev.detail) order by ev.at desc, ev.ref desc), '[]'::jsonb),
         count(*)
    into v_items, v_n
    from (select * from ev where c.ts is null or (ev.at, ev.ref) < (c.ts, c.id)
          order by ev.at desc, ev.ref desc limit v_lim + 1) ev;
  if v_n > v_lim then
    v_items := v_items - v_lim;
    v_next := game.cursor_encode((v_items -> (v_lim - 1) ->> 'at')::timestamptz, v_items -> (v_lim - 1) ->> 'ref');
  end if;
  return jsonb_build_object('items', v_items, 'next_cursor', v_next, 'server_time', now());
end $$;

create or replace function game_api.list_notifications(p_actor uuid, p_cursor text default null, p_limit integer default 50)
returns jsonb language plpgsql stable security definer set search_path = pg_catalog, pg_temp as $$
declare v_lim int := least(greatest(coalesce(p_limit, 50), 1), 100); c record; v_items jsonb; v_n int; v_next text;
begin
  select * into c from game.cursor_decode(p_cursor);
  select coalesce(jsonb_agg(x.j order by x.created_at desc, x.id desc), '[]'::jsonb), count(*) into v_items, v_n
    from (select n.id, n.created_at, jsonb_build_object('id', n.id, 'type', n.type, 'payload', n.payload,
                                                        'created_at', n.created_at, 'read_at', n.read_at) as j
            from game.notifications n
           where n.user_id = p_actor and (c.ts is null or (n.created_at, n.id) < (c.ts, c.id::uuid))
           order by n.created_at desc, n.id desc limit v_lim + 1) x;
  if v_n > v_lim then
    v_items := v_items - v_lim;
    v_next := game.cursor_encode((v_items -> (v_lim - 1) ->> 'created_at')::timestamptz, v_items -> (v_lim - 1) ->> 'id');
  end if;
  return jsonb_build_object('items', v_items, 'next_cursor', v_next,
    'unread', (select count(*) from game.notifications where user_id = p_actor and read_at is null),
    'server_time', now());
end $$;

create or replace function game_api.mark_notifications_read(p_actor uuid, p_ids uuid[]) returns jsonb
language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare v int;
begin
  update game.notifications set read_at = now()
   where user_id = p_actor and read_at is null and (p_ids is null or id = any(p_ids));
  get diagnostics v = row_count;
  return jsonb_build_object('updated', v);
end $$;

create or replace function game_api.register_device(p_actor uuid, p_token text, p_platform text) returns jsonb
language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
begin
  perform game.require_player(p_actor, false);
  if p_token is null or length(p_token) not between 20 and 4096 then perform game.err('VALIDATION_FAILED', 'Token inválido'); end if;
  if p_platform not in ('android', 'ios') then perform game.err('VALIDATION_FAILED', 'Plataforma inválida'); end if;
  insert into game.device_tokens (user_id, token, platform) values (p_actor, p_token, p_platform)
  on conflict (token) do update set user_id = excluded.user_id, platform = excluded.platform,
                                    last_seen_at = now(), revoked_at = null;
  return jsonb_build_object('registered', true);
end $$;

create or replace function game_api.unregister_device(p_actor uuid, p_token text) returns jsonb
language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
begin
  update game.device_tokens set revoked_at = now() where user_id = p_actor and token = p_token and revoked_at is null;
  return jsonb_build_object('revoked', found);
end $$;

create or replace function game_api.create_report(p_actor uuid, p_category text, p_property_id uuid, p_message text)
returns jsonb language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare v_id bigint;
begin
  perform game.require_player(p_actor, false);
  if p_category not in ('map_error','wrong_category','missing_property','abuse','bug','account','other') then
    perform game.err('VALIDATION_FAILED', 'Categoria inválida');
  end if;
  if p_message is null or length(trim(p_message)) not between 5 and 2000 then
    perform game.err('VALIDATION_FAILED', 'Descreva o problema em 5 a 2000 caracteres');
  end if;
  if p_property_id is not null and not exists (select 1 from game.properties where id = p_property_id) then
    perform game.err('PROPERTY_NOT_FOUND', 'Imóvel não encontrado');
  end if;
  insert into game.reports (reporter_id, property_id, category, message)
  values (p_actor, p_property_id, p_category, trim(p_message)) returning id into v_id;
  if p_property_id is not null and p_category in ('map_error','wrong_category') then
    insert into geo.review_cases (property_id, reason, evidence)
    values (p_property_id, 'player_report', jsonb_build_object('report_id', v_id, 'category', p_category));
  end if;
  return jsonb_build_object('report_id', v_id, 'protocol', 'DV-' || lpad(v_id::text, 6, '0'), 'status', 'open');
end $$;

-- ---------------------------------------------------------------------------
-- Outbox (consumida pela Edge Function outbox-worker)
-- ---------------------------------------------------------------------------
create or replace function game_api.outbox_claim(p_limit integer default 50, p_lease_s integer default 60) returns jsonb
language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare v jsonb;
begin
  with c as (
    select id from game.outbox
     where (status in ('pending','failed') and next_attempt_at <= now())
        or (status = 'processing' and locked_until < now())
     order by id
     limit least(greatest(coalesce(p_limit, 50), 1), 200)
     for update skip locked
  ), u as (
    update game.outbox o
       set status = 'processing', locked_until = now() + make_interval(secs => p_lease_s), attempts = o.attempts + 1
      from c where o.id = c.id
    returning o.id, o.event_id, o.topic, o.user_id, o.payload, o.attempts
  )
  select coalesce(jsonb_agg(jsonb_build_object(
           'id', u.id, 'event_id', u.event_id, 'topic', u.topic, 'user_id', u.user_id, 'payload', u.payload,
           'attempts', u.attempts,
           'tokens', coalesce((select jsonb_agg(d.token) from game.device_tokens d
                                where d.user_id = u.user_id and d.revoked_at is null), '[]'::jsonb),
           'prefs', (select pr.notification_prefs from game.profiles pr where pr.id = u.user_id)) order by u.id), '[]'::jsonb)
    into v from u;
  return v;
end $$;

create or replace function game_api.outbox_complete(p_id bigint, p_success boolean, p_error text default null,
                                                    p_invalid_tokens text[] default '{}')
returns void language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
begin
  if p_success then
    update game.outbox set status = 'sent', sent_at = now(), locked_until = null, last_error = null where id = p_id;
  else
    update game.outbox
       set status = case when attempts >= 8 then 'dead' else 'failed' end,
           next_attempt_at = now() + least(interval '1 hour', make_interval(secs => (30 * power(2, least(attempts, 10)))::int)),
           locked_until = null,
           last_error = left(p_error, 500)
     where id = p_id;
  end if;
  if p_invalid_tokens is not null and cardinality(p_invalid_tokens) > 0 then
    update game.device_tokens set revoked_at = now() where token = any(p_invalid_tokens) and revoked_at is null;
  end if;
end $$;
