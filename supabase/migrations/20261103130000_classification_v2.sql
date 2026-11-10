-- Dono da Vez — 0014: regras de classificação v2 do importador.
-- 1. Categoria estimada ("inferred"): quando o OSM não declara o uso, altura e área estimam a categoria
--    (casa baixa; prédio residencial alto). É negociável; o app avisa que é uma estimativa.
-- 2. Anexos ("part"): pedaço pequeno (edícula, garagem) encostado num prédio vira parte dele. O imóvel antigo
--    do anexo, se não tiver dono, sai do mapa e suas referências passam para o vizinho. Anexo com dono nunca é
--    juntado: vira caso de fusão para revisão. O importador continua sem tocar carteiras, preços ou posses.

alter table game.properties drop constraint properties_classification_status_check;
alter table game.properties add constraint properties_classification_status_check
  check (classification_status in ('resolved','inferred','pending_size','undefined','residential_undefined','mixed_use_pending'));
alter table game.properties drop constraint properties_eligible_requires_category;
alter table game.properties add constraint properties_eligible_requires_category
  check (eligibility <> 'eligible' or (category_id is not null and classification_status in ('resolved','inferred')));
alter table geo.source_features drop constraint source_features_role_check;
alter table geo.source_features add constraint source_features_role_check
  check (role in ('primary','poi','entrance','address','part'));

-- Imóveis antigos de anexos que este lote junta a um vizinho: mapeados por uma referência "part" da linha, sem
-- dono, não suspensos, não mapeados pela referência principal da linha e sem correspondência com outra linha do
-- lote (nesses casos a fusão segue para revisão humana).
create or replace function geo.absorbed_properties(p_batch uuid)
returns table (canonical_ref text, property_id uuid)
language sql stable set search_path = pg_catalog, pg_temp as $$
  with refs as (
    select s.canonical_ref, coalesce(ref->>'role', 'primary') as role, sf.property_id
      from geo.staging_properties s
      cross join lateral jsonb_array_elements(s.source_refs) ref
      join geo.source_features sf
        on sf.provider = ref->>'provider'
       and sf.object_type = ref->>'object_type'
       and sf.source_id = (ref->>'source_id')::bigint
     where s.batch_id = p_batch
  ),
  pairs as (
    select r.canonical_ref, r.property_id, bool_or(r.role = 'part') as by_part, bool_or(r.role = 'primary') as by_primary
      from refs r group by r.canonical_ref, r.property_id
  ),
  shared as (select x.property_id from pairs x group by x.property_id having count(*) > 1)
  select x.canonical_ref, x.property_id
    from pairs x
    join game.properties p on p.id = x.property_id
   where x.by_part and not x.by_primary
     and p.current_ownership_id is null and p.eligibility <> 'suspended'
     and not exists (select 1 from shared where shared.property_id = x.property_id)
$$;
revoke execute on function geo.absorbed_properties(uuid) from public;
grant execute on function geo.absorbed_properties(uuid) to dv_geo_publisher;

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
  except
  -- Anexo juntado: o imóvel antigo do anexo deixa de ser uma correspondência (não é fusão de patrimônio).
  select a.canonical_ref, a.property_id from geo.absorbed_properties(p_batch) a
$$;

create or replace function geo_api.validate_batch(p_batch uuid) returns jsonb
language plpgsql security definer set search_path = pg_catalog, pg_temp as $$
declare b geo.import_batches; v_cfg jsonb := game.cfg(); v_diff jsonb; v_existing int; v_removed int; v_owned_removed int;
        v_ratio numeric; v_auto boolean; v_status text; v_absorbed int;
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
  select count(distinct property_id) into v_absorbed from geo.absorbed_properties(p_batch);
  -- Anexos juntados a um imóvel vizinho não contam como remoção (nada some do mapa; o desenho só cresce).
  with m as materialized (select property_id from geo.batch_matches(p_batch)),
       a as materialized (select property_id from geo.absorbed_properties(p_batch))
  select count(*), count(*) filter (where p.current_ownership_id is not null)
    into v_removed, v_owned_removed
    from game.properties p
   where p.region_id = b.region_id and p.eligibility <> 'removed_from_source'
     and not exists (select 1 from m where m.property_id = p.id)
     and not exists (select 1 from a where a.property_id = p.id);
  v_ratio := case when v_existing = 0 then 0 else round(v_removed::numeric / v_existing, 4) end;
  v_diff := v_diff || jsonb_build_object('existing_active', v_existing, 'removed', v_removed, 'absorbed', v_absorbed,
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

-- Publicação: igual à 0010, mais a categoria estimada e a junção de anexos.
create or replace function geo_api.publish_batch(p_batch uuid) returns jsonb
language plpgsql security definer set search_path = pg_catalog, extensions, pg_temp as $$
declare
  b geo.import_batches; r record; v_split uuid[]; v_cat smallint; v_pid uuid; v_cur game.properties;
  v_new int := 0; v_changed int := 0; v_unchanged int := 0; v_removed int := 0; v_cases int := 0; v_absorbed int := 0;
  v_elig text; v_reason text; v_before jsonb; v_after jsonb; v_class text; v_conf numeric; v_summary jsonb; v_n int;
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
    with abs as materialized (
      select a.canonical_ref, array_agg(a.property_id order by a.property_id) as ids
        from geo.absorbed_properties(p_batch) a group by a.canonical_ref)
    select s.*, coalesce(abs.ids, '{}'::uuid[]) as absorbed,
           (select coalesce(array_agg(distinct sf.property_id), '{}')
              from jsonb_array_elements(s.source_refs) ref
              join geo.source_features sf
                on sf.provider = ref->>'provider' and sf.object_type = ref->>'object_type'
               and sf.source_id = (ref->>'source_id')::bigint
             where sf.property_id <> all(coalesce(abs.ids, '{}'::uuid[]))) as pids
      from geo.staging_properties s
      left join abs on abs.canonical_ref = s.canonical_ref
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
    if v_elig = 'eligible' and (v_cat is null or v_class not in ('resolved', 'inferred')) then
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

    -- Anexos juntados: o imóvel antigo do anexo (sempre sem dono) sai do mapa e suas referências passam para
    -- este imóvel. Nunca há transferência de posse: anexo com dono continua como caso de fusão para revisão.
    if cardinality(r.absorbed) > 0 then
      update geo.source_features sf set property_id = v_pid
        from jsonb_array_elements(r.source_refs) ref
       where sf.property_id = any(r.absorbed)
         and sf.provider = ref->>'provider' and sf.object_type = ref->>'object_type'
         and sf.source_id = (ref->>'source_id')::bigint;
      update geo.source_features set active = false where property_id = any(r.absorbed);
      insert into geo.property_changes (property_id, batch_id, change_kind, before, after)
      select p.id, p_batch, 'absorbed',
             jsonb_build_object('eligibility', p.eligibility, 'eligibility_reason', p.eligibility_reason),
             jsonb_build_object('absorbed_into', v_pid, 'canonical_ref', r.canonical_ref)
        from game.properties p
       where p.id = any(r.absorbed) and p.current_ownership_id is null and p.eligibility <> 'removed_from_source';
      get diagnostics v_n = row_count;
      update game.properties set eligibility = 'removed_from_source', eligibility_reason = 'anexo juntado a outro imóvel',
                                 version = version + 1, geo_updated_at = now()
       where id = any(r.absorbed) and current_ownership_id is null and eligibility <> 'removed_from_source';
      v_absorbed := v_absorbed + v_n;
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
                                  'removed', v_removed, 'absorbed', v_absorbed, 'review_cases', v_cases, 'published_at', now());
  update geo.import_batches set status = 'superseded'
   where region_id = b.region_id and status = 'published' and id <> p_batch;
  update geo.import_batches
     set status = 'published', published_at = now(), report = report || jsonb_build_object('publication', v_summary)
   where id = p_batch;
  update game.regions set active_dataset_version = p_batch, import_status = 'published' where id = b.region_id;
  return v_summary;
end $$;

-- Admin: categoria estimada pode ser liberada como a resolvida.
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
  if p_eligibility = 'eligible' and (p.category_id is null or p.classification_status not in ('resolved', 'inferred')) then
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
    if p.category_id is null or p.classification_status not in ('resolved', 'inferred') then
      perform game.err('VALIDATION_FAILED', 'Classifique o imóvel antes de liberar');
    end if;
    update game.properties set eligibility = 'eligible', eligibility_reason = null, version = version + 1 where id = p.id;
  elsif p_action = 'verify_entrance' then
    update game.properties set interaction_quality = 'entrance_verified',
           eligibility = case when category_id is not null and classification_status in ('resolved', 'inferred') and eligibility = 'under_review' then 'eligible' else eligibility end,
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
