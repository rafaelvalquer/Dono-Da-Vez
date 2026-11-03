# Economia — simulação e pontos de decisão

O simulador em `tests/economy` é um modelo por agentes, determinístico (semente fixa) e em centavos inteiros,
que reproduz as regras de [rules.md](rules.md) com os parâmetros lidos diretamente da migração
`supabase/migrations/20261103120100_config_catalog.sql`. Ele não substitui os testes SQL (`tests/database`),
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
| baseline | 30 | 226 | 1.056.169 | 4.673 | 1,4% | 4,4% | 0,068 | — |
| baseline | 90 | 448 | 3.740.607 | 8.350 | 2,9% | 2,7% | 0,159 | — |
| baseline | 180 | 759 | 14.135.206 | 18.623 | 1,2% | 3,8% | 0,310 | — |
| abuse | 30 | 244 | 1.444.842 | 5.921 | 2,0% | 5,6% | 0,148 | 4,4% |
| abuse | 90 | 449 | 4.546.619 | 10.126 | 2,4% | 4,9% | 0,227 | 3,5% |
| abuse | 180 | 752 | 15.151.537 | 20.148 | 1,1% | 5,4% | 0,347 | 2,1% |

Negociações em 180 dias (baseline): 3.000 compras do sistema, 1.012 vendas aceitas, 5.783 compras sem aceite
por silêncio, 1.401 por inatividade; 10.378 propostas vencidas, 1.912 recusadas, 804 substituídas;
1.586 operações com sinal de risco, 22 bloqueadas.

## Achados para decisão de produto

Nenhum destes pontos foi alterado no código: são escolhas de desenho do jogo, registradas aqui para decisão.

### 1. Funil de contas novas não atinge o bloqueio de risco

Contas novas recebem 5.000 moedas uma única vez. Para canalizar esse saldo a um jogador, cada conta compra
um imóvel dele acima do preço. A pontuação máxima que esse padrão alcança é **45 pontos**
(`new_account_overpay` +25 e preço > 3× a categoria +20), abaixo do bloqueio em **60**: com 5.000 moedas não
dá para pagar mais de 10× por uma casa pequena (1.000), e a mesma dupla só soma `repeated_pair` se repetir.
O alerta aparece na fila de risco do console, mas a operação não é bloqueada.

No cenário `abuse` o abusador chega a 4,4% da riqueza no dia 30 (acima do p99 dos jogadores orgânicos) e
cai para 2,1% em 180 dias, diluído pela renda do restante do mercado. O teste
`funil de conta nova soma no máximo 45 pontos` documenta essa limitação.

Opções (uma ou mais):

- liberar o saldo inicial de forma gradual (ex.: 1.000 por dia nos primeiros 5 dias);
- usar um limiar de bloqueio menor para contas com menos de N dias (ex.: 40);
- limitar o valor de propostas de contas novas a um múltiplo do preço de referência do imóvel;
- manter como está e tratar pela fila de risco (revisão humana), aceitando o vazamento.

### 2. A compra sem aceite por silêncio domina as transferências

Com donos casuais (que não abrem o app todo dia), cerca de 7 em cada 10 transferências entre jogadores são compras
sem aceite após 24 h sem resposta (5.783 em 180 dias), contra 1.012 vendas aceitas. Isso é coerente com a regra (+20% pagos ao dono), mas
muda a percepção do jogo de "negociar" para "tomar". Alavancas possíveis: janela de resposta maior (ex.: 48 h),
notificação push com lembrete antes do vencimento (já existe o envio no vencimento), ou prêmio maior que 20%.

### 3. Inflação por renda composta

A renda de 1%/dia sobre o preço pago é a principal fonte de emissão, e o tesouro (compras do sistema) é o único
sumidouro. Quando todos os imóveis da região já têm dono (dia 165 no baseline), o sumidouro para e a massa
por jogador ativo cresce de 1% a 3% ao dia; o preço médio das vendas sobe de 1,6× para 2,1× o preço da categoria.
Opções: liberar novas regiões conforme a ocupação (o que cria novo sumidouro), taxa sobre transferências,
renda decrescente para portfólios grandes, ou teto de renda acumulada. O relatório diário
`game_internal.economy_daily_report` e a tela Economia do console acompanham estas métricas em produção.

## Limitações do modelo

- Passo de 1 dia: não reproduz corridas entre jogadores no mesmo minuto (cobertas por `tests/database`).
- Comportamento dos agentes é estilizado (frequência de acesso, agressividade, abandono) e não calibrado com
  dados reais; recalibrar com os dados do piloto antes de decisões finais.
- Não modela GPS, proteção por região pausada, suspensões nem compensações administrativas.
