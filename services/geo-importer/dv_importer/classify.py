"""Classificação determinística (tabela 6.3 do projeto técnico)."""
from __future__ import annotations

from dataclasses import dataclass

from .config import (APARTMENT_BUILDINGS, COMMERCIAL_AMENITIES, COMMERCIAL_BUILDINGS, COMMERCIAL_KEYS,
                     HOUSE_BUILDINGS, INFERRED_BUILDING_CONFIDENCE, INFERRED_BUILDING_MIN_AREA_M2,
                     INFERRED_CONFIDENCE, INFERRED_HOUSE_MAX_AREA_M2, INFERRED_HOUSE_MIN_AREA_M2,
                     INFERRED_NEAR_LIMIT_CONFIDENCE, INSTITUTIONAL_AMENITIES, LOW_RISE_MAX_HEIGHT_M, MAX_HEIGHT_M,
                     METERS_PER_LEVEL, MIN_HEIGHT_M, RESIDENTIAL_UNDEFINED_BUILDINGS, SIZE_UNCERTAINTY_BAND,
                     SMALL_HOUSE_MAX_AREA_M2, TALL_MIN_HEIGHT_M, UNDEFINED_BUILDINGS)
from .normalize import parse_height, parse_levels, subtype_of


@dataclass(frozen=True)
class Classification:
    category: str | None
    subtype: str | None
    status: str  # resolved | inferred | pending_size | undefined | residential_undefined | mixed_use_pending
    confidence: float
    units: int = 1


@dataclass(frozen=True)
class LinkedPoi:
    tags: dict[str, str]
    how: str  # explicit | inside | near


def is_commercial(tags: dict[str, str]) -> bool:
    return any(tags.get(k) for k in COMMERCIAL_KEYS) or tags.get("amenity") in COMMERCIAL_AMENITIES \
        or tags.get("tourism") in ("hotel", "hostel", "guest_house", "motel")


def is_institutional(tags: dict[str, str]) -> bool:
    return tags.get("amenity") in INSTITUTIONAL_AMENITIES


def _count(value: str | None) -> int:
    try:
        n = int(float((value or "").split(";")[0].replace(",", ".")))
    except ValueError:
        return 1
    return n if 1 <= n <= 100_000 else 1


def floor_area(footprint_area_m2: float | None, tags: dict[str, str]) -> float | None:
    if footprint_area_m2 is None:
        return None
    return footprint_area_m2 * (parse_levels(tags.get("building:levels")) or 1.0)


def _shape(tags: dict[str, str]) -> tuple[float | None, float | None]:
    """(andares, altura_m) declarados no OSM; valores implausíveis são ignorados."""
    levels = parse_levels(tags.get("building:levels"))
    h = parse_height(tags.get("building:height") or tags.get("height"))
    if h is not None and not MIN_HEIGHT_M <= h <= MAX_HEIGHT_M:
        h = None
    return levels, h


def infer_house(tags: dict[str, str], footprint_area_m2: float | None) -> Classification | None:
    """Regra R1 (v2): prédio baixo (até 2 andares) com área de casa vira casa estimada.

    Só quando o OSM não diz o uso (building=yes/residential, sem comércio). O porte usa a mesma regra das casas
    declaradas: área do contorno × andares (andares declarados, ou estimados pela altura).
    """
    levels, h = _shape(tags)
    if footprint_area_m2 is None or (levels is None and h is None):
        return None
    low = levels <= 2 if levels is not None else h < LOW_RISE_MAX_HEIGHT_M
    if not low or not INFERRED_HOUSE_MIN_AREA_M2 <= footprint_area_m2 <= INFERRED_HOUSE_MAX_AREA_M2:
        return None
    floors = levels if levels is not None else max(1.0, float(int(h / METERS_PER_LEVEL)))
    built = footprint_area_m2 * floors
    near_limit = abs(built - SMALL_HOUSE_MAX_AREA_M2) <= SMALL_HOUSE_MAX_AREA_M2 * SIZE_UNCERTAINTY_BAND
    category = "house_small" if built <= SMALL_HOUSE_MAX_AREA_M2 else "house_large"
    return Classification(category, None, "inferred",
                          INFERRED_NEAR_LIMIT_CONFIDENCE if near_limit else INFERRED_CONFIDENCE)


def infer_tall_residential(tags: dict[str, str], footprint_area_m2: float | None) -> Classification | None:
    """Regra R3 (v2), só para building=residential: 5 andares ou mais com área de prédio vira prédio residencial."""
    levels, h = _shape(tags)
    if footprint_area_m2 is None or (levels is None and h is None):
        return None
    tall = levels >= 5 if levels is not None else h >= TALL_MIN_HEIGHT_M
    if not tall or footprint_area_m2 < INFERRED_BUILDING_MIN_AREA_M2:
        return None
    return Classification("residential_building", None, "inferred", INFERRED_BUILDING_CONFIDENCE,
                          _count(tags.get("building:flats")))


def classify_building(tags: dict[str, str], pois: list[LinkedPoi], footprint_area_m2: float | None
                      ) -> Classification | str:
    """Retorna Classification ou o motivo de rejeição (fora do escopo do jogo)."""
    b = (tags.get("building") or "").strip().lower()
    commercial_pois = [p for p in pois if is_commercial(p.tags)]
    own_commercial = is_commercial(tags) or b in COMMERCIAL_BUILDINGS
    has_commercial = own_commercial or bool(commercial_pois)
    residential = b in HOUSE_BUILDINGS | APARTMENT_BUILDINGS | RESIDENTIAL_UNDEFINED_BUILDINGS

    if not has_commercial and (is_institutional(tags) or any(is_institutional(p.tags) for p in pois)):
        return "institutional"

    # Usos incompatíveis no mesmo edifício: não criar ativos sobrepostos na V1.
    if residential and has_commercial:
        return Classification(None, None, "mixed_use_pending", 0.5, max(1, len(commercial_pois)))

    if b in APARTMENT_BUILDINGS:
        # Unidades reais são preservadas como informação; o prédio inteiro é um único ativo na V1.
        return Classification("residential_building", None, "resolved", 0.9, _count(tags.get("building:flats")))

    if b in HOUSE_BUILDINGS:
        area = floor_area(footprint_area_m2, tags)
        if area is None:
            return Classification(None, None, "pending_size", 0.4)
        near_limit = abs(area - SMALL_HOUSE_MAX_AREA_M2) <= SMALL_HOUSE_MAX_AREA_M2 * SIZE_UNCERTAINTY_BAND
        category = "house_small" if area <= SMALL_HOUSE_MAX_AREA_M2 else "house_large"
        return Classification(category, None, "resolved", 0.75 if near_limit else 0.9)

    if b in RESIDENTIAL_UNDEFINED_BUILDINGS:
        return infer_house(tags, footprint_area_m2) or infer_tall_residential(tags, footprint_area_m2)             or Classification(None, None, "residential_undefined", 0.3)

    if has_commercial:
        subtypes: list[str] = []
        for t in [tags] + [p.tags for p in commercial_pois]:
            s = subtype_of(t, COMMERCIAL_AMENITIES)
            if s and s not in subtypes:
                subtypes.append(s)
        if own_commercial:
            conf = 0.9
        elif all(p.how in ("explicit", "inside") for p in commercial_pois):
            conf = 0.85
        else:
            conf = 0.7
        return Classification("commercial", subtypes[0] if len(subtypes) == 1 else None, "resolved", conf,
                              max(1, len(commercial_pois)))

    if b in UNDEFINED_BUILDINGS:
        # Sem uso declarado: só a casa baixa é estimada. Prédio alto building=yes pode ser escritório ou hotel.
        return infer_house(tags, footprint_area_m2) or Classification(None, None, "undefined", 0.2)

    return "not_of_interest"


def classify_poi(tags: dict[str, str]) -> Classification | None:
    """Ponto comercial sem construção associada vira imóvel pontual (interação no próprio ponto)."""
    if not is_commercial(tags):
        return None
    return Classification("commercial", subtype_of(tags, COMMERCIAL_AMENITIES), "resolved", 0.6)
