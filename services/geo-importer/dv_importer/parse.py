"""Leitura do OSM (PBF ou XML) em fluxo com pyosmium; somente tags da lista de interesse."""
from __future__ import annotations

from pathlib import Path

import shapely
from shapely.geometry import LineString, Point

from .classify import is_commercial, is_institutional
from .config import STREET_HIGHWAYS
from .geometry import polygon_from_ring, repair
from .model import Building, Extracted, PointFeature, Reject, SourceRef, Street


def _tags(obj) -> dict[str, str]:
    return {t.k: t.v for t in obj.tags}


def _is_building(tags: dict[str, str]) -> bool:
    b = tags.get("building")
    return bool(b) and b != "no"


def source_version(path: Path) -> str | None:
    """Carimbo de replicação do cabeçalho (extratos Geofabrik), quando existir."""
    import osmium

    reader = osmium.io.Reader(str(path), osmium.osm.osm_entity_bits.NOTHING)
    try:
        header = reader.header()
        return header.get("osmosis_replication_timestamp") or header.get("timestamp") or None
    finally:
        reader.close()


def read_osm(path: Path) -> Extracted:
    import osmium
    from osmium.geom import WKBFactory

    wkb = WKBFactory()
    ex = Extracted()
    fp = osmium.FileProcessor(str(path)).with_locations().with_areas()
    for obj in fp:
        kind = obj.type_str()
        if kind == "n":
            tags = _tags(obj)
            if not tags:
                continue
            ref = SourceRef("node", obj.id, obj.version or None)
            pt = Point(obj.location.lon, obj.location.lat)
            if is_commercial(tags) or is_institutional(tags):
                ex.pois.append(PointFeature(ref, tags, pt))
            elif "entrance" in tags or "door" in tags:
                ex.entrances.append(PointFeature(ref, tags, pt))
            elif "addr:housenumber" in tags and not _is_building(tags):
                ex.addresses.append(PointFeature(ref, tags, pt))
            elif _is_building(tags):
                ex.rejects.append(Reject(ref.key, "building_as_node"))
        elif kind == "w":
            tags = _tags(obj)
            if not tags:
                continue
            ref = SourceRef("way", obj.id, obj.version or None)
            try:
                coords = [(n.lon, n.lat) for n in obj.nodes]
            except osmium.InvalidLocationError:
                if _is_building(tags):
                    ex.rejects.append(Reject(ref.key, "missing_nodes"))
                continue
            if _is_building(tags) and "building:part" not in tags:
                geom, repaired, reason = polygon_from_ring(coords)
                if geom is None:
                    ex.rejects.append(Reject(ref.key, reason or "invalid_geometry"))
                    continue
                ex.buildings.append(Building(ref, tags, geom, frozenset(n.ref for n in obj.nodes), repaired))
            elif tags.get("highway") in STREET_HIGHWAYS and len(coords) >= 2:
                ex.streets.append(Street(ref, LineString(coords)))
        elif kind == "a":
            # Construções em relações multipolygon (as de way já foram tratadas acima).
            if obj.from_way():
                continue
            tags = _tags(obj)
            if not _is_building(tags):
                continue
            ref = SourceRef("relation", obj.orig_id(), obj.version or None)
            try:
                geom = shapely.from_wkb(wkb.create_multipolygon(obj))
            except Exception:  # noqa: BLE001 — área que o libosmium não consegue montar
                ex.rejects.append(Reject(ref.key, "invalid_geometry"))
                continue
            fixed, repaired = repair(geom)
            if fixed is None:
                ex.rejects.append(Reject(ref.key, "invalid_geometry"))
                continue
            ex.buildings.append(Building(ref, tags, fixed, frozenset(), repaired))
    return ex

