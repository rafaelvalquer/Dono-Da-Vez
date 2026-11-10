from dv_importer.classify import LinkedPoi, classify_building, classify_poi
from dv_importer.normalize import address_label, height_for, parse_height, subtype_of, text
from dv_importer.config import COMMERCIAL_AMENITIES


def test_house_size_rule_uses_estimated_floor_area():
    small = classify_building({"building": "house"}, [], 100.0)
    large = classify_building({"building": "house"}, [], 210.0)
    two_floors = classify_building({"building": "house", "building:levels": "2"}, [], 100.0)
    assert small.category == "house_small" and small.confidence == 0.9
    assert large.category == "house_large"
    assert two_floors.category == "house_large"  # 200 m² construídos


def test_house_near_threshold_has_lower_confidence():
    c = classify_building({"building": "detached"}, [], 150.0)
    assert c.category == "house_small" and c.confidence == 0.75


def test_apartments_are_one_asset_and_keep_units():
    c = classify_building({"building": "apartments", "building:flats": "48"}, [], 600.0)
    assert (c.category, c.status, c.units) == ("residential_building", "resolved", 48)


def test_shop_inside_building_yes_is_commercial_with_subtype():
    c = classify_building({"building": "yes"}, [LinkedPoi({"shop": "bakery"}, "inside")], 180.0)
    assert (c.category, c.subtype, c.confidence) == ("commercial", "bakery", 0.85)


def test_office_is_commercial_office():
    c = classify_building({"building": "commercial"}, [LinkedPoi({"office": "company"}, "inside")], 150.0)
    assert (c.category, c.subtype, c.confidence) == ("commercial", "office", 0.9)


def test_mixed_commercial_subtypes_have_no_single_subtype():
    pois = [LinkedPoi({"shop": "bakery"}, "inside"), LinkedPoi({"amenity": "pharmacy"}, "inside")]
    c = classify_building({"building": "yes"}, pois, 300.0)
    assert (c.category, c.subtype, c.units) == ("commercial", None, 2)


def test_ambiguous_cases_are_not_tradeable():
    assert classify_building({"building": "yes"}, [], 100.0).status == "undefined"
    assert classify_building({"building": "residential"}, [], 100.0).status == "residential_undefined"
    assert classify_building({"building": "yes", "height": "40"}, [], 400.0).status == "undefined"  # alto sem uso
    assert classify_building({"building": "yes", "height": "5"}, [], 300.0).status == "undefined"   # térreo grande
    assert classify_building({"building": "yes", "height": "5"}, [], 30.0).status == "undefined"    # pequeno demais
    assert classify_building({"building": "yes", "height": "1"}, [], 100.0).status == "undefined"   # altura implausível
    mixed = classify_building({"building": "apartments"}, [LinkedPoi({"shop": "convenience"}, "inside")], 400.0)
    assert (mixed.category, mixed.status) == (None, "mixed_use_pending")


def test_low_building_with_house_area_is_an_inferred_house():
    one = classify_building({"building": "yes", "height": "4.5"}, [], 100.0)
    two = classify_building({"building": "yes", "building:levels": "2"}, [], 100.0)
    near = classify_building({"building": "yes", "height": "3"}, [], 140.0)
    by_height = classify_building({"building": "residential", "height": "7"}, [], 90.0)
    assert (one.category, one.status, one.confidence) == ("house_small", "inferred", 0.55)
    assert two.category == "house_large"  # 200 m² construídos
    assert (near.category, near.confidence) == ("house_small", 0.5)
    assert (by_height.category, by_height.status) == ("house_large", "inferred")  # 7 m ≈ 2 andares
    assert classify_building({"building": "yes", "building:levels": "3"}, [], 100.0).status == "undefined"


def test_tall_residential_is_an_inferred_building_only_when_declared_residential():
    c = classify_building({"building": "residential", "building:levels": "8", "building:flats": "32"}, [], 400.0)
    assert (c.category, c.status, c.confidence, c.units) == ("residential_building", "inferred", 0.6, 32)
    assert classify_building({"building": "residential", "height": "20"}, [], 120.0).status == "residential_undefined"
    assert classify_building({"building": "residential", "height": "10"}, [], 300.0).status == "residential_undefined"


def test_declared_use_wins_over_inference():
    shop = classify_building({"building": "yes", "height": "4"}, [LinkedPoi({"shop": "bakery"}, "inside")], 100.0)
    assert (shop.category, shop.status) == ("commercial", "resolved")
    mixed = classify_building({"building": "residential", "height": "4"}, [LinkedPoi({"shop": "bakery"}, "inside")], 100.0)
    assert mixed.status == "mixed_use_pending"
    assert classify_building({"building": "house", "height": "4"}, [], 100.0).status == "resolved"


def test_out_of_scope_buildings_are_rejected():
    assert classify_building({"building": "school", "amenity": "school"}, [], 900.0) == "institutional"
    assert classify_building({"building": "garage"}, [], 60.0) == "not_of_interest"
    assert classify_building({"building": "yes"}, [LinkedPoi({"amenity": "place_of_worship"}, "inside")], 300.0) \
        == "institutional"


def test_standalone_poi():
    assert classify_poi({"amenity": "cafe"}).subtype == "cafe"
    assert classify_poi({"amenity": "school"}) is None


def test_height_parsing_and_sources():
    assert parse_height("12") == 12.0
    assert parse_height("12,5 m") == 12.5
    assert round(parse_height("40'"), 2) == 12.19
    assert parse_height("abc") is None
    assert height_for({"height": "18"}, "commercial") == (18.0, "source")
    assert height_for({"building:levels": "12"}, "residential_building") == (36.0, "levels")
    assert height_for({}, "house_small") == (6.0, "stylized")
    assert height_for({"height": "9000"}, None) == (6.0, "stylized")


def test_text_and_address_normalization():
    assert text("  Rua   das\tFlores ") == "Rua das Flores"
    assert address_label({"addr:street": "Rua A", "addr:housenumber": "10"}) == "Rua A, 10"
    assert address_label({"addr:housenumber": "10"}) is None
    assert subtype_of({"shop": "Açougue Bom"}, COMMERCIAL_AMENITIES) == "acougue_bom"
