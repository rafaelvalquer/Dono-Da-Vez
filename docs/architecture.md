# Arquitetura

## Componentes

```
Android (Compose + MapLibre)                 Console admin (React, MFA)
   │  JWT do Supabase Auth                        │  JWT aal2
   ▼                                              ▼
Edge Function `api` ── dv_backend ──┐     Edge Function `admin` ── dv_admin_backend
                                    ▼                      ▼
                    PostgreSQL + PostGIS (schemas não expostos)
                    game_api.* / admin_api.*  (SECURITY DEFINER, dono dv_engine)
                    game.* tabelas  · game_internal.* jobs (pg_cron)
                    geo.* staging   · geo_api.* (dono dv_geo_publisher)
                                    ▲                      │ outbox (pg_net)
                    geo-importer ── dv_importer            ▼
                    (Python, OSM PBF)            Edge Function `outbox-worker` → FCM
```

- **Fonte da verdade**: o banco. Toda regra econômica roda dentro de uma função PL/pgSQL, em uma única
  transação, com bloqueios em ordem global. A Edge Function valida formato, autentica, aplica limites de taxa e
  traduz erros; não decide regras.
- **Data API do Supabase** expõe apenas `public` e `graphql_public`, que ficam vazios. `anon`,
  `authenticated` e `service_role` não têm privilégios nos schemas do jogo.
- **Tempo**: o relógio das regras é o do banco (`clock_timestamp()` capturado depois dos locks).

## Schemas e papéis

| Schema | Conteúdo | Quem executa |
|---|---|---|
| `game` | Tabelas e funções internas (invoker) | somente funções definer |
| `game_api` | Operações do jogo (SECURITY DEFINER) | `dv_backend` |
| `admin_api` | Operações do painel, com papel verificado em `game.admin_roles` e auditoria | `dv_admin_backend` |
| `game_internal` | Jobs (expiração, lembretes, reconciliação, limpeza, relatórios, exclusões) | pg_cron |
| `geo` | Lotes, staging, rejeições, casos de revisão, mudanças | `dv_geo_publisher` |
| `geo_api` | Pipeline de publicação | `dv_importer` |
| `extensions` | PostGIS e pgcrypto | — |

Papéis `NOLOGIN NOINHERIT`: `dv_engine` (dono das funções definer do jogo; DML + políticas RLS),
`dv_geo_publisher` (dono de `geo_api`; grants por coluna em `game.properties`/`game.regions`). Papéis de
login por ambiente: `dv_backend`, `dv_admin_backend`, `dv_importer` — cada um só tem `EXECUTE` no seu schema
de API. RLS habilitado em todas as tabelas com políticas por papel interno (defesa em profundidade).

## Fluxo de uma mutação

1. App gera `Idempotency-Key` ao confirmar a ação e envia com o corpo.
2. `api` verifica o JWT (JWKS, ou HS256 local), aplica rate limit (`game_api.preflight`, transação curta que
   também registra atividade) e calcula `request_hash = sha256(rota + JSON canônico)`.
3. `game_api.<op>` chama `game.op_begin` (insere em `game.operations` com unicidade por ator+chave; replay
   devolve a resposta gravada; hash diferente → `IDEMPOTENCY_CONFLICT`).
4. `game.do_<op>` bloqueia na ordem **imóvel → atividade do dono → propostas (por id) → carteiras (por UUID)
   → reservas/titularidades**, revalida tudo e grava razão, carteiras, titularidade, trade e notificações/outbox.
5. Erros de domínio (`SQLSTATE DV000`) são capturados num subbloco: o efeito econômico é revertido, mas a
   falha fica registrada na operação (replay idêntico). `40001/40P01` são repetidos pela Edge Function.
6. O `outbox-worker` envia push depois do commit; falha de push nunca altera o resultado econômico.

## Rendimento sem job de crédito

`acumulado = fração + preço_pago × taxa × segundos_desde_checkpoint / 86400`. A coleta credita
`floor(acumulado, centavos)` e guarda a fração (`numeric(38,18)`). Na venda, o rendimento do vendedor é
liquidado até o instante da transferência (trunc 8 casas, resto registrado em `income_discarded`).

## Importação geográfica

`geo-importer` baixa o PBF da Geofabrik, recorta o polígono da região, normaliza/classifica e grava em
`geo.staging_properties` em uma transação (inserções em lote; COPY não é aceito em tabelas com RLS). `geo_api.validate_batch` calcula o diff e aprova automaticamente quando a
remoção fica abaixo de 5%. `geo_api.publish_batch` publica atomicamente: nunca apaga imóvel, nunca altera
carteira, preço pago ou titularidade; imóveis com dono que somem ou mudam de forma relevante viram casos de
revisão (`geo.review_cases`).

## Ambientes

| Ambiente | Banco | GPS simulado | Seed fictício |
|---|---|---|---|
| local | Supabase CLI | permitido (config v2 local) | sim (`local-demo`) |
| homologação | projeto Supabase próprio | somente build `staging` | não |
| produção | projeto Supabase próprio | proibido | não |

Testes automatizados nunca rodam contra produção.
