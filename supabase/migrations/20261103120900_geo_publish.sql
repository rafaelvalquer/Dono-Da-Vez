-- Dono da Vez — 0010: registro de lotes, validação e publicação regional atômica.
-- O importador nunca modifica carteiras, preços de aquisição ou titularidades.
-- Funções SECURITY DEFINER pertencem a dv_geo_publisher (grants restritos a colunas geográficas).

create or replace function geo.batch_matches(p_batch uuid)
returns table (canonical_ref text, property_id uuid)
language sql stable set search_path = pg_catalog, pg_temp as $$
  select distinct s.canonical_ref, sf.property_id
    from geo.staging_properties s
    cross join lateral jsonb_array_elements(s.source_refs) ref
    join geo.source_features sf
      on sf.provider = ref->>'provider'
     and sf.object_type = ref->>'object_type'
     and sf.source_id = (ref->>'source_id')::bigint
   where s.batch_id = p_batch
$$;

create or replace function geo_api.get_region(p_code text) returns jsonb
language sql stable security definer set search_path = pg_catalog, extensions, pg_temp as $$
  select jsonb_build_object('id', r.id, 'code', r.code, 'name', r.name, 'release_status', r.release_status,
                            'import_status', r.import_status, 'active_dataset_version', r.active_dataset_version,
                            'polygon', st_asgeojson(r.polygon)::jsonb)
    from game.regions r where r.code = p_code
$$;

-- Registra ou atualiza polígono de região ainda não liberada (draft/qa).
create or replace function geo_api.upsert_region(p_code text, p_name text, p_polygon_geojson jsonb) returns jsonb
language plpgsql security definer set search_path = pg_catalog, extensions, pg_temp as $$
declare v_geom geometry; v_region game.regions;
begin
  v_geom := st_multi(st_makevalid(st_setsrid(st_geomfromgeojson(p_polygon_geojson::text), 4326)));
  if geometrytype(v_geom) <> 'MULTIPOLYGON' or st_isempty(v_geom) then
    perform game.err('VALIDATION_FAILED', 'Polígono de região inválido');
  end if;
  if st_area(v_geom::geography) > 50e6 then
    perform game.err('VALIDATION_FAILED', 'Região piloto acima de 50 km²; divida em regiões menores');
  end if;
  select * into v_region from game.regions where code = p_code for update;
  if not found then
    insert into game.regions (code, name, polygon) values (p_code, p_name, v_geom) returning * into v_region;
  elsif v_region.release_status in ('draft', 'qa') then
    update game.regions set name = coalesce(p_name, name), polygon = v_geom where id = v_region.id returning * into v_region;
  elsif not st_equals(v_region.polygon, v_geom) then
    perform game.err('REGION_LOCKED', 'Região liberada: alteração de polígono exige autorização administrativa');
  end if;
  return geo_api.get_region(p_code);
end $$;

create or replace function geo_api.begin_batch(p_region_code text, p_provider text, p_source_url text, p_source_version text,
                                               p_source_hash text, p_rules_version integer)
returns jsonb language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare v_region game.regions; v_existing geo.import_batches; v_id uuid;
begin
  select * into v_region from game.regions where code = p_region_code for update;
  if not found then perform game.err('REGION_NOT_FOUND', 'Região não registrada'); end if;
  select * into v_existing from geo.import_batches
   where region_id = v_region.id and source_hash = p_source_hash and rules_version = p_rules_version and status = 'published'
   limit 1;
  if found then
    return jsonb_build_object('batch_id', v_existing.id, 'skipped', true, 'reason', 'already_published');
  end if;
  -- Reprocessamento do mesmo arquivo: descarta tentativas anteriores não publicadas.
  update geo.import_batches set status = 'superseded', completed_at = coalesce(completed_at, now())
   where region_id = v_region.id and status in ('running','staged','validated','approved')
     and source_hash = p_source_hash and rules_version = p_rules_version;
  insert into geo.import_batches (region_id, provider, source_url, source_version, source_hash, rules_version, status)
  values (v_region.id, coalesce(p_provider, 'osm'), p_source_url, p_source_version, p_source_hash, p_rules_version, 'running')
  returning id into v_id;
  update game.regions set import_status = 'importing' where id = v_region.id;
  return jsonb_build_object('batch_id', v_id, 'skipped', false, 'region_id', v_region.id);
end $$;

create or replace function geo_api.complete_staging(p_batch uuid, p_counts jsonb, p_report jsonb) returns jsonb
language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare b geo.import_batches; v_rows int;
begin
  select * into b from geo.import_batches where id = p_batch for update;
  if not found or b.status <> 'running' then perform game.err('BATCH_STATE', 'Lote não está em execução'); end if;
  select count(*) into v_rows from geo.staging_properties where batch_id = p_batch;
  update geo.import_batches
     set status = 'staged', completed_at = now(),
         counts = coalesce(p_counts, '{}'::jsonb) || jsonb_build_object('staged_properties', v_rows),
         report = coalesce(p_report, '{}'::jsonb)
   where id = p_batch;
  update game.regions set import_status = 'staged' where id = b.region_id;
  return jsonb_build_object('batch_id', p_batch, 'status', 'staged', 'staged_properties', v_rows);
end $$;

create or replace function geo_api.fail_batch(p_batch uuid, p_errors jsonb) returns void
language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare b geo.import_batches;
begin
  select * into b from geo.import_batches where id = p_batch for update;
  if not found or b.status in ('published', 'superseded') then return; end if;
  update geo.import_batches set status = 'failed', completed_at = now(), errors = errors || coalesce(p_errors, '[]'::jsonb)
   where id = p_batch;
  update game.regions set import_status = case when active_dataset_version is null then 'failed' else 'published' end
   where id = b.region_id;
end $$;

-- Diferença em relação ao catálogo publicado; aprovação automática se dentro dos limites.
create or replace function geo_api.validate_batch(p_batch uuid) returns jsonb
language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare b geo.import_batches; v_cfg jsonb := game.cfg(); v_diff jsonb; v_existing int; v_removed int; v_owned_removed int;
        v_ratio numeric; v_auto boolean; v_status text;
begin
  select * into b from geo.import_batches where id = p_batch for update;
  if not found or b.status not in ('staged', 'validated') then perform game.err('BATCH_STATE', 'Lote não está pronto para validação'); end if;

  with m as (select * from geo.batch_matches(p_batch)),
       per_ref as (select s.canonical_ref, count(m.property_id) n, (array_agg(m.property_id))[1] pid
                     from geo.staging_properties s left join m on m.canonical_ref = s.canonical_ref
                    where s.batch_id = p_batch group by s.canonical_ref),
       per_prop as (select property_id, count(*) n from m group by property_id)
  select jsonb_build_object(
           'new', count(*) filter (where per_ref.n = 0),
           'matched', count(*) filter (where per_ref.n = 1 and coalesce(pp.n, 0) = 1),
           'merge_candidates', count(*) filter (where per_ref.n > 1),
           'split_candidates', count(*) filter (where per_ref.n = 1 and pp.n > 1),
           'eligible', (select count(*) from geo.staging_properties where batch_id = p_batch and eligibility = 'eligible'),
           'pending', (select count(*) from geo.staging_properties where batch_id = p_batch and eligibility <> 'eligible'))
    into v_diff
    from per_ref left join per_prop pp on pp.property_id = per_ref.pid;

  select count(*) into v_existing from game.properties where region_id = b.region_id and eligibility <> 'removed_from_source';
  select count(*), count(*) filter (where p.current_ownership_id is not null)
    into v_removed, v_owned_removed
    from game.properties p
   where p.region_id = b.region_id and p.eligibility <> 'removed_from_source'
     and not exists (select 1 from geo.batch_matches(p_batch) m where m.property_id = p.id);
  v_ratio := case when v_existing = 0 then 0 else round(v_removed::numeric / v_existing, 4) end;
  v_diff := v_diff || jsonb_build_object('existing_active', v_existing, 'removed', v_removed,
                                         'owned_removed', v_owned_removed, 'removed_ratio', v_ratio);
  v_auto := coalesce((v_cfg->>'geo_auto_approve')::boolean, false)
            and v_ratio <= coalesce((v_cfg->>'geo_max_removed_ratio')::numeric, 0.05)
            and (v_diff->>'merge_candidates')::int = 0;
  v_status := case when v_auto then 'approved' else 'validated' end;
  update geo.import_batches
     set status = v_status, validated_at = now(),
         approved_at = case when v_auto then now() end,
         report = report || jsonb_build_object('diff', v_diff, 'auto_approved', v_auto)
   where id = p_batch;
  return jsonb_build_object('batch_id', p_batch, 'status', v_status, 'diff', v_diff, 'auto_approved', v_auto);
end $$;

create or replace function geo_api.approve_batch(p_batch uuid, p_approver uuid, p_note text) returns jsonb
language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare b geo.import_batches;
begin
  select * into b from geo.import_batches where id = p_batch for update;
  if not found or b.status <> 'validated' then perform game.err('BATCH_STATE', 'Somente lotes validados podem ser aprovados'); end if;
  update geo.import_batches
     set status = 'approved', approved_at = now(), approved_by = p_approver,
         report = report || jsonb_build_object('approval_note', p_note)
   where id = p_batch;
  return jsonb_build_object('batch_id', p_batch, 'status', 'approved');
end $$;

create or replace function geo_api.reject_batch(p_batch uuid, p_approver uuid, p_note text) returns jsonb
language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
begin
  update geo.import_batches
     set status = 'rejected', approved_by = p_approver, report = report || jsonb_build_object('rejection_note', p_note)
   where id = p_batch and status in ('staged', 'validated', 'approved');
  if not found then perform game.err('BATCH_STATE', 'Lote não pode ser rejeitado neste estado'); end if;
  return jsonb_build_object('batch_id', p_batch, 'status', 'rejected');
end $$;

-- Publicação: uma transação; trava todos os imóveis da região em ordem de ID.
create or replace function geo_api.publish_batch(p_batch uuid) returns jsonb
language plpgsql security definer set search_path = pg_catalog, extensions, pg_temp as $$
declare
  b geo.import_batches; r record; v_split uuid[]; v_cat smallint; v_pid uuid; v_cur game.properties;
  v_new int := 0; v_changed int := 0; v_unchanged int := 0; v_removed int := 0; v_cases int := 0;
  v_elig text; v_reason text; v_before jsonb; v_after jsonb; v_class text; v_conf numeric; v_summary jsonb;
begin
  select * into b from geo.import_batches where id = p_batch for update;
  if not found then perform game.err('BATCH_STATE', 'Lote não encontrado'); end if;
  if b.status = 'published' then
    return coalesce(b.report->'publication', '{}'::jsonb) || jsonb_build_object('already_published', true);
  end if;
  if b.status <> 'approved' then perform game.err('BATCH_NOT_APPROVED', 'Lote ainda não aprovado'); end if;

  perform 1 from game.regions where id = b.region_id for update;
  perform 1 from game.properties where region_id = b.region_id order by id for update;

  select coalesce(array_agg(m.property_id), '{}') into v_split
    from (select property_id from geo.batch_matches(p_batch) group by property_id having count(*) > 1) m;

  for r in
    select s.*,
           (select coalesce(array_agg(distinct sf.property_id), '{}')
              from jsonb_array_elements(s.source_refs) ref
              join geo.source_features sf
                on sf.provider = ref->>'provider' and sf.object_type = ref->>'object_type'
               and sf.source_id = (ref->>'source_id')::bigint) as pids
      from geo.staging_properties s
     where s.batch_id = p_batch
     order by s.canonical_ref
  loop
    -- Fusão (uma unidade nova cobre vários imóveis) ou divisão: nunca transferir/apagar.
    if cardinality(r.pids) > 1 or (cardinality(r.pids) = 1 and r.pids[1] = any(v_split)) then
      if not exists (select 1 from geo.review_cases c where c.batch_id = p_batch and c.property_id = r.pids[1]
                        and c.reason in ('merge', 'split')) then
        insert into geo.review_cases (property_id, batch_id, reason, evidence)
        values (r.pids[1], p_batch, case when cardinality(r.pids) > 1 then 'merge' else 'split' end,
                jsonb_build_object('canonical_ref', r.canonical_ref, 'property_ids', to_jsonb(r.pids), 'source_refs', r.source_refs));
        v_cases := v_cases + 1;
      end if;
      update geo.source_features sf set last_seen_batch_id = p_batch, active = true
        from jsonb_array_elements(r.source_refs) ref
       where sf.provider = ref->>'provider' and sf.object_type = ref->>'object_type' and sf.source_id = (ref->>'source_id')::bigint;
      continue;
    end if;

    select id into v_cat from game.categories where code = r.category_code;
    v_elig := r.eligibility;
    v_reason := r.eligibility_reason;
    v_class := r.classification_status;
    v_conf := r.classification_confidence;
    if v_elig = 'eligible' and (v_cat is null or v_class <> 'resolved') then
      v_elig := 'pending_classification';
      v_reason := 'classificação incompleta';
    end if;

    if cardinality(r.pids) = 0 then
      insert into game.properties (region_id, category_id, subtype, classification_status, classification_confidence,
                                   classification_rules_version, address_label, point, footprint, interaction_point,
                                   interaction_quality, height_m, height_source, visual_model, units_count,
                                   source_tags_summary, eligibility, eligibility_reason, dataset_batch_id)
      values (b.region_id, v_cat, r.subtype, v_class, v_conf, b.rules_version, r.address_label, r.point, r.footprint,
              r.interaction_point, r.interaction_quality, r.height_m, r.height_source, r.visual_model, r.units_count,
              r.source_tags, v_elig, v_reason, p_batch)
      returning id into v_pid;
      v_new := v_new + 1;
    else
      v_pid := r.pids[1];
      select * into v_cur from game.properties where id = v_pid;
      -- Imóvel com dono: preservar classe econômica; suspender negociações se ficou inelegível.
      if v_cur.current_ownership_id is not null then
        if v_cur.category_id is not null and v_cat is distinct from v_cur.category_id then
          insert into geo.review_cases (property_id, batch_id, reason, evidence)
          values (v_pid, p_batch, 'classification_changed_owned',
                  jsonb_build_object('from', v_cur.category_id, 'to', r.category_code, 'canonical_ref', r.canonical_ref));
          v_cases := v_cases + 1;
          v_cat := v_cur.category_id; v_class := v_cur.classification_status; v_conf := v_cur.classification_confidence;
          v_elig := case when v_elig = 'eligible' then 'eligible' else v_elig end;
        end if;
        if v_elig <> 'eligible' then
          if v_cur.eligibility <> 'suspended' then
            insert into geo.review_cases (property_id, batch_id, reason, evidence)
            values (v_pid, p_batch, 'owned_property_ineligible',
                    jsonb_build_object('staged_eligibility', r.eligibility, 'reason', r.eligibility_reason));
            v_cases := v_cases + 1;
          end if;
          v_elig := 'suspended';
          v_reason := 'revisão geográfica: ' || coalesce(r.eligibility_reason, r.eligibility);
        end if;
      end if;
      -- Suspensão administrativa prevalece sobre o importador.
      if v_cur.eligibility = 'suspended' and coalesce(v_cur.eligibility_reason, '') like 'admin:%' then
        v_elig := v_cur.eligibility; v_reason := v_cur.eligibility_reason;
      end if;

      v_before := jsonb_build_object('category_id', v_cur.category_id, 'subtype', v_cur.subtype, 'classification_status', v_cur.classification_status,
        'address_label', v_cur.address_label, 'footprint', st_astext(v_cur.footprint), 'interaction_point', st_astext(v_cur.interaction_point),
        'interaction_quality', v_cur.interaction_quality, 'height_m', v_cur.height_m, 'visual_model', v_cur.visual_model,
        'units_count', v_cur.units_count, 'eligibility', v_cur.eligibility, 'eligibility_reason', v_cur.eligibility_reason);
      v_after := jsonb_build_object('category_id', v_cat, 'subtype', r.subtype, 'classification_status', v_class,
        'address_label', r.address_label, 'footprint', st_astext(r.footprint), 'interaction_point', st_astext(r.interaction_point),
        'interaction_quality', case when v_cur.interaction_quality = 'entrance_verified' then v_cur.interaction_quality else r.interaction_quality end,
        'height_m', r.height_m, 'visual_model', r.visual_model, 'units_count', r.units_count,
        'eligibility', v_elig, 'eligibility_reason', v_reason);

      if v_before is distinct from v_after then
        update game.properties
           set category_id = v_cat, subtype = r.subtype, classification_status = v_class,
               classification_confidence = v_conf, classification_rules_version = b.rules_version,
               address_label = r.address_label, point = r.point, footprint = r.footprint,
               interaction_point = case when v_cur.interaction_quality = 'entrance_verified' then v_cur.interaction_point else r.interaction_point end,
               interaction_quality = v_after->>'interaction_quality',
               height_m = r.height_m, height_source = r.height_source, visual_model = r.visual_model,
               units_count = r.units_count, source_tags_summary = r.source_tags,
               eligibility = v_elig, eligibility_reason = v_reason,
               version = version + 1, dataset_batch_id = p_batch, geo_updated_at = now()
         where id = v_pid;
        insert into geo.property_changes (property_id, batch_id, change_kind, before, after)
        values (v_pid, p_batch, 'updated', v_before, v_after);
        v_changed := v_changed + 1;
      else
        v_unchanged := v_unchanged + 1;
      end if;
    end if;

    -- Mapeamento de origem: novas referências apontam para o mesmo UUID interno.
    insert into geo.source_features (provider, object_type, source_id, source_version, property_id, role,
                                     import_batch_id, first_seen_batch_id, last_seen_batch_id, active)
    select ref->>'provider', ref->>'object_type', (ref->>'source_id')::bigint, (ref->>'source_version')::int,
           v_pid, coalesce(ref->>'role', 'primary'), p_batch, p_batch, p_batch, true
      from jsonb_array_elements(r.source_refs) ref
    on conflict (provider, object_type, source_id) do update
       set source_version = excluded.source_version, last_seen_batch_id = p_batch, active = true,
           role = excluded.role;
  end loop;

  -- Ausentes na nova versão: nunca apagar; suspender se houver dono.
  for r in select p.id, p.current_ownership_id, p.eligibility, p.eligibility_reason
             from game.properties p
            where p.region_id = b.region_id and p.eligibility <> 'removed_from_source'
              and not exists (select 1 from geo.source_features sf where sf.property_id = p.id and sf.last_seen_batch_id = p_batch)
            order by p.id loop
    if r.current_ownership_id is not null then
      if r.eligibility <> 'suspended' then
        update game.properties set eligibility = 'suspended', eligibility_reason = 'removido da fonte; renda preservada',
                                   version = version + 1, geo_updated_at = now()
         where id = r.id;
      end if;
      insert into geo.review_cases (property_id, batch_id, reason, evidence)
      values (r.id, p_batch, 'removed_owned', jsonb_build_object('previous_eligibility', r.eligibility));
      v_cases := v_cases + 1;
    else
      update game.properties set eligibility = 'removed_from_source', eligibility_reason = 'ausente na versão da fonte',
                                 version = version + 1, geo_updated_at = now()
       where id = r.id;
    end if;
    update geo.source_features set active = false where property_id = r.id;
    insert into geo.property_changes (property_id, batch_id, change_kind, before, after)
    values (r.id, p_batch, 'removed', jsonb_build_object('eligibility', r.eligibility, 'eligibility_reason', r.eligibility_reason), null);
    v_removed := v_removed + 1;
  end loop;

  v_summary := jsonb_build_object('new', v_new, 'changed', v_changed, 'unchanged', v_unchanged,
                                  'removed', v_removed, 'review_cases', v_cases, 'published_at', now());
  update geo.import_batches set status = 'superseded'
   where region_id = b.region_id and status = 'published' and id <> p_batch;
  update geo.import_batches
     set status = 'published', published_at = now(), report = report || jsonb_build_object('publication', v_summary)
   where id = p_batch;
  update game.regions set active_dataset_version = p_batch, import_status = 'published' where id = b.region_id;
  return v_summary;
end $$;
