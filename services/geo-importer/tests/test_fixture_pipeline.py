"""Pipeline completo sobre a fixture OSM (sem banco). Requer pyosmium."""
from pathlib import Path

import pytest

pytest.importorskip("osmium")

from dv_importer.build import build  # noqa: E402
from dv_importer.parse import read_osm  # noqa: E402
from dv_importer.source import load_region  # noqa: E402

FIXTURES = Path(__file__).resolve().parent.parent / "fixtures"


@pytest.fixture(scope="module")
def result():
    region, _, _ = load_region(FIXTURES / "sample-region.geojson")
    return build(read_osm(FIXTURES / "sample.osm"), region)


def by_ref(result):
    return {r.canonical_ref: r for r in result.rows}


def test_expected_properties_and_categories(result):
    rows = by_ref(result)
    expected = {
        "osm:way:101": ("house_small", "resolved", "eligible", "derived_edge"),
        "osm:way:102": ("house_large", "resolved", "eligible", "entrance_mapped"),
        "osm:way:103": ("residential_building", "resolved", "eligible", "derived_edge"),
        "osm:way:104": ("commercial", "resolved", "eligible", "derived_edge"),
        "osm:way:105": (None, "undefined", "pending_classification", "derived_edge"),
        "osm:way:106": (None, "residential_undefined", "pending_classification", "derived_edge"),
        "osm:way:107": (None, "mixed_use_pending", "pending_classification", "derived_edge"),
        "osm:way:108": ("house_small", "resolved", "under_review", "centroid_fallback"),
        "osm:way:109": ("house_small", "resolved", "eligible", "derived_edge"),
        "osm:way:111": ("commercial", "resolved", "eligible", "derived_edge"),
        "osm:node:3003": ("commercial", "resolved", "eligible", "poi_point"),
        "osm:relation:5001": ("commercial", "resolved", "eligible", "derived_edge"),
    }
    assert set(rows) == set(expected)
    for ref, (cat, status, elig, quality) in expected.items():
        r = rows[ref]
        assert (r.category_code, r.classification_status, r.eligibility, r.interaction_quality) == \
            (cat, status, elig, quality), ref


def test_details(result):
    rows = by_ref(result)
    assert rows["osm:way:101"].address_label == "Rua Fixture, 10"
    assert rows["osm:way:105"].address_label == "Rua Fixture, 22"  # nó de endereço dentro do contorno
    assert rows["osm:way:103"].units_count == 48 and rows["osm:way:103"].height_source == "levels"
    assert rows["osm:way:104"].subtype == "bakery"
    assert rows["osm:way:111"].subtype == "office"
    assert rows["osm:relation:5001"].subtype == "clothes"
    assert rows["osm:node:3003"].subtype == "cafe" and rows["osm:node:3003"].footprint is None
    assert rows["osm:way:109"].classification_confidence == 0.85  # geometria reparada
    roles = {(s.key, s.role) for s in rows["osm:way:102"].source_refs}
    assert ("osm:node:2001", "entrance") in roles
    assert ("osm:node:3001", "poi") in {(s.key, s.role) for s in rows["osm:way:104"].source_refs}
    # Cada POI comercial vira no máximo uma propriedade (sem patrimônio duplicado).
    poi_refs = [s.key for r in result.rows for s in r.source_refs if s.object_type == "node" and s.role in ("poi", "primary")]
    assert len(poi_refs) == len(set(poi_refs))


def test_rejects(result):
    reasons = {r.source_ref: r.reason for r in result.rejects}
    assert reasons["osm:way:110"] == "too_small"
    assert reasons["osm:way:112"] == "institutional"
    assert reasons["osm:way:113"] == "not_of_interest"


def test_interaction_point_within_80m_of_footprint(result):
    from dv_importer.geometry import LocalProjection
    proj = LocalProjection(-46.69, -23.57)
    for r in result.rows:
        if r.footprint is not None:
            assert proj.to_m(r.footprint).distance(proj.to_m(r.interaction_point)) < 1.0, r.canonical_ref


def test_build_is_deterministic(result):
    region, _, _ = load_region(FIXTURES / "sample-region.geojson")
    again = build(read_osm(FIXTURES / "sample.osm"), region)
    assert [(r.canonical_ref, r.interaction_point.wkt, r.classification_confidence) for r in again.rows] == \
           [(r.canonical_ref, r.interaction_point.wkt, r.classification_confidence) for r in result.rows]
    assert again.counts == result.counts
