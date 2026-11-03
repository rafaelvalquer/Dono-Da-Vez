// Gera sample.osm e sample-region.geojson (dados de teste fictícios). Uso: node generate-sample.js <dir>
const fs = require("fs");
const out = process.argv[2];
const LON0 = -46.69, LAT0 = -23.57, MX = 1 / 102050, MY = 1 / 110800;
const ll = (x, y) => [+(LON0 + x * MX).toFixed(7), +(LAT0 + y * MY).toFixed(7)];
const nodes = []; const ways = []; const rels = [];
let nid = 1;
const esc = (s) => String(s).replace(/&/g, "&amp;").replace(/"/g, "&quot;").replace(/</g, "&lt;");
function node(x, y, tags = {}, id = nid++) { const [lon, lat] = ll(x, y); nodes.push({ id, lon, lat, tags }); return id; }
function way(id, pts, tags = {}, inserts = {}) {
  const ids = [];
  pts.forEach(([x, y], i) => { ids.push(node(x, y)); if (inserts[i]) ids.push(inserts[i]); });
  ids.push(ids[0]);
  ways.push({ id, nodes: ids, tags });
}
function rect(id, x0, y0, x1, y1, tags, inserts = {}) { way(id, [[x0, y0], [x1, y0], [x1, y1], [x0, y1]], tags, inserts); }

// Rua (única) ao longo de y = 0.
const street = []; for (let x = -200; x <= 200; x += 50) street.push(node(x, 0));
ways.push({ id: 301, nodes: street, tags: { highway: "residential", name: "Rua Fixture" } });

// Entrada privada como vértice da casa 101 (não deve ser usada).
const privateEntrance = node(-95, 5, { entrance: "yes", access: "private" }, 2002);
rect(101, -100, 5, -90, 15, { building: "house", "addr:street": "Rua Fixture", "addr:housenumber": "10" }, { 0: privateEntrance });
const mainEntrance = node(-72.5, 5, { entrance: "main" }, 2001);
rect(102, -80, 5, -65, 19, { building: "detached" }, { 0: mainEntrance });
rect(103, -50, 5, -20, 25, { building: "apartments", "building:levels": "12", "building:flats": "48" });
rect(104, -10, 5, 5, 17, { building: "yes" });
node(-2.5, 11, { shop: "bakery", name: "Padaria Fixture" }, 3001);
rect(105, 10, 5, 20, 15, { building: "yes" });
node(15, 10, { "addr:street": "Rua Fixture", "addr:housenumber": "22" }, 4001);
rect(106, 25, 5, 35, 15, { building: "residential" });
rect(107, 40, 5, 60, 25, { building: "apartments" });
node(50, 15, { shop: "convenience" }, 3002);
node(70, -10, { amenity: "cafe", name: "Café Fixture" }, 3003);
rect(108, -100, 150, -90, 160, { building: "house" });
way(109, [[80, 5], [90, 15], [90, 5], [80, 15]], { building: "house" }); // anel autointersectado
rect(110, -120, 5, -117, 8, { building: "house" });
rect(111, -140, 5, -125, 15, { building: "commercial" });
node(-132.5, 10, { office: "company" }, 3004);
rect(112, -50, 40, -20, 60, { building: "school", amenity: "school" });
rect(113, -10, 40, 0, 46, { building: "garage" });
rect(201, 100, 5, 130, 25, {});
rect(202, 110, 12, 120, 18, {});
rels.push({ id: 5001, members: [["way", 201, "outer"], ["way", 202, "inner"]],
            tags: { type: "multipolygon", building: "retail", shop: "clothes", name: "Loja Fixture" } });

const tagXml = (t) => Object.entries(t).map(([k, v]) => `    <tag k="${esc(k)}" v="${esc(v)}"/>\n`).join("");
let xml = `<?xml version="1.0" encoding="UTF-8"?>\n<!-- Fixture de teste do importador: dados fictícios, não representam imóveis reais. -->\n<osm version="0.6" generator="dv-fixture">\n`;
for (const n of nodes.sort((a, b) => a.id - b.id)) {
  const t = tagXml(n.tags);
  xml += `  <node id="${n.id}" version="1" lat="${n.lat}" lon="${n.lon}"${t ? `>\n${t}  </node>` : "/>"}\n`;
}
for (const w of ways.sort((a, b) => a.id - b.id)) {
  xml += `  <way id="${w.id}" version="1">\n${w.nodes.map((r) => `    <nd ref="${r}"/>\n`).join("")}${tagXml(w.tags)}  </way>\n`;
}
for (const r of rels) {
  xml += `  <relation id="${r.id}" version="1">\n${r.members.map(([t, id, role]) => `    <member type="${t}" ref="${id}" role="${role}"/>\n`).join("")}${tagXml(r.tags)}  </relation>\n`;
}
xml += "</osm>\n";
fs.writeFileSync(out + "/sample.osm", xml);
const [ax, ay] = ll(-150, -20), [bx, by] = ll(150, 200);
fs.writeFileSync(out + "/sample-region.geojson", JSON.stringify({ type: "Feature",
  properties: { code: "fixture", name: "Fixture de teste (fictícia)" },
  geometry: { type: "Polygon", coordinates: [[[ax, ay], [bx, ay], [bx, by], [ax, by], [ax, ay]]] } }, null, 2) + "\n");
console.log(nodes.length, "nodes", ways.length, "ways", rels.length, "relations");
