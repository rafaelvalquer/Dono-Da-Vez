"""Regras determinísticas versionadas. Alterar qualquer valor aqui exige incrementar RULES_VERSION."""

RULES_VERSION = 1
PROVIDER = "osm"

# Porte de casas: área construída estimada = área do footprint × pavimentos (padrão 1).
SMALL_HOUSE_MAX_AREA_M2 = 150.0
SIZE_UNCERTAINTY_BAND = 0.15  # ±15% do limite reduz a confiança

MIN_BUILDING_AREA_M2 = 15.0
MAX_BUILDING_AREA_M2 = 200_000.0

POI_LINK_MAX_DISTANCE_M = 8.0
ENTRANCE_MAX_DISTANCE_M = 2.0
STREET_MAX_DISTANCE_M = 60.0
OVERLAP_RATIO = 0.5

METERS_PER_LEVEL = 3.0
MIN_HEIGHT_M = 2.0
MAX_HEIGHT_M = 600.0

DEFAULT_HEIGHT_M = {
    "house_small": 6.0,
    "house_large": 8.0,
    "commercial": 7.0,
    "residential_building": 30.0,
    None: 6.0,
}

VISUAL_MODEL = {
    "house_small": "house_small",
    "house_large": "house_large",
    "commercial": "commercial",
    "residential_building": "residential_tower",
    None: "generic",
}

HOUSE_BUILDINGS = {"house", "detached", "semidetached_house", "bungalow", "terrace", "cabin", "farm"}
APARTMENT_BUILDINGS = {"apartments"}
RESIDENTIAL_UNDEFINED_BUILDINGS = {"residential"}
COMMERCIAL_BUILDINGS = {"commercial", "retail", "office", "kiosk", "supermarket", "hotel"}
UNDEFINED_BUILDINGS = {"yes"}
# Tudo que não está nas listas acima é rejeitado como fora do escopo (escola, igreja, garagem, galpão...).

COMMERCIAL_AMENITIES = {
    "restaurant", "cafe", "bar", "pub", "fast_food", "ice_cream", "food_court", "biergarten",
    "pharmacy", "bank", "bureau_de_change", "car_rental", "car_wash", "fuel", "veterinary",
    "dentist", "doctors", "cinema", "nightclub", "internet_cafe",
}
INSTITUTIONAL_AMENITIES = {
    "school", "university", "college", "kindergarten", "hospital", "clinic", "place_of_worship", "police",
    "fire_station", "townhall", "library", "community_centre", "courthouse", "prison", "post_office",
    "social_facility", "public_building", "embassy",
}
COMMERCIAL_KEYS = ("shop", "office", "craft")

EXCLUDED_ENTRANCES = {"emergency", "service", "garage", "exit", "no"}
PRIVATE_ACCESS = {"private", "no"}

STREET_HIGHWAYS = {
    "primary", "primary_link", "secondary", "secondary_link", "tertiary", "tertiary_link", "unclassified",
    "residential", "living_street", "pedestrian", "service", "footway", "trunk", "trunk_link",
}

# Tags preservadas no staging (o restante é descartado).
KEPT_TAG_PREFIXES = ("building", "addr:", "shop", "amenity", "office", "craft", "tourism", "name",
                     "height", "roof:levels", "entrance")
