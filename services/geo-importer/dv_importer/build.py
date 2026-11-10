"""Consolidação: vincula POIs/entradas/endereços às construções, classifica e gera linhas de staging.

Puro (sem I/O): recebe o que foi lido do OSM e devolve linhas determinísticas, ordenadas por referência.
"""
from __future__ import annotations

from collections import Counter, defaultdict
from typing import Any

import shapely
from shapely.geometry.base import BaseGeometry

from .annex import absorb_annexes, is_annex_candidate
from .classify import Classification, LinkedPoi, classify_building, classify_poi
from .config import (ENTRANCE_MAX_DISTANCE_M, MAX_BUILDING_AREA_M2, MIN_BUILDING_AREA_M2, OVERLAP_RATIO,
                     POI_LINK_MAX_DISTANCE_M, RULES_VERSION, VISUAL_MODEL)
from .geometry import LocalProjection, interaction_point, round_point
from .model import Building, BuildResult, Extracted, PointFeature, Reject, SourceRef, StagingRow
from .normalize import address_label, height_for, kept_tags


def _eligibility(cls: Classification, quality: str, overlapping: bool) -> tuple[str, str | None]:
    # "inferred": categoria estimada pelo formato; negociável, e a ficha avisa que é uma estimativa.
    if cls.status not in ("resolved", "inferred") or cls.category is None:
        return "pending_classification", cls.status
    if overlapping:
        return "under_review", "overlapping_geometry"
    if quality == "centroid_fallback":
        return "under_review", "no_accessible_edge"
    return "eligible", None


def _locator(items: list[tuple[Building, BaseGeometry, float]]):
    """Procura a construção dona de um ponto: vértice explícito → contenção → proximidade."""
    tree = shapely.STRtree([fp for _, fp, _ in items]) if items else None
    vertex_owner: dict[int, int] = {}
    for i, (b, _, _) in enumerate(items):
        for nid in sorted(b.node_ids):
            vertex_owner.setdefault(nid, i)

    def owner_of(feat: PointFeature, pm: BaseGeometry, max_near: float) -> tuple[int | None, str | None]:
        if feat.ref.object_type == "node" and feat.ref.source_id in vertex_owner:
            return vertex_owner[feat.ref.source_id], "explicit"
        if tree is None:
            return None, None
        hits = tree.query(pm, predicate="within")
        if len(hits):
            return int(min(hits)), "inside"
        near = tree.query_nearest(pm, max_distance=max_near, all_matches=False)
        if len(near):
            return int(near[0]), "near"
        return None, None

    return owner_of, tree


def build(ex: Extracted, region: BaseGeometry) -> BuildResult:
    center = region.centroid
    proj = LocalProjection(center.x, center.y)
    shapely.prepare(region)
    rejects: list[Reject] = list(ex.rejects)

    def in_region(g: BaseGeometry) -> bool:
        return region.contains(g.representative_point())

    # 1. Construções dentro da região, com área plausível.
    found: list[tuple[Building, BaseGeometry, float]] = []
    outside = 0
    for b in sorted(ex.buildings, key=lambda x: x.ref.key):
        if not in_region(b.geom):
            outside += 1
            continue
        fp_m = proj.to_m(b.geom)
        area = fp_m.area
        if area > MAX_BUILDING_AREA_M2:
            rejects.append(Reject(b.ref.key, "too_large", {"area_m2": round(area, 1)}))
            continue
        found.append((b, fp_m, area))

    # 1b. POIs: vinculados às construções como vieram do mapa, antes de juntar anexos. Assim quem tem loja vinculada
    # (dentro, num vértice ou a poucos metros) nunca vira anexo, e o contorno maior de um vizinho não leva a loja.
    original = [it for it in found if it[2] >= MIN_BUILDING_AREA_M2]
    poi_owner, _ = _locator(original)
    poi_links: dict[str, list[tuple[PointFeature, str]]] = defaultdict(list)
    standalone: list[PointFeature] = []
    link_stats: Counter[str] = Counter()
    for p in sorted(ex.pois, key=lambda x: x.ref.key):
        if not in_region(p.geom):
            continue
        i, how = poi_owner(p, proj.to_m(p.geom), POI_LINK_MAX_DISTANCE_M)
        if i is None:
            standalone.append(p)
        else:
            poi_links[original[i][0].ref.key].append((p, how or "inside"))
            link_stats[how or "inside"] += 1

    # 1c. Anexos (edícula, garagem, puxadinho): pedaço pequeno encostado num prédio maior vira parte dele.
    # Pedaço com POI nunca é absorvido: um quiosque continua sendo um imóvel próprio.
    poi_nodes = {p.ref.source_id for p in ex.pois if p.ref.object_type == "node"}
    poi_pts = [proj.to_m(p.geom) for p in sorted(ex.pois, key=lambda x: x.ref.key)]
    poi_tree = shapely.STRtree(poi_pts) if poi_pts else None
    items: list[tuple[Building, BaseGeometry, float]] = []
    with_poi: set[int] = set()
    for b, fp_m, area in found:
        has_poi = b.ref.key in poi_links or bool(poi_nodes & b.node_ids) or (
            poi_tree is not None and len(poi_tree.query(fp_m, predicate="contains")) > 0)
        if area < MIN_BUILDING_AREA_M2 and not is_annex_candidate(b, area, has_poi):
            rejects.append(Reject(b.ref.key, "too_small", {"area_m2": round(area, 1)}))
            continue
        if has_poi:
            with_poi.add(len(items))
        items.append((b, fp_m, area))
    pieces, annex_rejects, annex_counts = absorb_annexes(items, with_poi)
    rejects.extend(annex_rejects)
    buildings = [(pc.building, pc.fp_m, pc.area) for pc in pieces]
    linked: dict[int, list[tuple[PointFeature, str]]] = {
        i: poi_links[pc.building.ref.key] for i, pc in enumerate(pieces) if pc.building.ref.key in poi_links}

    fps_m = [fp for _, fp, _ in buildings]
    owner_of, tree = _locator(buildings)

    # 2. Vínculos das peças finais: entradas e endereços.
    entrances: dict[int, list[tuple[PointFeature, BaseGeometry, bool]]] = defaultdict(list)
    for e in sorted(ex.entrances, key=lambda x: x.ref.key):
        em = proj.to_m(e.geom)
        i, how = owner_of(e, em, ENTRANCE_MAX_DISTANCE_M)
        if i is not None:
            entrances[i].append((e, em, how == "explicit"))

    addresses: dict[int, list[PointFeature]] = defaultdict(list)
    for a in sorted(ex.addresses, key=lambda x: x.ref.key):
        if tree is None:
            break
        hits = tree.query(proj.to_m(a.geom), predicate="within")
        if len(hits):
            addresses[int(min(hits))].append(a)

    # 3. Sobreposição relevante: o menor fica em revisão (não criar patrimônio duplicado).
    overlapping: set[int] = set()
    if tree is not None:
        for i, fp in enumerate(fps_m):
            for j in tree.query(fp, predicate="intersects"):
                j = int(j)
                if j <= i:
                    continue
                small = min(fp.area, fps_m[j].area)
                if small > 0 and fp.intersection(fps_m[j]).area / small > OVERLAP_RATIO:
                    overlapping.add(i if fp.area <= fps_m[j].area else j)

    streets_m = [proj.to_m(s.geom) for s in sorted(ex.streets, key=lambda x: x.ref.key)]
    street_tree = shapely.STRtree(streets_m) if streets_m else None

    rows: list[StagingRow] = []
    repaired = 0
    for i, (b, fp_m, area) in enumerate(buildings):
        pois = linked.get(i, [])
        cls = classify_building(b.tags, [LinkedPoi(p.tags, how) for p, how in pois], area)
        parts = pieces[i].parts
        if isinstance(cls, str):
            rejects.append(Reject(b.ref.key, cls, {"building": b.tags.get("building")}))
            rejects.extend(Reject(a.ref.key, cls, {"part_of": b.ref.key}) for a in parts)
            continue
        ip_m, quality, entrance_ref = interaction_point(fp_m, entrances.get(i, []), street_tree, streets_m)

        # Anexos ficam registrados como "part": o imóvel mantém a identidade do prédio principal.
        refs: list[SourceRef] = [b.ref] + [a.ref.with_role("part") for a in parts]             + [p.ref.with_role("poi") for p, _ in pois]
        if entrance_ref is not None:
            refs.append(entrance_ref)
        label = address_label(b.tags)
        if label is None:
            candidates = sorted({lbl for a in addresses.get(i, []) if (lbl := address_label(a.tags))})
            if len(candidates) == 1:
                label = candidates[0]
                refs.extend(a.ref.with_role("address") for a in addresses[i] if address_label(a.tags) == label)
        if label is None:
            poi_labels = sorted({lbl for p, _ in pois if (lbl := address_label(p.tags))})
            if len(poi_labels) == 1:
                label = poi_labels[0]

        height, height_source = height_for(b.tags, cls.category)
        elig, reason = _eligibility(cls, quality, i in overlapping)
        repaired += int(b.repaired)
        rows.append(StagingRow(
            canonical_ref=b.ref.key,
            source_refs=refs,
            category_code=cls.category,
            subtype=cls.subtype,
            classification_status=cls.status,
            classification_confidence=round(max(0.0, cls.confidence - (0.05 if b.repaired else 0.0)), 3),
            address_label=label,
            point=round_point(b.geom.representative_point()),
            footprint=b.geom,
            interaction_point=round_point(proj.to_ll(ip_m)),
            interaction_quality=quality,
            height_m=height,
            height_source=height_source,
            visual_model=VISUAL_MODEL.get(cls.category, VISUAL_MODEL[None]),
            units_count=cls.units,
            eligibility=elig,
            eligibility_reason=reason,
            source_tags={"building": kept_tags(b.tags), "pois": [kept_tags(p.tags) for p, _ in pois]}
            | ({"parts": len(parts)} if parts else {}),
        ))

    ignored_pois = 0
    for p in standalone:
        cls = classify_poi(p.tags)
        if cls is None:
            ignored_pois += 1
            continue
        height, height_source = height_for(p.tags, cls.category)
        pt = round_point(p.geom)
        rows.append(StagingRow(
            canonical_ref=p.ref.key, source_refs=[p.ref], category_code=cls.category, subtype=cls.subtype,
            classification_status=cls.status, classification_confidence=cls.confidence,
            address_label=address_label(p.tags), point=pt, footprint=None, interaction_point=pt,
            interaction_quality="poi_point", height_m=height, height_source=height_source,
            visual_model=VISUAL_MODEL[cls.category], units_count=1, eligibility="eligible", eligibility_reason=None,
            source_tags={"building": {}, "pois": [kept_tags(p.tags)]},
        ))

    rows.sort(key=lambda r: r.canonical_ref)
    staged = len(rows)
    eligible = sum(r.eligibility == "eligible" for r in rows)
    with_address = sum(r.address_label is not None for r in rows)
    with_entrance = sum(r.interaction_quality == "entrance_mapped" for r in rows)
    counts: dict[str, Any] = {
        "buildings_read": len(ex.buildings),
        "buildings_outside_region": outside,
        "pois_read": len(ex.pois),
        "entrances_read": len(ex.entrances),
        "addresses_read": len(ex.addresses),
        "streets_read": len(ex.streets),
        "staged": staged,
        "rejected": len(rejects),
        "eligible": eligible,
        "with_address": with_address,
        "repaired_geometries": repaired,
        "overlapping": len(overlapping),
        **annex_counts,
        "pois_linked": dict(sorted(link_stats.items())),
        "pois_standalone": len(standalone) - ignored_pois,
        "pois_ignored": ignored_pois,
        "by_category": dict(sorted(Counter(r.category_code or "none" for r in rows).items())),
        "by_status": dict(sorted(Counter(r.classification_status for r in rows).items())),
        "by_eligibility": dict(sorted(Counter(r.eligibility for r in rows).items())),
        "by_interaction_quality": dict(sorted(Counter(r.interaction_quality for r in rows).items())),
    }
    ratio = (lambda n: round(n / staged, 4) if staged else 0.0)
    report: dict[str, Any] = {
        "rules_version": RULES_VERSION,
        "coverage": {"eligible_ratio": ratio(eligible), "address_ratio": ratio(with_address),
                     "entrance_ratio": ratio(with_entrance)},
        "rejects_by_reason": dict(sorted(Counter(r.reason for r in rejects).items())),
        "ambiguities": [
            {"ref": r.canonical_ref, "reason": r.eligibility_reason or r.classification_status}
            for r in rows if r.eligibility != "eligible"
        ][:200],
    }
    return BuildResult(rows=rows, rejects=rejects, counts=counts, report=report)
