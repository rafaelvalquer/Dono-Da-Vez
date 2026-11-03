# Painel administrativo

Console web (React + Vite) para suporte, operação geográfica e financeira. Fala **somente** com a Edge
Function `admin` (`supabase/functions/admin`) usando o JWT do operador; não há credencial privilegiada
no navegador.

## Segurança

- **Nunca** coloque a service role, URLs de banco ou chaves privadas em `VITE_*`: tudo que começa com
  `VITE_` vai para o bundle público. Só a URL do projeto e a chave publicável.
- O servidor exige **MFA (TOTP, `aal2`)** em todas as rotas. O primeiro acesso cadastra o autenticador.
- Papéis (`support`, `geo`, `finance`, `superadmin`) ficam em `game.admin_roles` e são verificados no
  banco a cada chamada; o menu só esconde o que o servidor recusaria.
- Toda mutação exige motivo (≥ 3 caracteres) e entra em `game.admin_audit`. Mutações econômicas e de
  elegibilidade enviam `Idempotency-Key`; o diálogo mantém a mesma chave enquanto aberto, então repetir
  após erro de rede não duplica a operação.
- Saldo nunca é editado: correções são **ajustes compensatórios** no razão.
- Sessão em `sessionStorage` (some ao fechar a aba) e saída automática após 15 min sem interação.
- A origem do painel precisa estar em `DV_ADMIN_ORIGINS` (secret da função `admin`) para o CORS.

### Cabeçalhos do host estático

O build injeta a CSP como `<meta>`, mas `frame-ancestors` só vale como cabeçalho. Configure no host
(ex.: Cloudflare Pages `_headers`, Netlify, Nginx):

```
Content-Security-Policy: default-src 'self'; connect-src 'self' https://<projeto>.supabase.co; img-src 'self' data:; style-src 'self' 'unsafe-inline'; script-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'
X-Content-Type-Options: nosniff
Referrer-Policy: no-referrer
Strict-Transport-Security: max-age=63072000; includeSubDomains
Permissions-Policy: geolocation=(), camera=(), microphone=()
```

Restrinja o acesso por rede (Cloudflare Access, VPN ou allowlist de IP) além do login.

## Desenvolvimento

Requer Node ≥ 22.12 e o backend local (`supabase start` + `supabase functions serve`, ver `docs/setup.md`).

```bash
cp .env.example .env.local   # preencha com os valores públicos de `supabase status`
npm ci
npm run dev                  # http://127.0.0.1:5173
```

Conceder o primeiro papel (somente no banco local ou por um superadmin existente):

```sql
insert into game.admin_roles (user_id, role, granted_by)
values ('<uuid do usuário>', 'superadmin', '<uuid do usuário>');
```

## Verificação

```bash
npm run typecheck
npm test
npm run build
```

## Telas

| Área | Papéis | O que faz |
| --- | --- | --- |
| Operação | todos | Filas, outbox, reservas órfãs, rejeições de GPS, emissão em 24 h |
| Buscar / Jogador | support, finance | Conta, carteira, razão, posses, propostas, sinais de risco; suspender; ajuste; descongelar |
| Imóvel | support, geo, finance | Origem OSM, posses, negociações, casos; elegibilidade; classificação (geo) |
| Revisão geográfica | geo (compensação: finance) | Casos do importador e de relatos |
| Relatos | support, geo | Protocolos `DV-000123` com resposta ao jogador |
| Propostas | support, finance | Lista e cancelamento operacional |
| Risco | finance, support | Aprovar/recusar negociações bloqueadas |
| Conciliação | finance, support | Alertas e execução sob demanda |
| Importações / Regiões | geo | Aprovar, publicar ou rejeitar lotes; liberar ou pausar regiões |
| Economia | finance | Relatório diário agregado |
| Configuração | finance (leitura), superadmin | Nova versão imutável de parâmetros |
| Auditoria | superadmin | Trilha completa com antes/depois |
