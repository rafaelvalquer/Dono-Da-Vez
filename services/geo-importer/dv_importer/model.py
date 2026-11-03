from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from shapely.geometry.base import BaseGeometry

from .config import PROVIDER


@dataclass(frozen=True)
class SourceRef:
    object_type: str  # node | way | relation
    source_id: int
    version: int | None = None
    role: str = "primary"  # primary | poi | entrance | address

    @property
    def key(self) -> str:
        return f"{PROVIDER}:{self.object_type}:{self.source_id}"

    def with_role(self, role: str) -> SourceRef:
        return SourceRef(self.object_type, self.source_id, self.version, role)

    def to_json(self) -> dict[str, Any]:
        return {"provider": PROVIDER, "object_type": self.object_type, "source_id": self.source_id,
                "source_version": self.version, "role": self.role}


@dataclass
class Building:
    ref: SourceRef
    tags: dict[str, str]
    geom: BaseGeometry  # MultiPolygon em lon/lat
    node_ids: frozenset[int] = frozenset()
    repaired: bool = False


@dataclass
class PointFeature:
    ref: SourceRef
    tags: dict[str, str]
    geom: BaseGeometry  # Point em lon/lat


@dataclass
class Street:
    ref: SourceRef
    geom: BaseGeometry  # LineString em lon/lat


@dataclass
class Reject:
    source_ref: str
    reason: str
    detail: dict[str, Any] = field(default_factory=dict)


@dataclass
class Extracted:
    buildings: list[Building] = field(default_factory=list)
    pois: list[PointFeature] = field(default_factory=list)
    entrances: list[PointFeature] = field(default_factory=list)
    addresses: list[PointFeature] = field(default_factory=list)
    streets: list[Street] = field(default_factory=list)
    rejects: list[Reject] = field(default_factory=list)


@dataclass
class StagingRow:
    canonical_ref: str
    source_refs: list[SourceRef]
    category_code: str | None
    subtype: str | None
    classification_status: str
    classification_confidence: float
    address_label: str | None
    point: BaseGeometry
    footprint: BaseGeometry | None
    interaction_point: BaseGeometry
    interaction_quality: str
    height_m: float
    height_source: str
    visual_model: str
    units_count: int
    eligibility: str
    eligibility_reason: str | None
    source_tags: dict[str, Any]


@dataclass
class BuildResult:
    rows: list[StagingRow]
    rejects: list[Reject]
    counts: dict[str, Any]
    report: dict[str, Any]
