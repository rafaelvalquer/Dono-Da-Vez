-- Dono da Vez — 0001: extensões, schemas internos e papéis
-- Schemas do jogo NÃO são expostos pela Data API. O app só fala com Auth e Edge Functions.

create schema if not exists extensions;
create extension if not exists postgis with schema extensions;
create extension if not exists pgcrypto with schema extensions;

-- pg_cron e pg_net existem no Supabase; em Postgres puro (testes) podem não existir.
do $$
begin
  if exists (select 1 from pg_available_extensions where name = 'pg_cron') then
    create extension if not exists pg_cron;
  end if;
  if exists (select 1 from pg_available_extensions where name = 'pg_net') then
    create extension if not exists pg_net;
  end if;
end $$;

create schema if not exists game;           -- tabelas econômicas e catálogo
create schema if not exists game_api;       -- funções chamadas pela API (papel dv_backend)
create schema if not exists game_internal;  -- jobs agendados
create schema if not exists admin_api;      -- funções do console administrativo
create schema if not exists geo;            -- fonte geográfica, staging e reconciliação
create schema if not exists geo_api;        -- funções do importador/publicação

revoke all on schema game, game_api, game_internal, admin_api, geo, geo_api from public;

-- Funções novas nesses schemas não ficam executáveis por PUBLIC.
alter default privileges in schema game revoke execute on functions from public;
alter default privileges in schema game_api revoke execute on functions from public;
alter default privileges in schema game_internal revoke execute on functions from public;
alter default privileges in schema admin_api revoke execute on functions from public;
alter default privileges in schema geo revoke execute on functions from public;
alter default privileges in schema geo_api revoke execute on functions from public;

-- Papéis. Senhas/LOGIN são configurados fora das migrações (docs/setup.md).
--  dv_engine         NOLOGIN: dono das funções SECURITY DEFINER econômicas
--  dv_geo_publisher  NOLOGIN: dono das funções de publicação geográfica
--  dv_backend        API do jogo (Edge Function api)
--  dv_admin_backend  console administrativo (Edge Function admin)
--  dv_importer       importador geográfico (somente staging/publicação)
do $$
declare r text;
begin
  foreach r in array array['dv_engine','dv_geo_publisher','dv_backend','dv_admin_backend','dv_importer'] loop
    if not exists (select 1 from pg_roles where rolname = r) then
      execute format('create role %I nologin noinherit', r);
    end if;
  end loop;
end $$;

-- O papel de migração precisa poder transferir a posse das funções.
grant dv_engine to current_user;
grant dv_geo_publisher to current_user;

grant usage on schema extensions to dv_engine, dv_geo_publisher;
