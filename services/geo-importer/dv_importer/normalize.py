"""Normalização de textos, alturas e tags."""
from __future__ import annotations

import re
import unicodedata

from .config import (DEFAULT_HEIGHT_M, KEPT_TAG_PREFIXES, MAX_HEIGHT_M, METERS_PER_LEVEL, MIN_HEIGHT_M)

_SPACES = re.compile(r"\s+")
_NUMBER = re.compile(r"^\s*(-?\d+(?:[.,]\d+)?)\s*(m|meters|metres|ft|feet|'|)\s*$", re.IGNORECASE)
_SUBTYPE = re.compile(r"[^a-z0-9_]+")


def text(value: str | None, max_len: int = 200) -> str | None:
    if value is None:
        return None
    v = _SPACES.sub(" ", unicodedata.normalize("NFC", value)).strip()
    return v[:max_len] or None


def parse_height(value: str | None) -> float | None:
    """'12', '12 m', '12,5', "40'", '40 ft', '12;15' (primeiro valor). Retorna metros."""
    if not value:
        return None
    m = _NUMBER.match(value.split(";")[0])
    if not m:
        return None
    n = float(m.group(1).replace(",", "."))
    if m.group(2).lower() in ("ft", "feet", "'"):
        n *= 0.3048
    return n if n > 0 else None


def parse_levels(value: str | None) -> float | None:
    if not value:
        return None
    try:
        n = float(value.split(";")[0].replace(",", ".").strip())
    except ValueError:
        return None
    return n if 0 < n <= 200 else None


def height_for(tags: dict[str, str], category: str | None) -> tuple[float, str]:
    """(altura_m, origem) com origem em source | levels | stylized."""
    h = parse_height(tags.get("building:height") or tags.get("height"))
    if h is not None and MIN_HEIGHT_M <= h <= MAX_HEIGHT_M:
        return round(h, 2), "source"
    levels = parse_levels(tags.get("building:levels"))
    if levels is not None:
        roof = parse_levels(tags.get("roof:levels")) or 0.0
        return round(min(max((levels + roof * 0.5) * METERS_PER_LEVEL, MIN_HEIGHT_M), MAX_HEIGHT_M), 2), "levels"
    return DEFAULT_HEIGHT_M.get(category, DEFAULT_HEIGHT_M[None]), "stylized"


def address_label(tags: dict[str, str]) -> str | None:
    street = text(tags.get("addr:street")) or text(tags.get("addr:place"))
    number = text(tags.get("addr:housenumber"), 20)
    if street and number:
        return f"{street}, {number}"
    return street


def subtype_of(tags: dict[str, str], commercial_amenities: set[str]) -> str | None:
    raw: str | None = None
    if tags.get("shop"):
        raw = tags["shop"]
    elif tags.get("amenity") in commercial_amenities:
        raw = tags["amenity"]
    elif tags.get("office"):
        raw = "office"
    elif tags.get("craft"):
        raw = tags["craft"]
    elif tags.get("tourism") in ("hotel", "hostel", "guest_house", "motel"):
        raw = tags["tourism"]
    elif tags.get("building") == "hotel":
        raw = "hotel"
    if not raw or raw in ("yes", "no"):
        return None
    v = _SUBTYPE.sub("_", unicodedata.normalize("NFKD", raw.split(";")[0]).encode("ascii", "ignore").decode().lower()).strip("_")
    return v[:40] or None


def kept_tags(tags: dict[str, str]) -> dict[str, str]:
    return {k: v[:255] for k, v in sorted(tags.items()) if k.startswith(KEPT_TAG_PREFIXES)}
