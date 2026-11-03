# São Paulo — mapa 3D e importação automática de imóveis

## Escopo e limitações

O jogo cobre **somente o município de São Paulo (IBGE 3550308)**, não o estado de SP.
Os imóveis são construídos automaticamente a partir dos edifícios/POIs mapeados no OSM.
O OSM **não possui todos os imóveis físicos**: não inventar imóveis ausentes.
Itens incertos ou sem acesso ficam indisponíveis até revisão.

No Android, localDebug usa OpenFreeMap Bright (tiles vetoriais, ruas e quadras reais,
internet obrigatória) com sobreposição de imóveis coloridos, contornos e extrusões 3D.
Os nomes e os ícones de comércios, estacionamentos e demais POIs do mapa-base são ocultados
ao carregar o estilo; não são propriedades compráveis e confundiam os jogadores.
A regra só oculta símbolos de POI do provedor, preservando ruas, nomes das vias,
edificações e todas as camadas próprias do jogo (dv-*), incluindo avatar e imóveis.
Esta limpeza é apenas visual e não modifica a base de dados do OpenStreetMap nem o Supabase.
Não utiliza tiles de openstreetmap.org. A atribuição OSM/OpenMapTiles deve permanecer visível.
É possível substituir o estilo com a propriedade Gradle dvLocalMapStyleUrl.

O zoom mínimo é 15.5, máximo 19.5. A câmera fica limitada a um retângulo
aproximado do município. O servidor utiliza as regiões oficiais para decidir imóveis negociáveis.
O app baixa no máximo 500 imóveis por viewport (250 por página, até duas páginas);
se a área exceder esse número, o jogador deve aproximar o mapa.
A compra ainda exige presença física validada pelo backend.

## Rodar no Windows com Supabase local

Pré-requisitos: Docker Desktop, Supabase local com migrations e seed aplicados.
Não execute estes comandos em produção.

1. Abra a branch e compile o importador:

~~~powershell
cd D:\Projetos\Dono-Da-Vez
git fetch origin
git switch feature/sao-paulo-mapa-imoveis-3d
docker build -t dv-geo-importer services/geo-importer
mkdir data -Force
~~~

2. Gerar os polígonos do município, sem modificar banco:

~~~powershell
docker run --rm -v "${PWD}\data:/data" dv-geo-importer import-city --workdir /data/sp-city --dry-run
~~~

3. Importar inicialmente **3 células próximas ao centro**:

~~~powershell
docker run --rm -v "${PWD}\data:/data" -e "DV_IMPORTER_DB_URL=postgresql://dv_importer:dv_importer_local@host.docker.internal:54322/postgres" dv-geo-importer import-city --workdir /data/sp-city --max-cells 3
~~~

O primeiro comando efetivo baixa o extrato Sudeste da Geofabrik, busca a malha municipal no
IBGE e faz um recorte municipal uma única vez. Os arquivos podem exigir vários GB em disco.
A importação pode ser demorada. O resultado está em data/sp-city/city-report.json.
O script recorta células de até 16 km² (limite do banco: 50 km²).
Se algum lote ficar em validated, a liberação exige revisão administrativa.

4. Liberar as células publicadas no **Supabase local** e pausar os imóveis fictícios:

~~~powershell
$db = docker ps --format "{{.Names}}" | Where-Object { $_ -eq "supabase_db_dono-da-vez" }
if (-not $db) { throw "Container PostgreSQL local não encontrado" }
Get-Content .\scripts\local\release-sao-paulo.sql -Raw | docker exec -i $db psql -U postgres -d postgres -v ON_ERROR_STOP=1
~~~

Reinicie o aplicativo ou refaça o login. Localização de teste perto do centro:

~~~powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" emu geo fix -46.6333 -23.5505
~~~

5. Após validar o piloto, importar o **restante da cidade** e rodar novamente o SQL de liberação:

~~~powershell
docker run --rm -v "${PWD}\data:/data" -e "DV_IMPORTER_DB_URL=postgresql://dv_importer:dv_importer_local@host.docker.internal:54322/postgres" dv-geo-importer import-city --workdir /data/sp-city
~~~

Reexecuções são idempotentes em lotes já publicados; compras e titulares não são apagados.
O diretório de trabalho persiste o contorno, downloads, recortes e relatórios.
Atualizações de geometria que exigem aprovação permanecem pendentes, sem publicação forçada.
Para staging/produção, utilizar fluxos administrativos autorizados, nunca o SQL de desenvolvimento.

## Testes

~~~powershell
docker run --rm --entrypoint pytest dv-geo-importer -q
cd apps\android
.\gradlew.bat testLocalDebugUnitTest
.\gradlew.bat :app:assembleLocalDebug
~~~

## Critérios de aceite

- Zoom não permite visualizar o estado inteiro; câmera permanece na região SP.
- Mapa real carrega com acesso à internet e não oculta atribuição cartográfica.
- No máximo 500 imóveis por carregamento, com aviso de resultado truncado.
- Piloto cria imóveis automaticamente, dentro dos limites municipais do IBGE.
- Apenas regiões publicadas e liberadas permitem compra; o seed fictício é pausado.
- Reimportação não perde propriedade nem altera preço pago.
