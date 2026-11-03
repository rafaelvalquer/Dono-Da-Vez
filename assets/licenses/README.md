# Licenças e atribuições

## Assets próprios

Avatares (`assets/avatars`), ícones (`assets/icons`), sprites de estado (`assets/sprites`), ícone do app e os
VectorDrawables equivalentes no Android são **originais** do Dono da Vez, gerados por formas geométricas em
`assets/tools/generate-assets.mjs`. Nenhum arquivo de outro jogo ou banco de imagens foi copiado, traçado ou
adaptado. Para mudar a identidade visual, edite as definições nesse script e rode-o de novo, sem colar arquivos
externos.

## Dados geográficos

| Fonte | Licença | Atribuição obrigatória | Onde aparece |
|---|---|---|---|
| OpenStreetMap (imóveis, endereços, entradas) | [ODbL 1.0](https://opendatacommons.org/licenses/odbl/1-0/) | "© colaboradores do OpenStreetMap" | `game.regions.attribution` (bootstrap), canto do mapa e tela Perfil → Sobre |
| Esquema OpenMapTiles (mapa base) | [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) (esquema) | "© OpenMapTiles" | atribuição do estilo base, junto com a do fornecedor |
| Fornecedor de tiles vetoriais contratado | contrato do fornecedor | conforme o contrato | atribuição do estilo base |

O banco de imóveis importado é uma **obra derivada** do OSM. Se for redistribuído fora do app (exportação,
API pública), precisa sair sob ODbL. Os tiles de `tile.openstreetmap.org` não podem ser usados no app, pela
política de uso dos tiles.

## Fontes tipográficas do mapa

O estilo base usa Noto Sans (SIL Open Font License 1.1), servida pelos glifos do fornecedor de tiles.

## Bibliotecas principais

| Componente | Licença |
|---|---|
| MapLibre Native Android | BSD-2-Clause |
| Jetpack Compose, AndroidX, Kotlin, kotlinx.serialization, kotlinx.coroutines | Apache-2.0 |
| Firebase Cloud Messaging SDK | Apache-2.0 (termos de serviço do Firebase para o serviço) |
| supabase-js, React, Vite | MIT |
| postgres.js (Edge Functions) | Unlicense |
| jose, zod | MIT |
| pyosmium | BSD-2-Clause |
| osmium-tool | GPL-3.0 (executável separado na imagem do importador, sem ligação ao código) |
| Shapely, pyproj, psycopg | BSD-3-Clause, MIT, LGPL-3.0 |
| PGlite (testes) | Apache-2.0 / PostgreSQL License |

A lista completa e as versões fixadas estão em [docs/versions.md](../../docs/versions.md). O app deve exibir os
avisos de licença das dependências na tela Sobre antes da publicação na loja.
