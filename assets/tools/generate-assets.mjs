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

// Elemento: { d, fill?, stroke?, width?, evenOdd?, alpha?, clip?, top? } — clip=true recorta pelo círculo do avatar.
function toSvg(size, view, els, clipD) {
  const attrs = (e) =>
    `d="${e.d}" fill="${e.fill ?? "none"}"` +
    (e.stroke ? ` stroke="${e.stroke}" stroke-width="${e.width ?? 1}" stroke-linecap="round" stroke-linejoin="round"` : "") +
    (e.evenOdd ? ` fill-rule="evenodd"` : "") +
    (e.alpha != null ? (e.stroke ? ` stroke-opacity="${e.alpha}"` : ` fill-opacity="${e.alpha}"`) : "");
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
    (e.alpha != null ? (e.stroke ? ` android:strokeAlpha="${e.alpha}"` : ` android:fillAlpha="${e.alpha}"`) : "") +
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
// Avatares (48×48): personagem ilustrado em um medalhão (fundo, cabelo de trás, corpo com gola,
// pescoço, orelhas, cabeça com sombreamento, rosto e acessório), anel branco e filete escuro interno.
// ---------------------------------------------------------------------------
const CIRCLE = "M2,24 A22,22 0 1,0 46,24 A22,22 0 1,0 2,24 Z";
const INNER_RING = "M3.4,24 A20.6,20.6 0 1,0 44.6,24 A20.6,20.6 0 1,0 3.4,24 Z";
const BG_SHINE = "M5,15 A19,12 0 1,0 43,15 A19,12 0 1,0 5,15 Z";
const BODY = "M7,48 C7,35 14,30.5 24,30.5 C34,30.5 41,35 41,48 Z";
const BODY_SHADE = "M30,31.4 C36.6,33.4 41,38.4 41,48 L34,48 C34,40.5 32.6,35 30,31.4 Z";
const NECK = "M21,25.5 H27 V31 C27,33 21,33 21,31 Z";
const NECK_SHADE = "M21,26.5 H27 V28.6 C25,29.8 23,29.8 21,28.6 Z";
const COLLAR = "M19,30.9 L24,36 L29,30.9 L27.2,30.5 L24,33.7 L20.8,30.5 Z";
const EARS = "M13.1,20.5 A2.3,2.3 0 1,0 17.7,20.5 A2.3,2.3 0 1,0 13.1,20.5 Z M30.3,20.5 A2.3,2.3 0 1,0 34.9,20.5 A2.3,2.3 0 1,0 30.3,20.5 Z";
const HEAD = "M15.5,19.5 A8.5,8.5 0 1,0 32.5,19.5 A8.5,8.5 0 1,0 15.5,19.5 Z";
const FACE_SHADE = "M24,11 A8.5,8.5 0 0,1 24,28 A9.5,9.5 0 0,0 24,11 Z";
const EYES = "M19.85,20.2 A0.95,1.25 0 1,0 21.75,20.2 A0.95,1.25 0 1,0 19.85,20.2 Z M26.25,20.2 A0.95,1.25 0 1,0 28.15,20.2 A0.95,1.25 0 1,0 26.25,20.2 Z";
const EYE_SHINE = "M20.7,19.6 A0.42,0.42 0 1,0 21.54,19.6 A0.42,0.42 0 1,0 20.7,19.6 Z M27.1,19.6 A0.42,0.42 0 1,0 27.94,19.6 A0.42,0.42 0 1,0 27.1,19.6 Z";
const CHEEKS = "M17.2,23.2 A1.5,1.1 0 1,0 20.2,23.2 A1.5,1.1 0 1,0 17.2,23.2 Z M27.8,23.2 A1.5,1.1 0 1,0 30.8,23.2 A1.5,1.1 0 1,0 27.8,23.2 Z";
const SMILE = "M21.6,23.7 Q24,26.1 26.4,23.7";
const SHINE = "#FFFFFF";

// Cabelo e acessório de cada personagem: `back` fica atrás da cabeça, `front` por cima do rosto.
const looks = {
  none: () => ({ back: [], front: [] }),
  cap: (hair) => ({
    back: [{ d: "M15.4,21 Q15,13 18,12 L30,12 Q33,13 32.6,21 Z", fill: hair }],
    front: [
      { d: "M15.3,18 Q15.5,9 24,9 Q32.5,9 32.7,18 Z", fill: "#D84315" },
      { d: "M22,16.4 Q30,15.2 37,17 Q37.6,18.6 36,18.9 Q29,17.6 22,18.6 Z", fill: "#BF360C" },
      { d: "M23.1,9.3 A0.9,0.9 0 1,0 24.9,9.3 A0.9,0.9 0 1,0 23.1,9.3 Z", fill: "#BF360C" },
      { d: "M18,13.6 Q19.6,10.8 23.2,10.3", stroke: SHINE, width: 1, alpha: 0.45 },
    ],
  }),
  bun: (hair) => ({
    back: [
      { d: "M20.4,8.6 A3.6,3.6 0 1,0 27.6,8.6 A3.6,3.6 0 1,0 20.4,8.6 Z", fill: hair },
      { d: "M15.2,20 Q14.8,10.4 24,10.4 Q33.2,10.4 32.8,20 Z", fill: hair },
    ],
    front: [
      { d: "M15.6,18.5 Q16,11 24,11 Q32,11 32.4,18.5 Q29,14 24,14.6 Q19,14 15.6,18.5 Z", fill: hair },
      { d: "M21.8,11.4 H26.2 V12.6 H21.8 Z", fill: "#F06292" },
      { d: "M18.6,14.4 Q20.4,12.2 23.4,11.9", stroke: SHINE, width: 0.9, alpha: 0.3 },
    ],
  }),
  band: (hair) => ({
    back: [],
    front: [
      { d: "M15.7,18 Q15.6,10.6 24,10.6 Q32.4,10.6 32.3,18 Q31,15.6 29,16.1 Q27,14.6 24,15.2 Q21,14.6 19,16.1 Q17,15.6 15.7,18 Z", fill: hair },
      { d: "M15.8,15.6 Q24,12.6 32.2,15.6 L32.4,17.8 Q24,14.8 15.6,17.8 Z", fill: "#FFFFFF" },
      { d: "M16,16.5 Q24,13.6 32,16.5", stroke: "#E53935", width: 0.7 },
    ],
  }),
  helmet: (hair) => ({
    back: [{ d: "M15.4,21 Q15,14 17,13 L31,13 Q33,14 32.6,21 Z", fill: hair }],
    front: [
      { d: "M15,18 Q15,8.6 24,8.6 Q33,8.6 33,18 Z", fill: "#FFD600" },
      { d: "M22.8,8.8 H25.2 V17.8 H22.8 Z", fill: "#F9A825" },
      { d: "M13.2,17.2 Q24,15.8 34.8,17.2 L34.8,19 Q24,17.6 13.2,19 Z", fill: "#F9A825" },
      { d: "M17.4,14.2 Q18.6,10.8 21.8,10", stroke: SHINE, width: 1.1, alpha: 0.6 },
    ],
  }),
  beret: (hair) => ({
    back: [{ d: "M15,22 Q14.6,10.6 24,10.6 Q33.4,10.6 33,22 L33,27 Q31,28 30,26 L30,19 L18,19 L18,26 Q17,28 15,27 Z", fill: hair }],
    front: [
      { d: "M15.8,18 Q16.6,12 24,12 Q30.6,12 32.2,16.6 Q27,14.4 21,16.4 Q18,17.4 15.8,18 Z", fill: hair },
      { d: "M15.2,14.8 Q18.5,7.6 30.5,10.4 Q34.6,11.8 33.4,14.2 Q24,12.6 15.2,14.8 Z", fill: "#B71C1C" },
      { d: "M26.2,8.6 L27.4,7 L28.2,7.6 L27.2,9.1 Z", fill: "#B71C1C" },
      { d: "M18.4,12.4 Q21,9.8 25,9.6", stroke: SHINE, width: 0.9, alpha: 0.35 },
    ],
  }),
  phones: (hair) => ({
    back: [],
    front: [
      { d: "M16,16.5 Q16,10.8 24,10.8 Q32,10.8 32,16.5 Q28,14.6 24,14.6 Q20,14.6 16,16.5 Z", fill: hair },
      { d: "M15.6,19.5 Q15.6,9.6 24,9.6 Q32.4,9.6 32.4,19.5", stroke: "#212121", width: 2 },
      { d: "M13.4,17.4 H17.2 V23.8 H13.4 Z M30.8,17.4 H34.6 V23.8 H30.8 Z", fill: "#E53935" },
      { d: "M14.2,18.4 V22.6 M31.6,18.4 V22.6", stroke: SHINE, width: 0.8, alpha: 0.5 },
    ],
  }),
  glasses: (hair) => ({
    back: [{ d: "M15,24 Q14.4,10.4 24,10.4 Q33.6,10.4 33,24 L33,30 L29.6,30 L29.6,19 L18.4,19 L18.4,30 L15,30 Z", fill: hair }],
    front: [
      { d: "M15.6,16.6 Q15.8,10.8 24,10.8 Q32.2,10.8 32.4,16.6 Z", fill: hair },
      { d: "M18.2,20.2 A2.6,2.6 0 1,0 23.4,20.2 A2.6,2.6 0 1,0 18.2,20.2 Z M24.6,20.2 A2.6,2.6 0 1,0 29.8,20.2 A2.6,2.6 0 1,0 24.6,20.2 Z M23.4,19.8 Q24,19.2 24.6,19.8", stroke: "#212121", width: 1.1 },
      { d: "M18.4,13.8 Q20.6,11.8 24,11.6", stroke: SHINE, width: 0.9, alpha: 0.3 },
    ],
  }),
  curls: (hair) => ({
    back: [{ d: "M13.5,17 A10.5,9.5 0 1,0 34.5,17 A10.5,9.5 0 1,0 13.5,17 Z", fill: hair }],
    front: [
      { d: "M16,16.8 Q15.5,11.5 19.5,11.6 Q21,9.8 24,10.4 Q27,9.8 28.5,11.6 Q32.5,11.5 32,16.8 Q30.5,14.6 28.6,15.4 Q26.6,13.6 24,14.6 Q21.4,13.6 19.4,15.4 Q17.5,14.6 16,16.8 Z", fill: hair },
      { d: "M17.2,11.6 Q19,9.2 22,8.8", stroke: SHINE, width: 0.9, alpha: 0.25 },
    ],
  }),
};

const avatars = [
  { id: "avatar_00", bg: "#9E9E9E", shirt: "#BDBDBD", skin: "#E0E0E0", hair: "#BDBDBD", look: "none", face: false }, // conta removida/anônima
  { id: "avatar_01", bg: "#1B5E52", shirt: "#F2C14E", skin: "#8D5524", hair: "#2B1B12", look: "cap" },
  { id: "avatar_02", bg: "#5E35B1", shirt: "#80CBC4", skin: "#F1C27D", hair: "#3E2723", look: "bun" },
  { id: "avatar_03", bg: "#EF6C00", shirt: "#1565C0", skin: "#C68642", hair: "#2B1B12", look: "band" },
  { id: "avatar_04", bg: "#1565C0", shirt: "#FFB300", skin: "#E0AC69", hair: "#5D4037", look: "helmet" },
  { id: "avatar_05", bg: "#2E7D32", shirt: "#F48FB1", skin: "#FFDBAC", hair: "#A0462A", look: "beret" },
  { id: "avatar_06", bg: "#6D4C41", shirt: "#4DD0E1", skin: "#8D5524", hair: "#1A1A1A", look: "phones" },
  { id: "avatar_07", bg: "#AD1457", shirt: "#FFF59D", skin: "#F1C27D", hair: "#212121", look: "glasses" },
  { id: "avatar_08", bg: "#37474F", shirt: "#A5D6A7", skin: "#C68642", hair: "#4E342E", look: "curls" },
];

for (const a of avatars) {
  const look = looks[a.look](a.hair);
  const face = a.face === false ? [] : [
    { d: EYES, fill: "#2B1B12" },
    { d: EYE_SHINE, fill: "#FFFFFF" },
    { d: CHEEKS, fill: "#FF6F61", alpha: 0.35 },
    { d: SMILE, stroke: "#5D2E1F", width: 1.1 },
  ];
  const els = [
    { d: CIRCLE, fill: a.bg },
    { d: BG_SHINE, fill: "#FFFFFF", alpha: 0.12, clip: true },
    ...look.back.map((e) => ({ ...e, clip: true })),
    { d: BODY, fill: a.shirt, clip: true },
    { d: BODY_SHADE, fill: "#000000", alpha: 0.14, clip: true },
    { d: NECK, fill: a.skin, clip: true },
    { d: NECK_SHADE, fill: "#000000", alpha: 0.16, clip: true },
    { d: COLLAR, fill: "#FFFFFF", alpha: 0.9, clip: true },
    { d: EARS, fill: a.skin, clip: true },
    { d: HEAD, fill: a.skin, clip: true },
    { d: FACE_SHADE, fill: "#000000", alpha: 0.1, clip: true },
    ...face.map((e) => ({ ...e, clip: true })),
    ...look.front.map((e) => ({ ...e, clip: true })),
    { d: INNER_RING, stroke: "#000000", width: 0.8, alpha: 0.22, top: true },
    { d: CIRCLE, stroke: "#FFFFFF", width: 2, top: true },
  ];
  write(join(root, "assets", "avatars", `${a.id}.svg`), toSvg(96, 48, els, CIRCLE));
  write(join(android, "core", "src", "main", "res", "drawable", `dv_${a.id}.xml`), toVector(48, 48, els, CIRCLE));
}

// ---------------------------------------------------------------------------
// Moeda do jogo (24×24): disco dourado com borda, aro interno, casinha e brilho.
// ---------------------------------------------------------------------------
const coin = [
  { d: "M1,12 A11,11 0 1,0 23,12 A11,11 0 1,0 1,12 Z", fill: "#D99A1E" },
  { d: "M3,11.4 A9,9 0 1,0 21,11.4 A9,9 0 1,0 3,11.4 Z", fill: "#F7C948" },
  { d: "M4.6,11.4 A7.4,7.4 0 1,0 19.4,11.4 A7.4,7.4 0 1,0 4.6,11.4 Z", stroke: "#E0A82E", width: 0.9 },
  { d: "M12,6.8 L7.4,10.8 H8.9 V15.8 H15.1 V10.8 H16.6 Z M11.1,15.8 V13.2 H12.9 V15.8 Z", fill: "#B7791F", evenOdd: true },
  { d: "M5.8,8.6 A7,7 0 0,1 9.6,5.2", stroke: "#FFFFFF", width: 1.2, alpha: 0.65 },
];
write(join(root, "assets", "icons", "coin.svg"), toSvg(48, 24, coin));
write(join(android, "core", "src", "main", "res", "drawable", "dv_coin.xml"), toVector(24, 24, coin));

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

console.log(`ok: ${avatars.length} avatares, moeda, ${Object.keys(states).length} estados, ${Object.keys(categories).length} categorias, ícone do app`);
