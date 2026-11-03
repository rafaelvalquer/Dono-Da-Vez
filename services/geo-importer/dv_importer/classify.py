"""Classificação determinística (tabela 6.3 do projeto técnico)."""
from __future__ import annotations

from dataclasses import dataclass

from .config import (APARTMENT_BUILDINGS, COMMERCIAL_AMENITIES, COMMERCIAL_BUILDINGS, COMMERCIAL_KEYS,
                     HOUSE_BUILDINGS, INSTITUTIONAL_AMENITIES, RESIDENTIAL_UNDEFINED_BUILDINGS,
                     SIZE_UNCERTAINTY_BAND, SMALL_HOUSE_MAX_AREA_M2, UNDEFINED_BUILDINGS)
from .normalize import parse_levels, subtype_of


@dataclass(frozen=True)
class Classification:
    category: str | None
    subtype: str | None
    status: str  # resolved | pending_size | undefined | residential_undefined | mixed_use_pending
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
        return Classification(None, None, "residential_undefined", 0.3)

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
        return Classification(None, None, "undefined", 0.2)

    return "not_of_interest"


def classify_poi(tags: dict[str, str]) -> Classification | None:
    """Ponto comercial sem construção associada vira imóvel pontual (interação no próprio ponto)."""
    if not is_commercial(tags):
        return None
    return Classification("commercial", subtype_of(tags, COMMERCIAL_AMENITIES), "resolved", 0.6)
