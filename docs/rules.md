# Regras do jogo (V1)

Parâmetros vivem em `game.game_config` (versões imutáveis; nova versão via painel). Valores da versão 1:

## Economia

| Regra | Valor |
|---|---|
| Saldo inicial | 5000.00, uma única vez por conta **e** por e-mail confirmado (hash em `game.grant_registry`) |
| Casa pequena (≤ 150 m² de área construída estimada) | 1000.00 |
| Casa grande | 2500.00 |
| Comércio | 3000.00 |
| Prédio residencial (o prédio inteiro é um imóvel) | 10000.00 |
| Rendimento | 1% ao dia sobre o **preço efetivamente pago**, contínuo e proporcional ao tempo |
| Precisão | `numeric(24,8)`; JSON sempre em string; ofertas com no máximo 2 casas |

- Não existe job que credita 1% ao dia: o acumulado é calculado sob demanda e só vira saldo ao coletar
  (centavos inteiros; a fração continua acumulando) ou ao vender (liquidado até o segundo da transferência).
- Razão de partidas dobradas: cada transação soma zero (gatilho diferido); lançamentos são imutáveis.
  Emissão (saldo inicial e rendimento) sai da conta `issuance`; compras do sistema vão para `treasury`.

## Compra do sistema

Imóvel sem dono, elegível, em região liberada. Exige prova de presença válida e saldo disponível
(saldo − reservado). O preço é o da categoria.

## Presença

- Distância ≤ **80 m** do ponto de interação (entrada mapeada, borda voltada para a rua ou, em último caso,
  centroide — este último deixa o imóvel em revisão até verificação).
- Precisão do GPS ≤ **30 m**; leitura com no máximo **15 s**; velocidade compatível (≤ 15 km/h para negociar;
  salto > 300 km/h é suspeito); localização simulada recusada fora de ambientes de teste.
- O servidor emite `proof_id` válido por **30 s**, de uso único, vinculado ao imóvel e ao jogador.
- Coordenadas brutas ficam no máximo 7 dias (auditoria) e nunca aparecem em logs.

## Proposta voluntária

- Presencial. Valor livre (≥ 0.01), **reservado integralmente** no envio.
- Uma proposta pendente por comprador e imóvel; máximo de 10 abertas por comprador; após recusa, 24 h de
  espera para propor de novo ao mesmo imóvel.
- O dono tem **24 h** para aceitar ou recusar (aceite não exige presença). Aceite transfere atomicamente:
  liquida o rendimento do vendedor, consome a reserva, paga o vendedor, encerra e abre titularidades e encerra
  as demais propostas do imóvel (reservas liberadas).
- Recusa ou cancelamento liberam a reserva.

## Compra sem aceite (+20%)

Duas condições independentes, ambas presenciais e com preço = `arredondar_para_cima_centavos(último preço
pago × 1.20)`:

1. **Silêncio**: a proposta do comprador venceu sem resposta. Janela de 24 h após o vencimento.
2. **Inatividade**: o dono não teve atividade qualificada (app aberto em primeiro plano) por **14 dias**.
   Reavaliada no commit, com bloqueio da linha de atividade do dono.

Bloqueada por **24 h após qualquer transferência** do imóvel (proteção). O valor é creditado ao antigo dono,
mesmo offline.

## Máquina de estados da proposta

`pending → accepted | rejected | cancelled | expired | superseded`; `expired → executed_without_acceptance`
(dentro da janela). Uma proposta pendente vencida já é exibida como `expired` antes do job materializar.

## Antiabuso

Pontuação de risco por negociação (ciclo entre o mesmo par +40, repetição do par +30, preço acima de 3× a
categoria +20 / 10× +40, conta nova pagando caro +25, alta velocidade de negociações +20). Pontuação ≥ 60
bloqueia e abre revisão no painel; nada é debitado. Revisões aprovadas liberam a mesma negociação.

## Conta

- Exclusão: exige login recente (≤ 5 min) e confirmação `EXCLUIR`. Propostas são canceladas, reservas
  liberadas, imóveis devolvidos ao sistema (sem pagamento), perfil pseudonimizado; razão preservado.
- Push e e-mail nunca são condição para efeito econômico.
