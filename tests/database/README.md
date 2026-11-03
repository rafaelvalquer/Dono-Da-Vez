# Testes de banco

Testes das regras econômicas, RLS/grants, idempotência, importação e concorrência contra as migrações reais de
`supabase/migrations` e o `supabase/seed.sql` (região fictícia `local-demo`). Cobrem os grupos Conta, Compra
inicial, Propostas, Compra sem aceite, Renda, Segurança, Importação, Falhas e Operação do plano de testes.

| Arquivo | O que verifica |
|---|---|
| `account_purchase.test.mjs` | saldo inicial único, e-mail não confirmado, versão mínima, compra inicial (sucesso, replay, conflito de chave, saldo, GPS, prova de presença, suspensão, região pausada) |
| `market.test.mjs` | propostas e reservas, aceite com liquidação de renda, vencimento, compra sem aceite (silêncio e inatividade), renda 12/24/36 h, reconciliação |
| `security.test.mjs` | papéis da Data API negados, privilégio mínimo de `dv_backend`/`dv_admin_backend`/`dv_importer`, RLS, `search_path`, imutabilidade, isolamento entre jogadores |
| `operations.test.mjs` | leituras do app, outbox de push, jobs idempotentes, exclusão de conta, ações administrativas auditadas, importação geográfica sem duplicar nem perder titularidade |
| `concurrency.test.mjs` | duas conexões reais: compradores simultâneos, mesma chave em paralelo, aceitar × cancelar, coleta simultânea |

## Modos

**PGlite (padrão).** Postgres 17 em WASM com PostGIS e pgcrypto, dentro do processo do Node. Cada arquivo de
teste cria um banco novo, aplica `lib/supabase-shim.sql` (papéis `anon`/`authenticated`/`service_role` e
`auth.users`), todas as migrações e o seed (≈ 6 s). Não precisa de Docker. Os testes de concorrência são pulados,
porque o PGlite tem uma única conexão.

```bash
npm ci && npm test
```

```bash
npm run migrate:check
```

**Postgres real.** Com `DV_TEST_DATABASE_URL` os testes usam o banco indicado em vez do PGlite e também rodam
`concurrency.test.mjs`. O banco precisa estar migrado e com o seed: rode `supabase db reset` antes. A URL
`*.supabase.co` é recusada (testes nunca rodam em homologação ou produção); `DV_TEST_ALLOW_REMOTE` existe apenas
para um projeto descartável criado só para isso.

```bash
supabase start && supabase db reset
```

```bash
DV_TEST_DATABASE_URL=postgresql://postgres:postgres@127.0.0.1:54322/postgres npm test
```

## Convenções

- `lib/game.mjs` chama `game_api` como `dv_backend`, exatamente como a Edge Function `api`; o ator é sempre um
  parâmetro da função, nunca um campo do corpo.
- Erros de domínio têm SQLSTATE `DV000`, MESSAGE = código estável e HINT = mensagem. Mutações devolvem
  `{ok:false, error:{code,...}}`; leituras levantam o erro.
- Tempo é simulado movendo carimbos (`ageOwnership`, `expireNow`, `endProtection`, `makeInactive`), não com espera.
- Cada teste termina conferindo as invariantes (`g.invariants()` igual a `CLEAN`): carteiras batem com o razão,
  transações balanceadas, no máximo um dono por imóvel e nenhuma reserva órfã.
