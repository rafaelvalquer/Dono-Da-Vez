-- Dono da Vez — 0002: configuração versionada, categorias, regiões e imóveis

create or replace function game.forbid_mutation() returns trigger
language plpgsql set search_path = pg_catalog, pg_temp as $$
begin
  raise exception 'tabela %.% é imutável (use lançamento compensatório)', tg_table_schema, tg_table_name
    using errcode = 'DV001';
end $$;

create or replace function game.touch_updated_at() returns trigger
language plpgsql set search_path = pg_catalog, pg_temp as $$
begin
  new.updated_at := now();
  return new;
end $$;

-- ---------------------------------------------------------------------------
-- game_config: versões imutáveis. Propostas capturam a versão vigente na criação.
-- ---------------------------------------------------------------------------
create table game.game_config (
  version      integer primary key check (version > 0),
  values       jsonb not null check (jsonb_typeof(values) = 'object'),
  effective_at timestamptz not null default now(),
  note         text,
  created_by   uuid,
  created_at   timestamptz not null default now()
);
create trigger game_config_immutable before update or delete on game.game_config
  for each row execute function game.forbid_mutation();

insert into game.game_config (version, values, note) values (1, $json${
  "initial_balance": "5000.00",
  "daily_income_rate": "0.01",
  "interaction_radius_m": 80,
  "max_gps_accuracy_m": 30,
  "max_location_age_s": 15,
  "presence_proof_ttl_s": 30,
  "presence_rate_limit_per_min": 20,
  "max_purchase_speed_kmh": 15,
  "impossible_speed_kmh": 300,
  "allow_mock_location": false,
  "presence_retention_s": 604800,
  "proposal_response_window_s": 86400,
  "owner_inactivity_s": 1209600,
  "forced_purchase_window_s": 86400,
  "proposal_cooldown_s": 86400,
  "transfer_protection_s": 86400,
  "forced_premium": "1.20",
  "max_open_proposals_per_buyer": 10,
  "max_offer_amount": "1000000000.00",
  "reminder_before_expiry_s": 7200,
  "heartbeat_min_interval_s": 60,
  "house_small_max_area_m2": 150,
  "map_max_bbox_km2": 6,
  "map_cluster_max_bbox_km2": 400,
  "map_max_features": 500,
  "risk_block_score": 60,
  "geo_auto_approve": true,
  "geo_max_removed_ratio": 0.05,
  "min_app_version": 1
}$json$::jsonb, 'Parâmetros iniciais propostos no projeto técnico v1.0');

-- ---------------------------------------------------------------------------
-- Categorias: preço base global (consistente entre regiões).
-- ---------------------------------------------------------------------------
create table game.categories (
  id            smallint primary key,
  code          text not null unique check (code ~ '^[a-z_]{3,40}$'),
  name          text not null,
  base_price    numeric(24,8) not null check (base_price > 0 and base_price = round(base_price, 2)),
  visual_key    text not null,
  rules_version integer not null default 1,
  active        boolean not null default true,
  updated_at    timestamptz not null default now()
);
create trigger categories_touch before update on game.categories
  for each row execute function game.touch_updated_at();

insert into game.categories (id, code, name, base_price, visual_key) values
  (1, 'house_small',          'Casa pequena',       1000.00,  'house_small'),
  (2, 'house_large',          'Casa grande',        2500.00,  'house_large'),
  (3, 'commercial',           'Comércio',           3000.00,  'commercial'),
  (4, 'residential_building', 'Prédio residencial', 10000.00, 'residential_tower');

-- ---------------------------------------------------------------------------
-- Regiões publicadas
-- ---------------------------------------------------------------------------
create table game.regions (
  id                     uuid primary key default gen_random_uuid(),
  code                   text not null unique check (code ~ '^[a-z0-9-]{3,64}$'),
  name                   text not null,
  polygon                extensions.geometry(MultiPolygon, 4326) not null,
  import_status          text not null default 'never_imported'
                         check (import_status in ('never_imported','importing','staged','published','failed')),
  release_status         text not null default 'draft'
                         check (release_status in ('draft','qa','released','paused')),
  active_dataset_version uuid,
  attribution            text not null default '© OpenStreetMap contributors (ODbL)',
  created_at             timestamptz not null default now(),
  updated_at             timestamptz not null default now()
);
create index regions_polygon_gist on game.regions using gist (polygon);
create trigger regions_touch before update on game.regions
  for each row execute function game.touch_updated_at();

-- ---------------------------------------------------------------------------
-- Imóveis do jogo: UUID interno permanente; geometria vem do importador.
-- ---------------------------------------------------------------------------
create table game.properties (
  id                           uuid primary key default gen_random_uuid(),
  region_id                    uuid not null references game.regions(id) on delete restrict,
  category_id                  smallint references game.categories(id) on delete restrict,
  subtype                      text,
  classification_status        text not null
                               check (classification_status in ('resolved','pending_size','undefined','residential_undefined','mixed_use_pending')),
  classification_confidence    numeric(4,3) not null default 0 check (classification_confidence between 0 and 1),
  classification_rules_version integer not null default 1,
  address_label                text,
  point                        extensions.geometry(Point, 4326) not null,
  footprint                    extensions.geometry(MultiPolygon, 4326),
  interaction_point            extensions.geometry(Point, 4326) not null,
  interaction_geog             extensions.geography(Point, 4326)
                               generated always as (interaction_point::extensions.geography) stored,
  interaction_quality          text not null
                               check (interaction_quality in ('entrance_verified','entrance_mapped','derived_edge','poi_point','centroid_fallback')),
  height_m                     numeric(6,2) not null default 6 check (height_m > 0),
  height_source                text not null default 'stylized' check (height_source in ('source','levels','stylized')),
  visual_model                 text not null default 'generic',
  units_count                  integer not null default 1 check (units_count >= 1),
  source_tags_summary          jsonb not null default '{}'::jsonb,
  eligibility                  text not null default 'under_review'
                               check (eligibility in ('eligible','pending_classification','under_review','suspended','removed_from_source')),
  eligibility_reason           text,
  current_ownership_id         uuid,
  last_transfer_at             timestamptz,
  version                      bigint not null default 1 check (version > 0),
  dataset_batch_id             uuid,
  created_at                   timestamptz not null default now(),
  updated_at                   timestamptz not null default now(),
  geo_updated_at               timestamptz not null default now(),
  constraint properties_eligible_requires_category
    check (eligibility <> 'eligible' or (category_id is not null and classification_status = 'resolved'))
);
create index properties_interaction_geog_gist on game.properties using gist (interaction_geog);
create index properties_footprint_gist on game.properties using gist (footprint);
create index properties_point_gist on game.properties using gist (point);
create index properties_region_idx on game.properties (region_id);
create index properties_current_ownership_idx on game.properties (current_ownership_id) where current_ownership_id is not null;
create trigger properties_touch before update on game.properties
  for each row execute function game.touch_updated_at();
