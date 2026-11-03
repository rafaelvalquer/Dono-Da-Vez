import shapely
from shapely.geometry import LineString, Point, box

from dv_importer.geometry import LocalProjection, interaction_point, polygon_from_ring
from dv_importer.model import PointFeature, SourceRef


def test_self_intersecting_ring_is_repaired_into_polygons():
    geom, repaired, reason = polygon_from_ring([(0, 0), (1, 1), (1, 0), (0, 1), (0, 0)])
    assert reason is None and repaired
    assert geom.geom_type == "MultiPolygon" and geom.is_valid and round(geom.area, 6) == 0.5


def test_degenerate_rings_are_rejected():
    assert polygon_from_ring([(0, 0), (1, 1), (0, 0)])[2] == "not_closed"
    assert polygon_from_ring([(0, 0), (1, 1), (1, 1), (0, 0)])[2] == "degenerate"


def test_projection_roundtrip_and_metric_area():
    proj = LocalProjection(-46.69, -23.556)
    sq = box(-46.69, -23.556, -46.69 + 10 / 102050, -23.556 + 10 / 110800)
    assert 98 < proj.to_m(sq).area < 102
    back = proj.to_ll(proj.to_m(Point(-46.69, -23.556)))
    assert abs(back.x + 46.69) < 1e-9 and abs(back.y + 23.556) < 1e-9


def _entrance(i, x, y, **tags):
    return PointFeature(SourceRef("node", i), {"entrance": "yes", **tags}, Point(x, y)), Point(x, y)


def test_interaction_prefers_main_entrance_then_street_edge_then_centroid():
    fp = box(0, 10, 10, 20)
    street = [LineString([(-100, 0), (100, 0)])]
    tree = shapely.STRtree(street)

    side, side_pt = _entrance(1, 0, 15)
    main, main_pt = _entrance(2, 5, 20, entrance="main")
    pt, quality, ref = interaction_point(fp, [(side, side_pt, False), (main, main_pt, False)], tree, street)
    assert quality == "entrance_mapped" and ref.source_id == 2 and ref.role == "entrance"

    private, private_pt = _entrance(3, 5, 10, access="private")
    pt, quality, ref = interaction_point(fp, [(private, private_pt, True)], tree, street)
    assert quality == "derived_edge" and ref is None and pt.y == 10  # face voltada para a rua

    far = box(0, 100, 10, 110)
    pt, quality, _ = interaction_point(far, [], tree, street)
    assert quality == "centroid_fallback" and far.contains(pt)
