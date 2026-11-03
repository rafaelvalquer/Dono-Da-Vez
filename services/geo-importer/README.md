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
7. Vínculo de POIs: vértice explícito → contenção → proximidade (≤ 8 m). Um POI vira no máximo um imóvel.
8. Classificação determinística (`dv_importer/classify.py`, `RULES_VERSION`), porte por área construída estimada
   (≤ 150 m² = casa pequena), uso misto e casos ambíguos ficam pendentes.
9. Ponto de interação: entrada pública mapeada → borda voltada para a via (≤ 60 m) → centroide (em revisão).
10. Relatório de cobertura, rejeições e ambiguidades; `geo_api.validate_batch` calcula o diff e aprova
    automaticamente se a remoção for ≤ 5% e não houver fusões; senão aguarda o painel.
11. `geo_api.publish_batch` publica a região de uma vez (sem estado intermediário visível).

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
