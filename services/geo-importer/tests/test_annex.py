"""Anexos: junção de pedaços pequenos ao prédio vizinho (geometria sintética, em metros)."""
from shapely.geometry import MultiPolygon, box

from dv_importer.annex import absorb_annexes, is_annex_candidate
from dv_importer.model import Building, SourceRef


def piece(way_id: int, x0: float, y0: float, x1: float, y1: float, **tags: str):
    g = box(x0, y0, x1, y1)
    b = Building(SourceRef("way", way_id), {"building": "yes"} | tags, MultiPolygon([g]),
                 frozenset({way_id * 10, way_id * 10 + 1}))
    return b, g, g.area


def run(*items, with_poi=frozenset()):
    pieces, rejects, counts = absorb_annexes(list(items), set(with_poi))
    return ({p.building.ref.source_id: sorted(a.ref.source_id for a in p.parts) for p in pieces},
            {r.source_ref: r.reason for r in rejects}, counts, {p.building.ref.source_id: p for p in pieces})


def test_candidates_are_small_pieces_without_declared_use():
    b, _, area = piece(1, 0, 0, 5, 5)
    assert is_annex_candidate(b, area, has_poi=False)
    assert not is_annex_candidate(b, area, has_poi=True)
    assert not is_annex_candidate(*piece(2, 0, 0, 5, 5, building="house")[::2], has_poi=False)
    assert not is_annex_candidate(*piece(3, 0, 0, 5, 5, shop="kiosk")[::2], has_poi=False)
    assert not is_annex_candidate(*piece(4, 0, 0, 5, 5, **{"addr:housenumber": "12"})[::2], has_poi=False)
    assert is_annex_candidate(*piece(5, 0, 0, 5, 5, building="garage")[::2], has_poi=False)
    assert not is_annex_candidate(*piece(6, 0, 0, 7, 6)[::2], has_poi=False)  # 42 m²: já é um prédio


def test_annex_joins_the_touching_building_and_keeps_its_identity():
    parts, rejects, counts, pieces = run(piece(1, 0, 0, 10, 10), piece(2, 10, 2, 14, 6), piece(3, 2, 10.5, 6, 14))
    assert parts == {1: [2, 3]} and rejects == {}
    host = pieces[1]
    assert host.area == 100  # a categoria segue pelo prédio principal
    assert round(host.fp_m.area) == 100 + 16 + 14
    assert host.building.node_ids == frozenset({10, 11, 20, 21, 30, 31})
    assert counts["annexes_absorbed"] == 2


def test_annex_goes_to_the_nearest_then_largest_neighbour():
    # Encostado no 1, a 0,5 m do 2 (maior): vence o mais próximo.
    parts, *_ = run(piece(1, 0, 0, 10, 10), piece(2, 14, 0, 30, 10), piece(3, 10, 2, 13.5, 6))
    assert parts == {1: [3], 2: []}
    # Encostado nos dois: vence o maior.
    parts, *_ = run(piece(1, 0, 0, 10, 10), piece(2, 14, 0, 30, 10), piece(3, 10, 2, 14, 6))
    assert parts == {1: [], 2: [3]}


def test_far_pieces_are_not_joined():
    parts, rejects, *_ = run(piece(1, 0, 0, 10, 10), piece(2, 11.5, 0, 16, 5))
    assert parts == {1: []} and rejects == {"osm:way:2": "too_small_isolated"}


def test_small_pieces_touching_each_other_become_one_property_when_big_enough():
    parts, rejects, counts, pieces = run(piece(1, 0, 0, 6, 5), piece(2, 6, 0, 10, 5), piece(3, 10, 0, 13, 4))
    assert parts == {1: [2, 3]} and rejects == {}
    assert pieces[1].area == 30 + 20 + 12  # grupo: a classificação usa a área somada
    assert counts["annex_groups"] == 1
    parts, rejects, *_ = run(piece(1, 0, 0, 4, 4), piece(2, 4, 0, 8, 4))  # 32 m² juntos: pouco
    assert parts == {} and rejects == {"osm:way:1": "too_small_isolated", "osm:way:2": "too_small_isolated"}


def test_isolated_tiny_piece_keeps_the_too_small_reason():
    _, rejects, *_ = run(piece(1, 0, 0, 3, 3))
    assert rejects == {"osm:way:1": "too_small"}


def test_dense_clusters_are_left_alone():
    row = [piece(i + 1, i * 4, 0, i * 4 + 4, 5) for i in range(11)]  # 11 pedaços encostados em fileira
    parts, rejects, counts, _ = run(*row)
    assert parts == {i + 1: [] for i in range(11)} and rejects == {} and counts["annex_groups"] == 0
    # Mesmo sem fusão, pedaço menor que o mínimo não vira imóvel.
    parts, rejects, *_ = run(*row, piece(12, 44, 0, 47, 3))
    assert 12 not in parts and rejects == {"osm:way:12": "too_small"}


def test_piece_with_poi_is_never_absorbed():
    parts, rejects, *_ = run(piece(1, 0, 0, 10, 10), piece(2, 10, 0, 15, 5), with_poi={1})
    assert parts == {1: [], 2: []} and rejects == {}


def test_result_is_independent_of_input_order():
    items = [piece(1, 0, 0, 10, 10), piece(2, 10, 2, 14, 6), piece(3, 20, 0, 24, 5), piece(4, 24, 0, 30, 5)]
    a, b = run(*items), run(*reversed(items))
    assert a[0] == b[0] == {1: [2], 4: [3]}  # grupo 3+4 (50 m²): o maior pedaço é a referência
    assert a[1] == b[1] and a[2] == b[2]
