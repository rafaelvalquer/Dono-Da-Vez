"""Geometria: reparo seguro, projeção métrica local (UTM) e ponto de interação."""
from __future__ import annotations

import math

import numpy as np
import shapely
from pyproj import CRS, Transformer
from shapely.geometry import MultiPolygon, Point, Polygon
from shapely.geometry.base import BaseGeometry
from shapely.ops import nearest_points

from .config import ENTRANCE_MAX_DISTANCE_M, EXCLUDED_ENTRANCES, PRIVATE_ACCESS, STREET_MAX_DISTANCE_M
from .model import PointFeature, SourceRef


def to_multipolygon(geom: BaseGeometry) -> MultiPolygon | None:
    """Mantém apenas partes poligonais; descarta linhas/pontos residuais de make_valid."""
    if geom is None or geom.is_empty:
        return None
    if isinstance(geom, Polygon):
        return MultiPolygon([geom])
    if isinstance(geom, MultiPolygon):
        return geom
    polys: list[Polygon] = []
    for part in getattr(geom, "geoms", []):
        mp = to_multipolygon(part)
        if mp is not None:
            polys.extend(mp.geoms)
    return MultiPolygon(polys) if polys else None


def polygon_from_ring(coords: list[tuple[float, float]]) -> tuple[MultiPolygon | None, bool, str | None]:
    """Retorna (geometria, reparada?, motivo_de_rejeição)."""
    if len(coords) < 4 or coords[0] != coords[-1]:
        return None, False, "not_closed"
    if len(set(coords)) < 3:
        return None, False, "degenerate"
    poly = Polygon(coords)
    if poly.is_valid:
        return to_multipolygon(poly), False, None
    fixed = to_multipolygon(shapely.make_valid(poly))
    if fixed is None or fixed.area == 0:
        return None, False, "invalid_geometry"
    return fixed, True, None


def repair(geom: BaseGeometry) -> tuple[MultiPolygon | None, bool]:
    if geom.is_valid:
        return to_multipolygon(geom), False
    return to_multipolygon(shapely.make_valid(geom)), True


class LocalProjection:
    """Projeção UTM da zona da região: distâncias e áreas em metros."""

    def __init__(self, lon: float, lat: float):
        zone = int(math.floor((lon + 180) / 6) + 1)
        epsg = (32600 if lat >= 0 else 32700) + zone
        self.crs = CRS.from_epsg(epsg)
        self._fwd = Transformer.from_crs(4326, self.crs, always_xy=True)
        self._inv = Transformer.from_crs(self.crs, 4326, always_xy=True)

    def _apply(self, tr: Transformer, geom: BaseGeometry) -> BaseGeometry:
        def fn(coords: np.ndarray) -> np.ndarray:
            x, y = tr.transform(coords[:, 0], coords[:, 1])
            return np.column_stack([x, y])
        return shapely.transform(geom, fn)

    def to_m(self, geom: BaseGeometry) -> BaseGeometry:
        return self._apply(self._fwd, geom)

    def to_ll(self, geom: BaseGeometry) -> BaseGeometry:
        return self._apply(self._inv, geom)


def usable_entrance(e: PointFeature) -> bool:
    if e.tags.get("access") in PRIVATE_ACCESS:
        return False
    return e.tags.get("entrance", e.tags.get("door", "yes")) not in EXCLUDED_ENTRANCES


def interaction_point(footprint_m: BaseGeometry, entrances: list[tuple[PointFeature, BaseGeometry, bool]],
                      street_tree: shapely.STRtree | None, streets_m: list[BaseGeometry]
                      ) -> tuple[BaseGeometry, str, SourceRef | None]:
    """Ponto (em metros) onde o jogador precisa estar a até 80 m.

    entrances: (feature, ponto_em_metros, é_vértice_do_contorno).
    Prioridade: entrada mapeada → borda voltada para a rua → centroide (vira revisão).
    """
    boundary = footprint_m.boundary
    best: tuple[int, float, PointFeature, BaseGeometry] | None = None
    for feat, pt, explicit in entrances:
        if not usable_entrance(feat):
            continue
        d = boundary.distance(pt)
        if not explicit and d > ENTRANCE_MAX_DISTANCE_M:
            continue
        rank = 0 if feat.tags.get("entrance") == "main" else 1
        if best is None or (rank, d, feat.ref.source_id) < (best[0], best[1], best[2].ref.source_id):
            best = (rank, d, feat, pt)
    if best is not None:
        return best[3], "entrance_mapped", best[2].ref.with_role("entrance")

    if street_tree is not None and streets_m:
        idx, dist = street_tree.query_nearest(footprint_m, max_distance=STREET_MAX_DISTANCE_M,
                                              return_distance=True, all_matches=False)
        if len(idx) > 0:
            street = streets_m[int(idx[0])]
            on_edge, _ = nearest_points(boundary, street)
            return on_edge, "derived_edge", None

    return footprint_m.representative_point(), "centroid_fallback", None


def round_point(p: BaseGeometry, digits: int = 7) -> Point:
    return Point(round(p.x, digits), round(p.y, digits))
