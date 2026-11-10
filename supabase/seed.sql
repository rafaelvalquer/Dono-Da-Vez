-- Seed APENAS do ambiente local (supabase db reset). Dados fictícios para desenvolvimento;
-- homologação/produção usam somente regiões importadas automaticamente do OpenStreetMap.

-- Credenciais locais dos papéis internos (em outros ambientes: cofre de segredos).
alter role dv_backend login password 'dv_backend_local';
alter role dv_admin_backend login password 'dv_admin_local';
alter role dv_importer login password 'dv_importer_local';

-- Configuração local: GPS simulado permitido e aprovação automática de lotes.
-- Próxima versão a partir da vigente (não da v1), para manter o que as migrações publicaram depois
-- (ex.: bônus de inauguração na versão 2).
insert into game.game_config (version, values, note)
select (select max(version) + 1 from game.game_config),
       (game.cfg() - '_version') || '{"allow_mock_location": true}'::jsonb, 'local: GPS simulado permitido'
 where not exists (select 1 from game.game_config where note = 'local: GPS simulado permitido');

-- Segredos para o pg_cron chamar a outbox-worker local.
do $$
begin
  if to_regclass('vault.secrets') is not null then
    if not exists (select 1 from vault.secrets where name = 'dv_outbox_worker_url') then
      perform vault.create_secret('http://host.docker.internal:54321/functions/v1/outbox-worker', 'dv_outbox_worker_url');
    end if;
    if not exists (select 1 from vault.secrets where name = 'dv_cron_secret') then
      perform vault.create_secret('local-cron-secret', 'dv_cron_secret');
    end if;
  end if;
end $$;

-- Região demonstrativa local (fictícia) em torno de -23.5560, -46.6900.
insert into game.regions (id, code, name, polygon, release_status, import_status, attribution)
values ('11111111-1111-4111-8111-111111111111', 'local-demo', 'Demo local (fictício)',
        extensions.st_multi(extensions.st_geomfromtext(
          'POLYGON((-46.6930 -23.5590, -46.6870 -23.5590, -46.6870 -23.5530, -46.6930 -23.5530, -46.6930 -23.5590))', 4326)),
        'released', 'published', 'Dados fictícios de desenvolvimento')
on conflict (code) do nothing;

insert into game.properties (id, region_id, category_id, subtype, classification_status, classification_confidence,
                             address_label, point, footprint, interaction_point, interaction_quality,
                             height_m, height_source, visual_model, eligibility)
select v.id::uuid, '11111111-1111-4111-8111-111111111111', v.cat, v.subtype, 'resolved', 0.9,
       v.addr,
       extensions.st_setsrid(extensions.st_makepoint(v.lon, v.lat), 4326),
       extensions.st_multi(extensions.st_buffer(extensions.st_setsrid(extensions.st_makepoint(v.lon, v.lat), 4326), v.size, 'endcap=square')),
       extensions.st_setsrid(extensions.st_makepoint(v.lon, v.lat - v.size), 4326),
       'derived_edge', v.h, 'stylized', v.model, 'eligible'
  from (values
    ('22222222-0000-4000-8000-000000000001', 1::smallint, null::text, 'Rua Exemplo, 10 (fictício)',  -46.6905, -23.5562, 0.00006, 6.0, 'house_small'),
    ('22222222-0000-4000-8000-000000000002', 1::smallint, null::text, 'Rua Exemplo, 22 (fictício)',  -46.6898, -23.5562, 0.00006, 6.0, 'house_small'),
    ('22222222-0000-4000-8000-000000000003', 2::smallint, null::text, 'Rua Exemplo, 40 (fictício)',  -46.6890, -23.5563, 0.00010, 8.0, 'house_large'),
    ('22222222-0000-4000-8000-000000000004', 3::smallint, 'bakery',   'Rua Modelo, 5 (fictício)',    -46.6903, -23.5555, 0.00007, 7.0, 'commercial'),
    ('22222222-0000-4000-8000-000000000005', 3::smallint, 'cafe',     'Rua Modelo, 17 (fictício)',   -46.6895, -23.5555, 0.00007, 7.0, 'commercial'),
    ('22222222-0000-4000-8000-000000000006', 4::smallint, null::text, 'Avenida Teste, 300 (fictício)', -46.6910, -23.5570, 0.00014, 36.0, 'residential_tower'),
    ('22222222-0000-4000-8000-000000000007', 4::smallint, null::text, 'Avenida Teste, 320 (fictício)', -46.6888, -23.5571, 0.00014, 45.0, 'residential_tower')
  ) as v(id, cat, subtype, addr, lon, lat, size, h, model)
on conflict (id) do nothing;
