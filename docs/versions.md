# Versões fixadas (verificadas em 2026-11-03)

| Área | Componente | Versão |
|---|---|---|
| Android | Android Gradle Plugin | 9.4.1 |
| Android | Kotlin | 2.4.20 |
| Android | compileSdk / targetSdk / minSdk | 37 / 36 / 26 |
| Android | MapLibre Native Android | 13.7.1 |
| Android | firebase-messaging | 26.0.0 (`register()` / `onRegistered`) |
| Android | JDK de build | 21 (JBR ou Temurin) |
| Console | React / React DOM | 19.3.1 |
| Console | Vite / @vitejs/plugin-react | 8.3.4 / 6.1.2 |
| Console | TypeScript | 6.0.3 |
| Console | Vitest | 5.0.5 |
| Console | @supabase/supabase-js | 2.118.0 |
| Edge Functions | Deno | 2.9.6 |
| Edge Functions | postgres.js / jose / zod | 3.4.9 / 6.1.0 / 3.25.76 |
| Backend | Supabase CLI (CI) | 2.122.0 (a 3.0.0 saiu em 2026-11-02; avaliar antes de migrar) |
| Backend | PostgreSQL (local) | 17 |
| Importador | Python | 3.12 (imagem `python:3.12.12-slim-bookworm`) |
| Importador | osmium (pyosmium) / shapely / pyproj / psycopg | 4.3.2 / 2.2.0 / 3.8.0 / 3.3.6 |
| Importador | pytest | 9.1.1 |
| Testes | @electric-sql/pglite / pglite-postgis / pglite-socket | 0.5.10 / 0.2.10 / 0.2.13 |
| Testes | pg | 8.23.1 |
| Testes | Node | 22 (≥ 22.12) |
| CI | actions/checkout, setup-node, setup-python, upload-artifact | v7 |
| CI | actions/setup-java, gradle/actions | v6 |
| CI | supabase/setup-cli, denoland/setup-deno | v3, v2 |

## Notas

- **npm**: use npm 11 (`npx -y npm@11 install`). O npm 10.9.2 que vem com o Node 22.17 falha com erro de
  *arborist* (`edgesOut`) nestes pacotes.
- **pglite-socket** precisa da versão pareada com o PGlite (0.2.13 ↔ 0.5.10).
- Ao atualizar qualquer item, rode `tests/database`, `tests/e2e` e o build Android antes de mudar esta tabela.
