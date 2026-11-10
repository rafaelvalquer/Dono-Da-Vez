"""Anexos: pedaços pequenos (edícula, garagem, puxadinho) viram parte do prédio vizinho.

O OSM não tem lotes. Um pedaço pequeno sem uso declarado que encosta num prédio maior é tratado como parte dele
(o imóvel continua sendo o prédio principal; a categoria e o preço não mudam, só o desenho fica maior). Pedaços
pequenos encostados só entre si viram um imóvel se juntos tiverem área suficiente; sozinhos, não são importados.
Pedaço com comércio ou ponto de interesse vinculado nunca é absorvido.

Puro e determinístico: a mesma entrada produz sempre as mesmas peças, na mesma ordem.
"""
from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass, field

import shapely
from shapely.geometry import MultiPolygon, Polygon
from shapely.geometry.base import BaseGeometry

from .classify import is_commercial, is_institutional
from .config import (ANNEX_BUILDINGS, ANNEX_GROUP_MAX_PARTS, ANNEX_GROUP_MIN_AREA_M2, ANNEX_MAX_AREA_M2,
                     ANNEX_TOUCH_DISTANCE_M, MIN_BUILDING_AREA_M2)
from .model import Building, Reject


@dataclass
class Piece:
    building: Building          # prédio principal (referência canônica e tags) com a geometria já unida
    fp_m: BaseGeometry          # contorno em metros (unido)
    area: float                 # área usada na classificação: a do prédio principal, ou a soma num grupo
    parts: list[Building] = field(default_factory=list)  # anexos absorvidos, ordenados pela referência


def is_annex_candidate(b: Building, area: float, has_poi: bool) -> bool:
    """Pedaço pequeno sem uso declarado. Nunca: casa declarada, comércio, instituição, POI vinculado ou número próprio
    (um pedaço com número na fachada é outra casa, não um anexo)."""
    tag = (b.tags.get("building") or "").strip().lower()
    return (area < ANNEX_MAX_AREA_M2 and not has_poi and tag in ANNEX_BUILDINGS
            and not b.tags.get("addr:housenumber")
            and not is_commercial(b.tags) and not is_institutional(b.tags))


def as_multipolygon(g: BaseGeometry) -> MultiPolygon:
    if isinstance(g, MultiPolygon):
        return g
    if isinstance(g, Polygon):
        return MultiPolygon([g])
    polys = [p for p in getattr(g, "geoms", []) if isinstance(p, Polygon)]
    polys += [q for p in getattr(g, "geoms", []) if isinstance(p, MultiPolygon) for q in p.geoms]
    return MultiPolygon(polys)


def _merge(host: Building, host_fp: BaseGeometry, parts: list[tuple[Building, BaseGeometry]], area: float) -> Piece:
    parts = sorted(parts, key=lambda p: p[0].ref.key)
    geom = as_multipolygon(shapely.unary_union([host.geom] + [b.geom for b, _ in parts]))
    fp = shapely.unary_union([host_fp] + [fp for _, fp in parts])
    nodes = frozenset(host.node_ids).union(*(b.node_ids for b, _ in parts))
    merged = Building(ref=host.ref, tags=host.tags, geom=geom, node_ids=nodes,
                      repaired=host.repaired or any(b.repaired for b, _ in parts))
    return Piece(merged, fp, area, [b for b, _ in parts])


def absorb_annexes(items: list[tuple[Building, BaseGeometry, float]], with_poi: set[int]
                   ) -> tuple[list[Piece], list[Reject], dict[str, int]]:
    """[items] = (construção, contorno em metros, área), em ordem de referência; [with_poi] = índices com POI vinculado.

    Devolve as peças (prédios com seus anexos), as rejeições de pedaços isolados e contadores para o relatório.
    """
    n = len(items)
    candidates = [i for i in range(n) if is_annex_candidate(items[i][0], items[i][2], i in with_poi)]
    cand_set = set(candidates)
    hosts = [i for i in range(n) if items[i][2] >= ANNEX_MAX_AREA_M2]
    host_tree = shapely.STRtree([items[i][1] for i in hosts]) if hosts else None

    # 1. Cada pedaço vai para o prédio maior mais próximo que encosta (empate: o maior, depois a referência).
    parts_of: dict[int, list[int]] = defaultdict(list)
    orphans: list[int] = []
    for i in candidates:
        fp = items[i][1]
        best: tuple[tuple[float, float, str], int] | None = None
        if host_tree is not None:
            for k in host_tree.query(fp.buffer(ANNEX_TOUCH_DISTANCE_M), predicate="intersects"):
                h = hosts[int(k)]
                d = fp.distance(items[h][1])
                if d > ANNEX_TOUCH_DISTANCE_M:
                    continue
                key = (round(d, 3), -round(items[h][2], 3), items[h][0].ref.key)
                if best is None or key < best[0]:
                    best = (key, h)
        if best is None:
            orphans.append(i)
        else:
            parts_of[best[1]].append(i)

    # 2. Pedaços sem prédio maior: grupos de pedaços encostados entre si (componentes conexos).
    parent = {i: i for i in orphans}

    def find(i: int) -> int:
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    if orphans:
        orphan_tree = shapely.STRtree([items[i][1] for i in orphans])
        for a, i in enumerate(orphans):
            for k in orphan_tree.query(items[i][1].buffer(ANNEX_TOUCH_DISTANCE_M), predicate="intersects"):
                j = orphans[int(k)]
                if j != i and items[i][1].distance(items[j][1]) <= ANNEX_TOUCH_DISTANCE_M:
                    ri, rj = find(i), find(j)
                    if ri != rj:
                        parent[max(ri, rj)] = min(ri, rj)
    groups: dict[int, list[int]] = defaultdict(list)
    for i in orphans:
        groups[find(i)].append(i)

    rejects: list[Reject] = []
    consumed: set[int] = {i for lst in parts_of.values() for i in lst}
    group_parts: dict[int, list[int]] = {}
    grouped = 0
    for members in groups.values():
        members.sort()
        total = sum(items[i][2] for i in members)
        if 2 <= len(members) <= ANNEX_GROUP_MAX_PARTS and total >= ANNEX_GROUP_MIN_AREA_M2:
            # O maior pedaço é a referência do imóvel; empate pela referência.
            host = min(members, key=lambda i: (-round(items[i][2], 3), items[i][0].ref.key))
            group_parts[host] = [i for i in members if i != host]
            consumed.update(group_parts[host])
            grouped += 1
        elif len(members) <= ANNEX_GROUP_MAX_PARTS:
            # Sozinho ou grupo pequeno demais: anexo sem prédio (barracão, cobertura), não vira imóvel.
            for i in members:
                b, _, area = items[i]
                reason = "too_small" if area < MIN_BUILDING_AREA_M2 else "too_small_isolated"
                rejects.append(Reject(b.ref.key, reason, {"area_m2": round(area, 1)}))
                consumed.add(i)
        else:
            # Grupo grande (conjunto denso): sem fusão automática; cada pedaço segue como está, com o mínimo de área.
            for i in members:
                b, _, area = items[i]
                if area < MIN_BUILDING_AREA_M2:
                    rejects.append(Reject(b.ref.key, "too_small", {"area_m2": round(area, 1)}))
                    consumed.add(i)

    pieces: list[Piece] = []
    for i in range(n):
        if i in consumed:
            continue
        b, fp, area = items[i]
        if i in parts_of:
            pieces.append(_merge(b, fp, [(items[j][0], items[j][1]) for j in parts_of[i]], area))
        elif i in group_parts:
            members = group_parts[i]
            pieces.append(_merge(b, fp, [(items[j][0], items[j][1]) for j in members],
                                 area + sum(items[j][2] for j in members)))
        else:
            pieces.append(Piece(b, fp, area))

    counts = {
        "annex_candidates": len(cand_set),
        "annexes_absorbed": sum(len(v) for v in parts_of.values()),
        "annex_groups": grouped,
        "annexes_grouped": sum(len(v) + 1 for v in group_parts.values()),
        "annexes_rejected": len(rejects),
    }
    return pieces, rejects, counts
