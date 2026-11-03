-- Dono da Vez — 0013: bucket de assets próprios versionados (avatares, sprites, estilos).
-- Leitura pública; escrita somente pelo pipeline de CI com credencial de serviço (sem políticas
-- de INSERT/UPDATE/DELETE para anon/authenticated).
do $$
begin
  if to_regclass('storage.buckets') is not null then
    insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
    values ('assets', 'assets', true, 5242880, array['image/png', 'image/webp', 'image/svg+xml', 'application/json'])
    on conflict (id) do update set public = excluded.public, file_size_limit = excluded.file_size_limit,
                                   allowed_mime_types = excluded.allowed_mime_types;
  end if;
end $$;
