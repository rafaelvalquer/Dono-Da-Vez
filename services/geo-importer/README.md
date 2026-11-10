# geo-importer

Pipeline de importação OSM → `geo.staging_properties` → validação → publicação atômica (`geo_api.*`).
O importador **nunca** altera carteiras, preços pagos ou titularidades; roda com o papel `dv_importer`, que só
escreve em `geo.staging_*` e executa funções `geo_api`.

## Etapas

1. Região: `geo_api.upsert_region` (polígono ≤ 50 km²; região liberada não muda de polígono sem o painel).
2. Download do extrato (Geofabrik) com retomada, SHA-256 em fluxo e conferência do `.md5` publicado.
3. `geo_api.begin_batch`: o mesmo arquivo com as mesmas regras já publicado é ignorado (idempotente).
4. Recorte com `osmium extract --strategy smart` e leitura em fluxo com pyosmium.
5. Somente tags de interesse: `building`, `shop`, `amenity`, `office`, `craft`, `entrance`, `addr:*`, vias.
6. Reparo de geometrias (`make_valid`); irrecuperáveis vão para `geo.staging_rejects`.
7. Vínculo de POIs: vértice explícito → contenção → proximidade (≤ 8 m). Um POI vira no máximo um imóvel. É feito
   sobre as construções originais, antes de juntar anexos, para o contorno maior de um vizinho não levar a loja.
8. Anexos (`dv_importer/annex.py`): pedaço com menos de 40 m² sem uso declarado (`building=yes/garage/garages/
   shed/carport/roof`, sem POI vinculado, sem número próprio, sem comércio ou instituição) que encosta (≤ 1 m) num
   prédio de 40 m² ou mais vira **parte** dele: o desenho cresce, mas a categoria e o preço seguem pelo prédio
   principal. Pedaços pequenos encostados só entre si (2 a 10) viram um imóvel se juntos somarem 40 m²; o maior
   pedaço é a referência. Pedaço isolado (ou grupo pequeno demais) é rejeitado (`too_small_isolated`); conjuntos
   com mais de 10 pedaços ficam como estão. O anexo entra em `source_refs` com papel `part`.
9. Classificação determinística (`dv_importer/classify.py`, `RULES_VERSION`), porte por área construída estimada
   (≤ 150 m² = casa pequena), uso misto e casos ambíguos ficam pendentes.
   Regras v2 — categoria **estimada** (`classification_status = inferred`, negociável, com aviso na ficha) quando o
   OSM não declara o uso:
   - `building=yes` ou `residential` baixo (até 2 andares, ou altura < 8 m) com 40 a 250 m² vira casa; o porte
     usa a área × andares (andares declarados ou altura ÷ 3 m). Confiança 0,55 (0,5 perto do limite de 150 m²).
   - `building=residential` com 5 andares ou mais (ou altura ≥ 14 m) e 150 m² ou mais vira prédio residencial
     (confiança 0,6).
   - `building=yes` alto continua indefinido: pode ser escritório, hotel ou galpão.
10. Ponto de interação: entrada pública mapeada → borda voltada para a via (≤ 60 m) → centroide (em revisão).
11. Relatório de cobertura, rejeições e ambiguidades; `geo_api.validate_batch` calcula o diff e aprova
    automaticamente se a remoção for ≤ 5% e não houver fusões; senão aguarda o painel. Anexos juntados contam
    à parte (`absorbed`), não como remoção.
12. `geo_api.publish_batch` publica a região de uma vez (sem estado intermediário visível). O imóvel antigo de um
    anexo juntado (sempre sem dono) sai do mapa (`removed_from_source`, "anexo juntado a outro imóvel") e suas
    referências passam para o vizinho; o histórico fica em `geo.property_changes` (`absorbed`). Anexo com dono
    nunca é juntado: vira caso de fusão para revisão, sem tocar posse, carteira ou preço pago.

## Uso

```bash
docker build -t dv-geo-importer services/geo-importer
```

Cobertura de um extrato, sem banco (para escolher o polígono piloto):

```bash
docker run --rm -v "$PWD/data:/data" dv-geo-importer coverage --pbf /data/sudeste-latest.osm.pbf --region-geojson /app/regions/sp-pinheiros-vila-madalena.geojson --out /data/report
```

Importação (homologação/local):

```bash
docker run --rm -e DV_IMPORTER_DB_URL dv-geo-importer import --region sp-pinheiros-vila-madalena --region-geojson /app/regions/sp-pinheiros-vila-madalena.geojson --url https://download.geofabrik.de/south-america/brazil/sudeste-latest.osm.pbf
```

Testes:

```bash
docker run --rm --entrypoint pytest dv-geo-importer -q
```

`tests/test_db_integration.py` roda só com `DV_TEST_IMPORTER_DB_URL` apontando para um banco local/CI.
A fixture `fixtures/sample.osm` é fictícia (gerada por `fixtures/generate-sample.js`) e serve apenas a testes.

## Licença dos dados

Dados © colaboradores do OpenStreetMap, licença ODbL. A atribuição é gravada na região e exibida no mapa.
