# Incidentes

Princípios: nunca editar saldo, razão ou titularidade diretamente. Toda correção é uma operação auditada do
painel (`admin_api.*`), que gera lançamento compensatório e registro em `game.admin_audit`.

## Severidades

| Sev | Exemplo | Resposta |
|---|---|---|
| 1 | Emissão indevida, saldo negativo, dois donos, vazamento de dados | imediata; considerar pausar regiões |
| 2 | Push parado, jobs atrasados > 30 min, importação falhando | mesmo dia |
| 3 | Erro de mapa, categoria errada | fila normal |

## Playbooks

### Divergência de reconciliação (`game.reconciliation_alerts`)
1. A carteira divergente já fica congelada (`WALLET_LOCKED`).
2. Painel → Usuário → extrato: identifique a transação causadora (`ledger_transaction_id`).
3. Se o razão estiver correto e a carteira não: abra incidente Sev 1 (indica bug), corrija via
   `compensating_adjustment` referenciando a transação, rode a reconciliação e descongele.
4. Registre a causa raiz abaixo.

### Fila da outbox parada
1. `select status, count(*), min(created_at) from game.outbox group by 1;`
2. Verifique `cron.job_run_details` do `dv-outbox-dispatch` e os segredos do Vault.
3. Verifique logs do `outbox-worker` (401 = `DV_CRON_SECRET` divergente; erro FCM = credencial).
4. Push atrasado não altera economia; o app sempre reconsulta o estado.

### Compra/proposta com erro `TRANSIENT` frequente
1. Painel → Operação: locks e p95.
2. Verifique contenção em imóvel muito disputado e o tamanho do pool (`max` do postgres.js).
3. O cliente reenvia com a mesma `Idempotency-Key`; não há cobrança dupla.

### Imóvel com dono removido/alterado pela importação
1. Caso em `geo.review_cases` (`removed_owned`, `classification_changed_owned`, ...).
2. Opções: `restore_eligibility`, `keep_suspended`, `verify_entrance` ou `compensate_and_close`
   (devolve ao sistema e credita compensação ao dono, com lançamento de ajuste).

### Suspeita de fraude de localização / conluio
1. Painel → Revisões de risco; sinais em `game.risk_signals` e `game.presence_rejections`.
2. Suspender conta (`set_user_status`) não desfaz negociações; ajustes exigem operação compensatória.

### Restauração de backup
Seguir `docs/operations.md` → Backup e restauração. Nunca reativar jobs antes de reconciliar.

## Registro

| Data | Sev | Resumo | Causa raiz | Ações |
|---|---|---|---|---|
| — | — | — | — | — |
