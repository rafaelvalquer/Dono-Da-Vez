# Sprites de estado

`dv-state-*.svg` são as fontes dos marcadores de estado do imóvel (disponível, meu, de outro jogador, com proposta,
indisponível), geradas por `assets/tools/generate-assets.mjs`, junto com os VectorDrawables do Android.

O app **não** depende de um sprite sheet hospedado: `MapLayers.install` rasteriza os drawables e registra cada um
como imagem do estilo (`dv-state-<estado>`), então o mapa base pode vir de qualquer fornecedor.

Se um estilo hospedado precisar dos mesmos ícones (por exemplo, um painel web com MapLibre GL JS), gere o sprite
sheet com [spreet](https://github.com/flother/spreet):

```bash
spreet assets/sprites build/sprites/dv
```

```bash
spreet --retina assets/sprites build/sprites/dv@2x
```

Depois aponte `"sprite"` do estilo para o caminho publicado, sem a extensão (ex.:
`https://<ref>.supabase.co/storage/v1/object/public/assets/sprites/dv`).
