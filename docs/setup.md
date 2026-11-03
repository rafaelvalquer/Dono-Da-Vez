# Setup e credenciais

Este guia cobre o ambiente local e a configuração de homologação/produção. Os passos marcados com
**[conta]** dependem de acessos que só o responsável pelo projeto tem (Supabase, Firebase, fornecedor de
tiles, chave de assinatura Android) e não podem ser automatizados pelo repositório.

## 1. Local

1. Instale Docker, Supabase CLI (≥ 2.60), Node 22, Deno 2, Python 3.12 e Android Studio.
2. `supabase start` e depois `supabase db reset` (aplica as migrações e o `seed.sql` local).
   - O seed define senhas **somente locais** para `dv_backend`, `dv_admin_backend` e `dv_importer`, uma
     versão de configuração com GPS simulado permitido, segredos do Vault para o worker da outbox e a região
     fictícia `local-demo`.
3. Edge Functions: copie `supabase/functions/.env.example` para `supabase/functions/.env` e rode
   `supabase functions serve --env-file supabase/functions/.env`.
4. Crie um usuário de teste em `http://127.0.0.1:54323` (Studio → Authentication) ou pelo app; o e-mail de
   confirmação local aparece no Inbucket (`http://127.0.0.1:54324`).
5. App Android: abra `apps/android` no Android Studio, selecione a variante `localDebug`. O emulador alcança o
   host em `10.0.2.2`; os valores locais já estão em `apps/android/gradle.properties`. Para aparelho físico,
   use o IP da máquina na rede (`-PdvLocalHost=192.168.x.y`).
6. Painel: `npm --prefix apps/admin ci && npm --prefix apps/admin run dev` e acesse `http://127.0.0.1:5173`.
   Para conceder papel a um usuário local:
   `insert into game.admin_roles (user_id, role, granted_by) values ('<uuid>', 'superadmin', '<uuid>');`
   e cadastre um fator TOTP no primeiro acesso.
7. Importador com fixture: `docker build -t dv-geo-importer services/geo-importer` e
   `docker run --rm --network host -e DV_IMPORTER_DB_URL=postgresql://dv_importer:dv_importer_local@127.0.0.1:54322/postgres dv-geo-importer import --region fixture --pbf /app/fixtures/sample.osm --region-geojson /app/fixtures/sample-region.geojson`.

## 2. Projetos Supabase [conta]

Crie **dois projetos separados** (homologação e produção) na conta existente; não reutilize tabelas de outro
produto. Registre os `project ref` como variáveis do repositório (`SUPABASE_PROJECT_REF_STAGING`,
`SUPABASE_PROJECT_REF_PROD`).

Para cada projeto:

1. **Migrações**: `supabase link --project-ref <ref>` e `supabase db push` (ou o workflow `deploy.yml`).
   Habilite `pg_cron` e `pg_net` em Database → Extensions **antes** do primeiro push para que os jobs sejam
   agendados (a migração só agenda se as extensões existirem).
2. **Senhas dos papéis de login** (gere senhas fortes no cofre; nunca versione):
   ```sql
   alter role dv_backend login password '<senha>';
   alter role dv_admin_backend login password '<senha>';
   alter role dv_importer login password '<senha>';
   ```
   Pelo pooler (Supavisor) o usuário tem o formato `dv_backend.<project_ref>`. Use o modo *transaction*
   (porta 6543) nas Edge Functions e *session* (porta 5432) ou conexão direta no importador (transação longa de staging).
3. **Vault** (worker da outbox chamado pelo pg_cron):
   ```sql
   select vault.create_secret('https://<ref>.supabase.co/functions/v1/outbox-worker', 'dv_outbox_worker_url');
   select vault.create_secret('<segredo aleatório de 32+ bytes>', 'dv_cron_secret');
   ```
4. **Auth**: confirmação de e-mail ligada; recuperação de senha; Redirect URLs com
   `donodavez://auth-callback`; SMTP próprio para produção (o SMTP padrão tem limite baixo); MFA TOTP ligado
   (exigido pelo painel). Ajuste o rate limit de e-mails.
5. **Data API**: Settings → API → *Exposed schemas* deve conter apenas `public` e `graphql_public`.
6. **Storage**: o bucket público `assets` é criado pela migração; envie o conteúdo de `assets/public`.
7. **Secrets das Edge Functions** (`supabase secrets set --project-ref <ref> ...`):

   | Secret | Valor |
   |---|---|
   | `APP_ENV` | `staging` ou `production` |
   | `DV_BACKEND_DB_URL` | `postgresql://dv_backend.<ref>:<senha>@<pooler-host>:6543/postgres` |
   | `DV_ADMIN_DB_URL` | `postgresql://dv_admin_backend.<ref>:<senha>@<pooler-host>:6543/postgres` |
   | `DV_CRON_SECRET` | o mesmo valor salvo no Vault |
   | `DV_ADMIN_ORIGINS` | origem(ns) do painel, ex.: `https://admin.exemplo.com` |
   | `FCM_SERVICE_ACCOUNT_JSON` | JSON da conta de serviço do Firebase (uma linha) |

   `DV_JWT_SECRET` e `DV_JWT_ISSUER` ficam **vazios** na nuvem (verificação por JWKS do próprio projeto).
   `SUPABASE_URL` e `SUPABASE_SERVICE_ROLE_KEY` são injetados pela plataforma; só o `outbox-worker` usa a
   service role (para apagar usuários do Auth após a exclusão de conta).
8. Implante: `supabase functions deploy api admin outbox-worker --project-ref <ref>`
   (`verify_jwt = false` porque as funções verificam o JWT por conta própria).

## 3. Firebase (push) [conta]

1. Crie um projeto Firebase por ambiente e registre o app Android com os IDs
   `app.donodavez.staging` e `app.donodavez`.
2. Baixe `google-services.json` para `apps/android/app/src/<flavor>/google-services.json` (ignorado pelo git).
   Sem o arquivo o app compila e funciona sem push.
3. Gere uma chave de conta de serviço com o papel *Firebase Cloud Messaging API Admin* e salve o JSON em
   `FCM_SERVICE_ACCOUNT_JSON`. Sem ela o worker registra o push em log (modo simulado).

## 4. Tiles vetoriais [conta]

O estilo base (`assets/styles/dono-da-vez.json`) espera uma fonte vetorial no esquema OpenMapTiles.
Contrate um fornecedor com atribuição clara, limites e alertas de consumo (50/80/100% do orçamento).
Não use os tiles do openstreetmap.org (política de uso proíbe apps). Configure por ambiente:

- `MAP_STYLE_URL`: URL do estilo (ou do `style.json` hospedado no bucket `assets`, com `{key}` substituída).
- `MAP_PUBLIC_KEY`: chave pública **restrita por pacote Android/assinatura**.

Valide em aparelho Android real: 45° de inclinação, extrusão e ≥ 30 FPS.

## 5. Assinatura Android [conta]

1. Gere o keystore de upload (fora do repositório) e ative o Play App Signing.
2. Secrets do CI: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
   `ANDROID_KEY_PASSWORD`. O workflow `deploy.yml` decodifica o keystore em tempo de build.
3. Localmente: `~/.gradle/gradle.properties` com `DV_KEYSTORE_FILE`, `DV_KEYSTORE_PASSWORD`, `DV_KEY_ALIAS`,
   `DV_KEY_PASSWORD`.

## 6. Região piloto [conta/decisão]

`services/geo-importer/regions/sp-pinheiros-vila-madalena.geojson` é uma **aproximação** inicial. Revise o
polígono antes de publicar. Fluxo: importação → status `qa` → teste de campo com dois aparelhos e contas
distintas → `released` pelo painel.

## 7. Segredos do CI (GitHub)

| Secret | Uso |
|---|---|
| `SUPABASE_ACCESS_TOKEN` | CLI para `db push` e deploy de funções |
| `SUPABASE_DB_PASSWORD_STAGING` / `_PROD` | `supabase link` |
| `DV_IMPORTER_DB_URL_STAGING` / `_PROD` | workflow de importação |
| `ANDROID_*` | assinatura (ver acima) |
| `GOOGLE_SERVICES_JSON_STAGING` / `_PROD` | build com push |

Produção usa *environments* do GitHub com aprovação manual.
