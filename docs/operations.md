# Operações

## Jobs (pg_cron)

| Job | Agenda | Função | Observação |
|---|---|---|---|
| `dv-expire-proposals` | a cada minuto | `game_internal.expire_due_proposals(200)` | materializa vencimentos e libera reservas; a regra já vale antes |
| `dv-expiry-reminders` | a cada 5 min | `game_internal.send_expiry_reminders()` | lembrete ao dono 2 h antes do prazo |
| `dv-outbox-dispatch` | a cada minuto | `game_internal.trigger_outbox_worker()` | chama o `outbox-worker` via pg_net com segredo do Vault |
| `dv-account-deletions` | a cada 2 min | `game_internal.process_account_deletions(20)` | cancela propostas, devolve imóveis, pseudonimiza |
| `dv-reconcile` | 03:17 UTC | `game_internal.reconcile()` | carteira × razão, reservas, soma global, ponteiros; congela divergências |
| `dv-presence-cleanup` | 03:41 UTC | `game_internal.purge_presence()` | apaga coordenadas com mais de 7 dias |
| `dv-economy-report` | 00:05 UTC | `game_internal.economy_daily_report()` | emissão, volume, concentração (participação dos 10 maiores) |

Todos processam lotes pequenos com `FOR UPDATE SKIP LOCKED` e são idempotentes. Atraso de cron não quebra
regras: prazos são avaliados pelo horário no momento da operação.

Consultas úteis:

```sql
select jobname, status, start_time, end_time from cron.job_run_details order by start_time desc limit 20;
select status, count(*) from game.outbox group by 1;
select * from game.reconciliation_alerts where status = 'open';
```

## Monitoramento

Logs estruturados (JSON) das Edge Functions incluem `correlation_id`, rota, status e duração; nunca tokens,
e-mails ou coordenadas. Indicadores (painel → Operação ou `admin_api.ops_metrics`):

- p95 de compra/proposta (< 2 s) e de mapa/próximos (< 800 ms);
- taxa de erros 5xx e de `TRANSIENT` (locks/serialização);
- fila da outbox (`pending` antigo > 5 min) e eventos `dead`;
- atraso dos jobs (último sucesso);
- reservas órfãs e alertas de reconciliação (meta: zero);
- taxa de presença rejeitada por motivo;
- emissão monetária diária vs. relatório econômico;
- consumo de tiles e de e-mail (alertas 50/80/100% do orçamento).

## Backup e restauração

Meta inicial: **RPO 24 h, RTO 4 h** (backups diários do plano Supabase; para RPO menor, contratar PITR).

Ensaio de restauração (trimestral e antes do beta):

1. Restaure o backup em um **projeto isolado** (nunca sobre produção).
2. Desative imediatamente os jobs para evitar reenvio de push antigo:
   `update cron.job set active = false where jobname like 'dv-%';` e marque a outbox pendente como
   `dead` (`update game.outbox set status = 'dead' where status in ('pending','processing','failed');`).
3. Rode `select game_internal.reconcile();` e confira: zero alertas abertos, soma global do razão = 0,
   uma titularidade ativa por imóvel, nenhuma carteira negativa.
4. Meça o tempo total (RTO) e a idade do último dado (RPO) e registre em `docs/incidents.md`.

O backup do banco não cobre: assets (versionados no repositório + bucket), configuração do fornecedor de
tiles, projetos Firebase e secrets (mantidos no cofre).

## Importação geográfica

- Workflow `import.yml` (manual ou semanal) roda o importador contra homologação; produção exige aprovação.
- Lotes com remoção acima de 5% ficam em `validated` aguardando aprovação no painel.
- Publicação nunca apaga imóveis nem altera carteira/titularidade; casos com dono vão para revisão.
- Para pausar uma região: painel → Regiões → `paused` (o mapa deixa de exibi-la; titularidades permanecem).

## Liberação de região

`draft` → `qa` → `released` (painel → Regiões). Antes de `released`:

1. Lote publicado para a região, sem casos de revisão `open` de categoria `removed_owned` ou `split`.
2. QA de campo com o build `staging` em pelo menos 20 imóveis de categorias diferentes: ponto de interação
   alcançável da calçada, categoria correta, compra com presença aceita e recusada a mais de 80 m.
3. Amostra de 5 imóveis conferida contra o OSM (link "Origem" na ficha do imóvel no painel).
4. Atribuição "© OpenStreetMap contributors" visível no mapa e na ficha.

## Configuração do jogo

Mudanças de parâmetros criam uma nova versão imutável de `game.game_config` (painel → Configuração).
Propostas guardam o snapshot das regras no envio. Nunca edite a tabela diretamente.

## Custos a acompanhar

Plano/compute Supabase, disco e backups, egress, invocações de funções, tiles por sessão, e-mail, runner do
importador, monitoramento e loja. Defina teto operacional e alertas de 50/80/100%.
