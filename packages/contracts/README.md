# packages/contracts

Contrato público entre o app e a Edge Function `api`.

- `openapi.yaml`: OpenAPI 3.1 de todas as rotas `/v1/*`; é a fonte da verdade para os DTOs do Android.
- `schemas/error-codes.json`: catálogo de códigos de erro, status HTTP e conduta esperada do cliente.
- `schemas/push-payload.schema.json`: campo `data` das notificações push.
- `examples/`: respostas de exemplo (dados fictícios do seed local) usadas na documentação e nos testes de contrato.

Regras que o cliente deve respeitar: dinheiro como string decimal; uma `Idempotency-Key` por intenção do
usuário; nunca enviar `user_id`; usar `server_time` para prazos. Detalhes em `docs/rules.md`.

Validação (também roda no CI):

```bash
npx --yes @redocly/cli@1.34.5 lint packages/contracts/openapi.yaml
```
