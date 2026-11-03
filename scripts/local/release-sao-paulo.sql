-- APENAS PARA O SUPABASE LOCAL (postgres superuser).
-- Não executar em produção: publicação de regiões reais requer revisão geográfica.
begin;
update game.regions
   set release_status = 'released'
 where code like 'sp-3550308-%'
   and import_status = 'published'
   and active_dataset_version is not null
   and release_status in ('draft','qa','released');
-- Deixa de exibir imóveis fictícios depois de liberar a cidade real.
update game.regions set release_status = 'paused'
 where code = 'local-demo' and exists (
   select 1 from game.regions
    where code like 'sp-3550308-%' and release_status = 'released');
commit;

select count(*) filter (where release_status = 'released') as regioes_ativas,
       count(*) filter (where import_status = 'published') as regioes_publicadas
  from game.regions where code like 'sp-3550308-%';

select count(*) as imoveis,
       count(*) filter (where p.eligibility = 'eligible') as negociaveis
  from game.properties p join game.regions r on r.id = p.region_id
 where r.code like 'sp-3550308-%' and r.release_status = 'released';
