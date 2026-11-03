"""A partição precisa ser determinística e nunca exceder o limite de 50 km²."""
from __future__ import annotations

import pytest
from shapely.geometry import box
from shapely.ops import unary_union

from dv_importer.city import split_city
from dv_importer.geometry import LocalProjection


def test_split_city_cells_are_bounded_and_cover_input():
    shape = box(-46.71, -23.62, -46.61, -23.52)
    cells = split_city(shape)
    assert cells
    assert len({c.code for c in cells}) == len(cells)
    projection = LocalProjection(-46.6333, -23.5505)
    for cell in cells:
        assert projection.to_m(cell.geometry).area / 1e6 < 50
        assert cell.code.startswith("sp-3550308-")
    union = unary_union([c.geometry for c in cells])
    assert shape.symmetric_difference(union).area < 1e-9
    assert [c.code for c in cells] == [c.code for c in split_city(shape)]


def test_rejects_oversized_cells():
    with pytest.raises(ValueError):
        split_city(box(-46.7, -23.6, -46.6, -23.5), cell_meters=10000)
