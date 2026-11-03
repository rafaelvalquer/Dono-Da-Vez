# Testes ponta a ponta (HTTP)

Jornadas pela Edge Function `api` real (`supabase/functions/api/index.ts`), falando HTTP como o app: Bearer do
Auth, `Idempotency-Key` em toda mutação econômica e nenhum `user_id` no corpo.

| Arquivo | O que verifica |
|---|---|
| `journeys.test.mjs` | cadastro → saldo inicial único → mapa → ficha → presença → compra → carteira/portfólio/histórico; proposta com reserva → aviso → aceite com renda liquidada; perfil, avisos e denúncia |
| `failures.test.mjs` | conexão cai depois do commit (compra, proposta, aceite, coleta) e timeout do cliente: o retry com a mesma chave devolve o resultado gravado (`replayed: true`) sem cobrar, reservar ou transferir de novo; `/v1/operations/:key` recupera o resultado; mesma chave com outro conteúdo é recusada |
| `http_security.test.mjs` | sem sessão, token forjado/vencido/sem papel → 401; `user_id` no corpo ignorado; prova de presença alheia; proposta e operação de terceiros; ids de jogadores fora da ficha; mutação sem chave; validação, 404/405/413; limite de taxa 429; reautenticação recente para excluir conta |

As falhas de rede usam `lib/proxy.mjs`: o proxy repassa a requisição, espera o servidor responder (a operação já
foi confirmada no banco) e então derruba a conexão ou segura a resposta além do timeout do cliente.

## Modo emulado (padrão, sem Docker)

PGlite com todas as migrações e o seed, exposto por `pglite-socket`; Deno executando a função `api` real; tokens
HS256 assinados pelo próprio teste com um segredo aleatório, no formato do Supabase Auth. A sessão do banco usa o
papel `dv_backend` (o multiplexador do pglite-socket ignora o usuário do login). Precisa de Deno 2 no PATH ou em
`DENO_BIN`.

```bash
npm ci && npm test
```

```bash
DENO_BIN=/caminho/para/deno npm test
```

## Modo pilha local (Supabase CLI)

Com `DV_E2E_API_URL` definido, os testes usam a pilha local real (`supabase start`, `supabase functions serve`):
login de verdade pelo Auth com a chave publicável e tokens emitidos pelo GoTrue. Todas as URLs precisam ser de
loopback, então homologação e produção são recusadas. A chave secreta local (`supabase status -o env`) só cria
os usuários de teste já confirmados, para não esbarrar no limite de e-mails do Auth. Os testes de token
customizado (vencido, `amr` antigo) só rodam no modo emulado.

```bash
supabase start && supabase db reset
```

```bash
supabase functions serve api --env-file supabase/functions/.env
```

```bash
DV_E2E_API_URL=http://127.0.0.1:54321/functions/v1/api \
DV_E2E_SUPABASE_URL=http://127.0.0.1:54321 \
DV_E2E_DATABASE_URL=postgresql://postgres:postgres@127.0.0.1:54322/postgres \
DV_E2E_PUBLISHABLE_KEY=<PUBLISHABLE_KEY> DV_E2E_SERVICE_ROLE_KEY=<SECRET_KEY> npm test
```

SQL privilegiado (`stack.admin`) existe só para fixtures (imóveis fictícios na região `local-demo`) e para o relógio
simulado (fim da proteção de 24 h, renda acumulada). Nunca é usado para verificar o que a API deveria responder.
