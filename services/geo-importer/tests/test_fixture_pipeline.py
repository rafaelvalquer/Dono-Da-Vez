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
        # Regras v2: categoria estimada pela altura e área (negociável, com aviso na ficha).
        "osm:way:114": ("house_small", "inferred", "eligible", "derived_edge"),
        "osm:way:116": ("house_small", "inferred", "eligible", "derived_edge"),
        "osm:way:120": ("commercial", "resolved", "eligible", "derived_edge"),
        "osm:way:121": ("house_large", "inferred", "eligible", "derived_edge"),
        "osm:way:122": ("residential_building", "inferred", "eligible", "derived_edge"),
        "osm:way:123": (None, "undefined", "pending_classification", "derived_edge"),
        "osm:way:125": (None, "undefined", "pending_classification", "derived_edge"),
        "osm:way:126": ("commercial", "resolved", "eligible", "derived_edge"),
        "osm:way:127": ("house_small", "inferred", "eligible", "derived_edge"),
        "osm:way:128": ("commercial", "resolved", "eligible", "derived_edge"),
        "osm:way:130": ("house_small", "inferred", "eligible", "derived_edge"),
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
    assert reasons["osm:way:110"] == "too_small"  # casa declarada pequena demais não é anexo
    assert reasons["osm:way:112"] == "institutional"
    assert reasons["osm:way:113"] == "not_of_interest"
    assert reasons["osm:way:118"] == "too_small_isolated"


def test_inferred_categories_use_height_and_area(result):
    rows = by_ref(result)
    assert rows["osm:way:114"].classification_confidence == 0.55
    assert rows["osm:way:121"].category_code == "house_large"  # 6 m ≈ 2 andares × 100 m²
    assert rows["osm:way:122"].classification_confidence == 0.6
    assert rows["osm:way:122"].height_m == 30.0 and rows["osm:way:122"].height_source == "source"


def test_annexes_become_parts_of_the_neighbour(result):
    from dv_importer.geometry import LocalProjection
    proj = LocalProjection(-46.69, -23.57)
    rows = by_ref(result)
    parts = {r.canonical_ref: sorted(s.key for s in r.source_refs if s.role == "part") for r in result.rows}
    assert parts["osm:way:114"] == ["osm:way:115", "osm:way:119"]  # puxadinho e garagem encostados
    assert parts["osm:way:116"] == ["osm:way:117"]                 # dois pedaços pequenos viram uma casa
    assert parts["osm:way:121"] == ["osm:way:124"]                 # anexo menor que 15 m² também entra
    assert parts["osm:way:120"] == [] and parts["osm:way:125"] == []  # loja e casa com número ficam separadas
    absorbed = {k for v in parts.values() for k in v}
    assert not absorbed & set(rows)
    assert not absorbed & {r.source_ref for r in result.rejects}
    # O desenho cresce (114: 120 + 20 + 20 m²); a categoria continua pela área do prédio principal.
    assert abs(proj.to_m(rows["osm:way:114"].footprint).area - 160) < 1.0
    assert rows["osm:way:114"].source_tags["parts"] == 2
    assert rows["osm:way:114"].source_refs[0].key == "osm:way:114"
    assert rows["osm:way:114"].source_refs[0].role == "primary"
    assert result.counts["annexes_absorbed"] == 4 and result.counts["annex_groups"] == 1


def test_poi_links_are_decided_before_joining_annexes(result):
    rows = by_ref(result)

    def refs(ref, role):
        return [s.key for s in rows[ref].source_refs if s.role == role]

    # POI a poucos metros do contorno também protege: a loja de 25 m² não vira anexo da casa ao lado.
    assert refs("osm:way:126", "poi") == ["osm:node:3006"] and rows["osm:way:126"].subtype == "fast_food"
    assert refs("osm:way:127", "part") == []
    # O anexo cresce o contorno da casa 130 em direção ao POI, mas a loja continua sendo do vizinho 128.
    assert refs("osm:way:130", "part") == ["osm:way:131"] and refs("osm:way:130", "poi") == []
    assert refs("osm:way:128", "poi") == ["osm:node:3007"]


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
