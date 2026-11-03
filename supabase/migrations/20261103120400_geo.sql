-- Dono da Vez — 0005: fonte geográfica (schema separado dos dados econômicos)

create table geo.import_batches (
  id             uuid primary key default gen_random_uuid(),
  region_id      uuid not null references game.regions(id) on delete restrict,
  provider       text not null default 'osm',
  source_url     text,
  source_version text,
  source_hash    text not null check (source_hash ~ '^[0-9a-f]{64}$'),
  rules_version  integer not null,
  status         text not null default 'running' check (status in (
                   'running','staged','validated','approved','published','failed','rejected','superseded')),
  counts         jsonb not null default '{}'::jsonb,
  report         jsonb not null default '{}'::jsonb,
  errors         jsonb not null default '[]'::jsonb,
  started_at     timestamptz not null default now(),
  completed_at   timestamptz,
  validated_at   timestamptz,
  approved_at    timestamptz,
  approved_by    uuid,
  published_at   timestamptz
);
create unique index import_batches_live_uq on geo.import_batches (region_id, source_hash, rules_version)
  where status in ('running','staged','validated','approved','published');
create index import_batches_region_idx on geo.import_batches (region_id, started_at desc);

alter table game.regions
  add constraint regions_active_dataset_fk foreign key (active_dataset_version)
  references geo.import_batches(id) on delete restrict;
alter table game.properties
  add constraint properties_dataset_batch_fk foreign key (dataset_batch_id)
  references geo.import_batches(id) on delete restrict;

-- Resultado consolidado do pipeline (uma linha por propriedade candidata).
create table geo.staging_properties (
  batch_id                  uuid not null references geo.import_batches(id) on delete cascade,
  canonical_ref             text not null check (canonical_ref ~ '^[a-z]+:(node|way|relation):[0-9]+$'),
  source_refs               jsonb not null check (jsonb_typeof(source_refs) = 'array' and jsonb_array_length(source_refs) > 0),
  category_code             text,
  subtype                   text,
  classification_status     text not null,
  classification_confidence numeric(4,3) not null,
  address_label             text,
  point                     extensions.geometry(Point, 4326) not null,
  footprint                 extensions.geometry(MultiPolygon, 4326),
  interaction_point         extensions.geometry(Point, 4326) not null,
  interaction_quality       text not null,
  height_m                  numeric(6,2) not null,
  height_source             text not null,
  visual_model              text not null,
  units_count               integer not null default 1,
  eligibility               text not null,
  eligibility_reason        text,
  source_tags               jsonb not null default '{}'::jsonb,
  primary key (batch_id, canonical_ref)
);

create table geo.staging_rejects (
  id         bigint generated always as identity primary key,
  batch_id   uuid not null references geo.import_batches(id) on delete cascade,
  source_ref text not null,
  reason     text not null,
  detail     jsonb not null default '{}'::jsonb
);
create index staging_rejects_batch_idx on geo.staging_rejects (batch_id);

-- Mapeamento fonte → property_id interno (várias referências por propriedade).
create table geo.source_features (
  id                 bigint generated always as identity primary key,
  provider           text not null,
  object_type        text not null check (object_type in ('node','way','relation')),
  source_id          bigint not null,
  source_version     integer,
  property_id        uuid not null references game.properties(id) on delete restrict,
  role               text not null default 'primary' check (role in ('primary','poi','entrance','address')),
  import_batch_id    uuid references geo.import_batches(id) on delete restrict,
  first_seen_batch_id uuid references geo.import_batches(id) on delete restrict,
  last_seen_batch_id uuid references geo.import_batches(id) on delete restrict,
  active             boolean not null default true,
  constraint source_features_origin_uq unique (provider, object_type, source_id)
);
create index source_features_property_idx on geo.source_features (property_id);

create table geo.review_cases (
  id          uuid primary key default gen_random_uuid(),
  property_id uuid references game.properties(id) on delete restrict,
  batch_id    uuid references geo.import_batches(id) on delete restrict,
  reason      text not null check (reason in (
                'split','merge','removed_owned','removed','ambiguous_geometry','inaccessible',
                'mixed_use','classification_changed_owned','owned_property_ineligible','player_report')),
  evidence    jsonb not null default '{}'::jsonb,
  status      text not null default 'open' check (status in ('open','resolved','dismissed')),
  resolution  jsonb,
  created_at  timestamptz not null default now(),
  resolved_at timestamptz,
  resolved_by uuid
);
create index review_cases_status_idx on geo.review_cases (status, created_at);
create index review_cases_property_idx on geo.review_cases (property_id);

-- Auditoria de alterações geográficas aplicadas a imóveis existentes.
create table geo.property_changes (
  id          bigint generated always as identity primary key,
  property_id uuid not null references game.properties(id) on delete restrict,
  batch_id    uuid references geo.import_batches(id) on delete restrict,
  change_kind text not null,
  before      jsonb,
  after       jsonb,
  changed_at  timestamptz not null default now()
);
create index property_changes_property_idx on geo.property_changes (property_id, changed_at desc);
