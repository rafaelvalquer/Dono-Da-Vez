// Gera o estilo hospedado de um ambiente a partir de assets/styles/dono-da-vez.json, preenchendo a fonte vetorial
// (TileJSON OpenMapTiles) e os glifos do fornecedor contratado. A chave pública do fornecedor deve ser restrita
// por pacote/assinatura Android; o arquivo gerado não é versionado.
//
// node assets/tools/render-style.mjs --tilejson "https://<fornecedor>/tiles.json?key=<chave>" \
//   --glyphs "https://<fornecedor>/fonts/{fontstack}/{range}.pbf?key=<chave>" --out build/style/dono-da-vez.json
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";

const root = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const { values } = parseArgs({
  options: {
    tilejson: { type: "string" },
    glyphs: { type: "string" },
    out: { type: "string", default: join(root, "build", "style", "dono-da-vez.json") },
  },
});

for (const [name, v] of [["--tilejson", values.tilejson], ["--glyphs", values.glyphs]]) {
  if (!v || !/^https:\/\//.test(v)) {
    console.error(`${name} é obrigatório e precisa ser https`);
    process.exit(2);
  }
}
if (!values.glyphs.includes("{fontstack}") || !values.glyphs.includes("{range}")) {
  console.error("--glyphs precisa conter {fontstack} e {range}");
  process.exit(2);
}
if (/tile\.openstreetmap\.org/.test(values.tilejson)) {
  console.error("tiles do openstreetmap.org não podem ser usados em apps (política de uso); contrate um fornecedor");
  process.exit(2);
}

const style = JSON.parse(readFileSync(join(root, "assets", "styles", "dono-da-vez.json"), "utf8"));
style.sources.openmaptiles.url = values.tilejson;
style.glyphs = values.glyphs;
const text = JSON.stringify(style, null, 2);
if (text.includes("{tilejson_url}") || text.includes("{glyphs_url}")) throw new Error("marcador não substituído");

mkdirSync(dirname(values.out), { recursive: true });
writeFileSync(values.out, text + "\n");
console.log(`ok: ${values.out} (${style.layers.length} camadas)`);
