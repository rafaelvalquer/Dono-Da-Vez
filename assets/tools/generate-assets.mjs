// Gera os assets visuais originais do Dono da Vez a partir de uma única definição:
//   assets/avatars/*.svg, assets/icons/*.svg, assets/sprites/*.svg (fontes para sprite sheet)
//   e os VectorDrawables equivalentes usados pelo app Android.
// Uso: node assets/tools/generate-assets.mjs   (sem dependências)
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const android = join(root, "apps", "android");

function write(path, text) {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, text);
}

// Elemento: { d, fill?, stroke?, width?, evenOdd?, clip? } — clip=true recorta pelo círculo do avatar.
function toSvg(size, view, els, clipD) {
  const attrs = (e) =>
    `d="${e.d}" fill="${e.fill ?? "none"}"` +
    (e.stroke ? ` stroke="${e.stroke}" stroke-width="${e.width ?? 1}" stroke-linecap="round" stroke-linejoin="round"` : "") +
    (e.evenOdd ? ` fill-rule="evenodd"` : "");
  const body = [];
  if (clipD) body.push(`<defs><clipPath id="c"><path d="${clipD}"/></clipPath></defs>`);
  const inClip = els.filter((e) => e.clip);
  for (const e of els.filter((e) => !e.clip && !e.top)) body.push(`<path ${attrs(e)}/>`);
  if (inClip.length) body.push(`<g clip-path="url(#c)">${inClip.map((e) => `<path ${attrs(e)}/>`).join("")}</g>`);
  for (const e of els.filter((e) => e.top)) body.push(`<path ${attrs(e)}/>`);
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 ${view} ${view}">${body.join("")}</svg>\n`;
}

function toVector(size, view, els, clipD) {
  const path = (e) =>
    `  <path android:pathData="${e.d}"` +
    (e.fill ? ` android:fillColor="${e.fill}"` : "") +
    (e.stroke ? ` android:strokeColor="${e.stroke}" android:strokeWidth="${e.width ?? 1}" android:strokeLineCap="round" android:strokeLineJoin="round"` : "") +
    (e.evenOdd ? ` android:fillType="evenOdd"` : "") +
    "/>";
  const lines = [
    `<?xml version="1.0" encoding="utf-8"?>`,
    `<!-- Gerado por assets/tools/generate-assets.mjs — não editar à mão. -->`,
    `<vector xmlns:android="http://schemas.android.com/apk/res/android"`,
    `    android:width="${size}dp" android:height="${size}dp"`,
    `    android:viewportWidth="${view}" android:viewportHeight="${view}">`,
  ];
  for (const e of els.filter((e) => !e.clip && !e.top)) lines.push(path(e));
  const inClip = els.filter((e) => e.clip);
  if (inClip.length) {
    lines.push(`  <group>`, `    <clip-path android:pathData="${clipD}"/>`);
    for (const e of inClip) lines.push("  " + path(e));
    lines.push(`  </group>`);
  }
  for (const e of els.filter((e) => e.top)) lines.push(path(e));
  lines.push(`</vector>`);
  return lines.join("\n") + "\n";
}

// ---------------------------------------------------------------------------
// Avatares (48×48): fundo, corpo recortado no círculo, cabeça e acessório.
// ---------------------------------------------------------------------------
const CIRCLE = "M2,24 A22,22 0 1,0 46,24 A22,22 0 1,0 2,24 Z";
const HEAD = "M17,19 A7,7 0 1,0 31,19 A7,7 0 1,0 17,19 Z";
const BODY = "M8,48 C8,34 15,29 24,29 C33,29 40,34 40,48 Z";

const accessories = {
  none: { back: [], front: [] },
  cap: { back: [], front: [{ d: "M16.6,17 Q17,10 24,10 Q31,10 31.4,17 Z", fill: "#D84315" }, { d: "M24,15.5 L36,15.5 L36,17.5 L24,17.5 Z", fill: "#BF360C" }] },
  bun: { back: [{ d: "M20.5,10 A3.5,3.5 0 1,0 27.5,10 A3.5,3.5 0 1,0 20.5,10 Z", fill: "#3E2723" }], front: [{ d: "M17,18 Q17.5,11 24,11 Q30.5,11 31,18 Q27,14.5 17,18 Z", fill: "#3E2723" }] },
  band: { back: [], front: [{ d: "M17.2,15 L30.8,15 L31,17.6 L17,17.6 Z", fill: "#FFFFFF" }] },
  helmet: { back: [], front: [{ d: "M16.5,17 Q16.5,9 24,9 Q31.5,9 31.5,17 Z", fill: "#FFD600" }, { d: "M14.5,16.5 L33.5,16.5 L33.5,18.3 L14.5,18.3 Z", fill: "#F9A825" }] },
  beret: { back: [], front: [{ d: "M16,15.5 Q20,8.5 31.5,12.5 Q32,15.5 16,15.5 Z", fill: "#B71C1C" }] },
  phones: { back: [], front: [{ d: "M16.2,20 Q16.2,10.5 24,10.5 Q31.8,10.5 31.8,20", stroke: "#212121", width: 2 }, { d: "M14.5,18 h3.5 v5 h-3.5 Z", fill: "#212121" }, { d: "M30,18 h3.5 v5 h-3.5 Z", fill: "#212121" }] },
  glasses: { back: [], front: [{ d: "M18,19.5 A2.6,2.6 0 1,0 23.2,19.5 A2.6,2.6 0 1,0 18,19.5 Z M24.8,19.5 A2.6,2.6 0 1,0 30,19.5 A2.6,2.6 0 1,0 24.8,19.5 Z M23.2,19.5 L24.8,19.5", stroke: "#212121", width: 1.2 }] },
  hair: { back: [{ d: "M15.5,22 Q15,10 24,10 Q33,10 32.5,22 L32.5,28 L28.5,28 L28.5,18 L19.5,18 L19.5,28 L15.5,28 Z", fill: "#4E342E" }], front: [{ d: "M17,17 Q18,11.5 24,11.5 Q30,11.5 31,17 Q25,13.5 17,17 Z", fill: "#4E342E" }] },
};

const avatars = [
  { id: "avatar_00", bg: "#9E9E9E", shirt: "#BDBDBD", skin: "#E0E0E0", acc: "none" }, // conta removida/anônima
  { id: "avatar_01", bg: "#1B5E52", shirt: "#F2C14E", skin: "#8D5524", acc: "cap" },
  { id: "avatar_02", bg: "#5E35B1", shirt: "#80CBC4", skin: "#F1C27D", acc: "bun" },
  { id: "avatar_03", bg: "#EF6C00", shirt: "#1565C0", skin: "#C68642", acc: "band" },
  { id: "avatar_04", bg: "#1565C0", shirt: "#FFB300", skin: "#E0AC69", acc: "helmet" },
  { id: "avatar_05", bg: "#2E7D32", shirt: "#F48FB1", skin: "#FFDBAC", acc: "beret" },
  { id: "avatar_06", bg: "#6D4C41", shirt: "#4DD0E1", skin: "#8D5524", acc: "phones" },
  { id: "avatar_07", bg: "#AD1457", shirt: "#FFF59D", skin: "#F1C27D", acc: "glasses" },
  { id: "avatar_08", bg: "#37474F", shirt: "#A5D6A7", skin: "#C68642", acc: "hair" },
];

for (const a of avatars) {
  const acc = accessories[a.acc];
  const els = [
    { d: CIRCLE, fill: a.bg },
    ...acc.back.map((e) => ({ ...e, clip: true })),
    { d: BODY, fill: a.shirt, clip: true },
    { d: HEAD, fill: a.skin, clip: true },
    ...acc.front.map((e) => ({ ...e, clip: true })),
    { d: CIRCLE, stroke: "#FFFFFF", width: 2, top: true },
  ];
  write(join(root, "assets", "avatars", `${a.id}.svg`), toSvg(96, 48, els, CIRCLE));
  write(join(android, "core", "src", "main", "res", "drawable", `dv_${a.id}.xml`), toVector(48, 48, els, CIRCLE));
}

// ---------------------------------------------------------------------------
// Estados no mapa (24×24): círculo colorido + símbolo branco (nunca só cor).
// ---------------------------------------------------------------------------
const DOT = "M2,12 A10,10 0 1,0 22,12 A10,10 0 1,0 2,12 Z";
const states = {
  available: { color: "#2E7D32", glyph: [{ d: "M11,7 h2 v4 h4 v2 h-4 v4 h-2 v-4 H7 v-2 h4 Z", fill: "#FFFFFF" }] },
  mine: { color: "#1565C0", glyph: [{ d: "M12,6.2 L13.7,9.9 L17.7,10.3 L14.7,13 L15.6,17 L12,14.9 L8.4,17 L9.3,13 L6.3,10.3 L10.3,9.9 Z", fill: "#FFFFFF" }] },
  other: { color: "#EF6C00", glyph: [{ d: "M9.8,9.2 A2.2,2.2 0 1,0 14.2,9.2 A2.2,2.2 0 1,0 9.8,9.2 Z M7.8,16.8 C7.8,14.2 9.8,12.8 12,12.8 C14.2,12.8 16.2,14.2 16.2,16.8 Z", fill: "#FFFFFF" }] },
  proposal: { color: "#6A1B9A", glyph: [{ d: "M6.8,8.2 h10.4 v7.6 H6.8 Z", fill: "#FFFFFF" }, { d: "M7.2,8.6 L12,12.4 L16.8,8.6", stroke: "#6A1B9A", width: 1.3 }] },
  unavailable: { color: "#757575", glyph: [{ d: "M7.5,11 h9 v2 h-9 Z", fill: "#FFFFFF" }] },
};
for (const [key, s] of Object.entries(states)) {
  const els = [{ d: DOT, fill: s.color, stroke: "#FFFFFF", width: 1.5 }, ...s.glyph];
  write(join(root, "assets", "icons", `state_${key}.svg`), toSvg(48, 24, els));
  write(join(root, "assets", "sprites", `dv-state-${key}.svg`), toSvg(32, 24, els));
  write(join(android, "map", "src", "main", "res", "drawable", `dv_state_${key}.xml`), toVector(32, 24, els));
}

// ---------------------------------------------------------------------------
// Categorias (24×24, monocromático; o app aplica a cor do tema).
// ---------------------------------------------------------------------------
const categories = {
  house_small: "M12,4 L3,11 H5.5 V19 H10.5 V14 H13.5 V19 H18.5 V11 H21 Z",
  house_large: "M12,3 L2,10.5 H4 V20 H20 V10.5 H22 Z M7,12.5 H10 V15.5 H7 Z M14,12.5 H17 V15.5 H14 Z",
  commercial: "M3,4 H21 L22,9 C22,10.1 21.1,11 20,11 C18.9,11 18,10.1 18,9 C18,10.1 17.1,11 16,11 C14.9,11 14,10.1 14,9 C14,10.1 13.1,11 12,11 C10.9,11 10,10.1 10,9 C10,10.1 9.1,11 8,11 C6.9,11 6,10.1 6,9 C6,10.1 5.1,11 4,11 C2.9,11 2,10.1 2,9 Z M4,12.5 H20 V20 H14 V15 H10 V20 H4 Z",
  residential_building: "M5,3 H19 V21 H14 V17 H10 V21 H5 Z M8,6 V8 H10 V6 Z M14,6 V8 H16 V6 Z M8,10 V12 H10 V10 Z M14,10 V12 H16 V10 Z M8,14 V15.5 H10 V14 Z M14,14 V15.5 H16 V14 Z",
  pending: "M12,3 L21,12 L12,21 L3,12 Z M12,7.5 L7.5,12 L12,16.5 L16.5,12 Z",
};
for (const [key, d] of Object.entries(categories)) {
  const els = [{ d, fill: "#000000", evenOdd: true }];
  write(join(root, "assets", "icons", `category_${key}.svg`), toSvg(48, 24, els));
  write(join(android, "core", "src", "main", "res", "drawable", `dv_category_${key}.xml`), toVector(24, 24, els, null));
}

// ---------------------------------------------------------------------------
// Ícone do app (adaptativo, 108×108; zona segura de 66 dp no centro).
// ---------------------------------------------------------------------------
const launcher = [
  { d: "M54,31 L32,50 H38 V76 H70 V50 H76 Z", fill: "#FFFFFF" },
  { d: "M49,76 V62 H59 V76 Z", fill: "#1B5E52" },
  { d: "M45,40 L49,45 L54,38 L59,45 L63,40 L62,49 H46 Z", fill: "#F2C14E" },
];
write(join(root, "assets", "icons", "app_icon.svg"),
  toSvg(432, 108, [{ d: "M0,0 H108 V108 H0 Z", fill: "#1B5E52" }, ...launcher]));
const res = join(android, "app", "src", "main", "res");
write(join(res, "drawable", "ic_launcher_foreground.xml"), toVector(108, 108, launcher));
write(join(res, "drawable", "ic_launcher_monochrome.xml"), toVector(108, 108, launcher.map((e) => ({ ...e, fill: "#FFFFFF" }))));
write(join(res, "values", "ic_launcher_background.xml"),
  `<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_launcher_background">#1B5E52</color>\n</resources>\n`);
const adaptive = `<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>
</adaptive-icon>
`;
write(join(res, "mipmap-anydpi-v26", "ic_launcher.xml"), adaptive);
write(join(res, "mipmap-anydpi-v26", "ic_launcher_round.xml"), adaptive);

console.log(`ok: ${avatars.length} avatares, ${Object.keys(states).length} estados, ${Object.keys(categories).length} categorias, ícone do app`);
