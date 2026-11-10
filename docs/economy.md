# Economia — simulação e pontos de decisão

O simulador em `tests/economy` é um modelo por agentes, determinístico (semente fixa) e em centavos inteiros,
que reproduz as regras de [rules.md](rules.md) com os parâmetros lidos diretamente da migração
`supabase/migrations/20261103120100_config_catalog.sql`, mais os blocos `$patch$` das migrações seguintes que
publicam novas versões da configuração (hoje, o bônus de inauguração em
`20261110120000_opening_bonus.sql`). Ele não substitui os testes SQL (`tests/database`),
que verificam as regras reais no banco: serve para observar inflação, concentração e efeito de abuso em
30, 90 e 180 dias antes de liberar uma região ou mudar parâmetros.

## Como rodar

```bash
node tests/economy/simulate.mjs --days 30,90,180 --scenario all --seed 42
```

```bash
npm --prefix tests/economy test
```

Saídas em `tests/economy/out/` (ignorado pelo git): `<cenário>-<dias>d.json` com a série diária e
`report.md` com a tabela-resumo. Os testes verificam determinismo, conservação do dinheiro
(emitido = saldos + reservas + tesouro), reservas nunca negativas e o mínimo de +20% em toda compra sem aceite.

## Cenários

- **baseline**: 150 jogadores iniciais, +12 cadastros/dia, 40% abandonam; 3.000 imóveis em 30 células
  (60% casa pequena, 15% casa grande, 20% comercial, 5% prédio residencial). Sem abuso coordenado.
- **abuse**: o orgânico acima mais um jogador que cria 3 contas/dia (e-mails distintos) entre os dias 5 e 60;
  cada conta nova compra uma casa dele a 2,9× o preço da categoria e ele a recompra pelo preço base.

## Resultados (semente 42)

| Cenário | Dia | Ativos | Massa monetária | Massa/ativo | Crescimento/dia (7d) | Top 10 renda | Gini riqueza | Abusador |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
Com o bônus de inauguração (configuração versão 2):

| Cenário | Dia | Ativos | Massa monetária | Massa/ativo | Crescimento/dia (7d) | Top 10 renda | Gini riqueza | Abusador |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| baseline | 30 | 236 | 1.188.358 | 5.035 | 1,7% | 5,6% | 0,136 | — |
| baseline | 90 | 461 | 5.399.794 | 11.713 | 2,2% | 4,9% | 0,249 | — |
| baseline | 180 | 729 | 17.416.419 | 23.891 | 1,0% | 6,2% | 0,401 | — |
| abuse | 30 | 218 | 1.534.518 | 7.039 | 2,1% | 6,9% | 0,201 | 4,1% |
| abuse | 90 | 444 | 6.194.586 | 13.952 | 1,9% | 6,4% | 0,304 | 3,4% |
| abuse | 180 | 767 | 18.172.137 | 23.692 | 1,0% | 6,9% | 0,419 | 2,0% |

Negociações em 180 dias (baseline): 3.000 compras do sistema, 1.213 vendas aceitas, 6.819 compras sem aceite
por silêncio, 1.422 por inatividade; 11.882 propostas vencidas, 2.372 recusadas, 935 substituídas;
2.733 operações com sinal de risco, 45 bloqueadas.

Sem o bônus (versão 1, `loadGameConfig(undefined, { patches: false })`), para comparação:

| Cenário | Dia | Ativos | Massa monetária | Massa/ativo | Crescimento/dia (7d) | Top 10 renda | Gini riqueza | Abusador |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| baseline | 30 | 226 | 1.056.169 | 4.673 | 1,4% | 4,4% | 0,068 | — |
| baseline | 90 | 448 | 3.740.607 | 8.350 | 2,9% | 2,7% | 0,159 | — |
| baseline | 180 | 759 | 14.135.206 | 18.623 | 1,2% | 3,8% | 0,310 | — |
| abuse | 30 | 244 | 1.444.842 | 5.921 | 2,0% | 5,6% | 0,148 | 4,4% |
| abuse | 90 | 449 | 4.546.619 | 10.126 | 2,4% | 4,9% | 0,227 | 3,5% |
| abuse | 180 | 752 | 15.151.537 | 20.148 | 1,1% | 5,4% | 0,347 | 2,1% |

## Bônus de inauguração (decidido)

Problema: com 1%/dia, um jogador novo leva cerca de 100 dias para recuperar o preço de um imóvel e cerca de
70 para dobrar o patrimônio, o que torna o começo lento. Decisão: **+4% ao dia nos 7 primeiros dias de cada
compra do sistema** (28% do preço devolvidos na primeira semana), gravado na titularidade. Só compras do
sistema recebem o bônus, para não estimular revenda em ciclo; zerar ou mudar o valor numa nova versão da
configuração (`opening_bonus_rate`, `opening_bonus_s`) não altera compras já feitas.

Efeito medido no simulador (baseline, semente 42; patrimônio = saldo + preço pago dos imóveis, a partir de 5.000):

| Coorte | Sem bônus | Com bônus |
|---|---:|---:|
| Jogadores ativos do dia 0, no dia 30 | 7.285 (5 imóveis) | 10.532 (7 imóveis) |
| Jogadores casuais do dia 0, no dia 30 | 5.846 (3 imóveis) | 6.769 (3 imóveis) |
| Ativos que entraram nos dias 55–60, no dia 90 | 6.659 | 7.583 |
| Casuais que entraram nos dias 55–60, no dia 90 | 5.996 | 6.822 |

O ganho no primeiro mês fica de 2 a 2,4 vezes maior. Custos e riscos observados:

- **Ocupação mais rápida**: todos os 3.000 imóveis do modelo têm dono no dia 136 (antes, 164). Quem entra depois
  disso não encontra compra do sistema e não recebe bônus nenhum (coorte dos dias 145–150 sem ganho). No mapa real
  há muito mais imóveis por região, mas a liberação de regiões novas passa a ser mais urgente (ver item 3).
- **Concentração**: o Gini de riqueza sobe (0,31 → 0,40 em 180 dias), porque quem compra mais cedo e com mais
  frequência recebe mais bônus. O top 10 da renda permanente passa de 3,8% para 6,2%.
- **Emissão**: o bônus emite cerca de 1,77 milhão em 180 dias (pico de 26 mil/dia); a massa monetária fica 44%
  maior no dia 90 e 23% maior no dia 180.
- **Funil de contas novas**: uma conta nova que gasta os 5.000 em compras do sistema e coleta na primeira semana
  passa a ter até 6.400 para repassar (+28%). O bloqueio de risco não muda (item 1).

Freios possíveis se os números reais confirmarem esses riscos: reduzir para +2%/dia, limitar o bônus às primeiras N
compras do jogador (ex.: bônus de 0,5% após 20 compras) ou só a jogadores com menos de 30 dias. Todos são uma nova
versão da configuração ou uma migração pequena; a tela Economia do console mostra o bônus ativo por dia
(`opening_bonus_run_rate` no relatório diário) ao lado da renda permanente.

## Achados para decisão de produto

Nenhum destes pontos foi alterado no código: são escolhas de desenho do jogo, registradas aqui para decisão.

### 1. Funil de contas novas não atinge o bloqueio de risco

Contas novas recebem 5.000 moedas uma única vez. Para canalizar esse saldo a um jogador, cada conta compra
um imóvel dele acima do preço. A pontuação máxima que esse padrão alcança é **45 pontos**
(`new_account_overpay` +25 e preço > 3× a categoria +20), abaixo do bloqueio em **60**: com 5.000 moedas não
dá para pagar mais de 10× por uma casa pequena (1.000), e a mesma dupla só soma `repeated_pair` se repetir.
O alerta aparece na fila de risco do console, mas a operação não é bloqueada.

No cenário `abuse` o abusador chega a 4,1% da riqueza no dia 30 (acima do p99 dos jogadores orgânicos) e
cai para 2,0% em 180 dias, diluído pela renda do restante do mercado. O teste
`funil de conta nova soma no máximo 45 pontos` documenta essa limitação.

Opções (uma ou mais):

- liberar o saldo inicial de forma gradual (ex.: 1.000 por dia nos primeiros 5 dias);
- usar um limiar de bloqueio menor para contas com menos de N dias (ex.: 40);
- limitar o valor de propostas de contas novas a um múltiplo do preço de referência do imóvel;
- manter como está e tratar pela fila de risco (revisão humana), aceitando o vazamento.

### 2. A compra sem aceite por silêncio domina as transferências

Com donos casuais (que não abrem o app todo dia), cerca de 7 em cada 10 transferências entre jogadores são compras
sem aceite após 24 h sem resposta (6.819 em 180 dias), contra 1.213 vendas aceitas. Isso é coerente com a regra (+20% pagos ao dono), mas
muda a percepção do jogo de "negociar" para "tomar". Alavancas possíveis: janela de resposta maior (ex.: 48 h),
notificação push com lembrete antes do vencimento (já existe o envio no vencimento), ou prêmio maior que 20%.

### 3. Inflação por renda composta

A renda de 1%/dia sobre o preço pago é a principal fonte de emissão, e o tesouro (compras do sistema) é o único
sumidouro. Quando todos os imóveis da região já têm dono (dia 136 no baseline com o bônus; 164 sem ele), o
sumidouro para e a massa por jogador ativo cresce de 1% a 2% ao dia; o preço médio das vendas sobe de 1,4× (dia 90)
para 2,8× (dia 180) o preço da categoria.
Opções: liberar novas regiões conforme a ocupação (o que cria novo sumidouro), taxa sobre transferências,
renda decrescente para portfólios grandes, ou teto de renda acumulada. O relatório diário
`game_internal.economy_daily_report` e a tela Economia do console acompanham estas métricas em produção.

## Limitações do modelo

- Passo de 1 dia: não reproduz corridas entre jogadores no mesmo minuto (cobertas por `tests/database`).
- Comportamento dos agentes é estilizado (frequência de acesso, agressividade, abandono) e não calibrado com
  dados reais; recalibrar com os dados do piloto antes de decisões finais.
- Não modela GPS, proteção por região pausada, suspensões nem compensações administrativas.
