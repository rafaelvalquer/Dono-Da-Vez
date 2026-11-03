"""Acesso ao banco como dv_importer: somente geo.staging_* e funções geo_api.*."""
from __future__ import annotations

from typing import Any

import psycopg
import shapely
from psycopg.types.json import Jsonb
from shapely.geometry.base import BaseGeometry

from .model import Reject, StagingRow

_INSERT_ROW = """
insert into geo.staging_properties (
  batch_id, canonical_ref, source_refs, category_code, subtype, classification_status, classification_confidence,
  address_label, point, footprint, interaction_point, interaction_quality, height_m, height_source, visual_model,
  units_count, eligibility, eligibility_reason, source_tags)
values (%s, %s, %s, %s, %s, %s, %s, %s,
        extensions.st_geomfromewkb(decode(%s, 'hex')),
        extensions.st_geomfromewkb(decode(%s, 'hex')),
        extensions.st_geomfromewkb(decode(%s, 'hex')),
        %s, %s, %s, %s, %s, %s, %s, %s)
"""
_INSERT_REJECT = "insert into geo.staging_rejects (batch_id, source_ref, reason, detail) values (%s, %s, %s, %s)"


def ewkb_hex(geom: BaseGeometry | None) -> str | None:
    if geom is None:
        return None
    return shapely.to_wkb(shapely.set_srid(geom, 4326), hex=True, include_srid=True)


class GeoDb:
    def __init__(self, url: str):
        # prepare_threshold=None: compatível com pooler em modo transação.
        self.conn = psycopg.connect(url, autocommit=True, prepare_threshold=None, application_name="dv-geo-importer")

    def close(self) -> None:
        self.conn.close()

    def __enter__(self) -> GeoDb:
        return self

    def __exit__(self, *exc: object) -> None:
        self.close()

    def _one(self, sql: str, *params: Any) -> Any:
        with self.conn.cursor() as cur:
            cur.execute(sql, params)
            row = cur.fetchone()
            return row[0] if row else None

    def get_region(self, code: str) -> dict | None:
        return self._one("select geo_api.get_region(%s)", code)

    def upsert_region(self, code: str, name: str, polygon_geojson: dict) -> dict:
        return self._one("select geo_api.upsert_region(%s, %s, %s)", code, name, Jsonb(polygon_geojson))

    def begin_batch(self, region: str, provider: str, url: str | None, version: str | None, sha256: str,
                    rules_version: int) -> dict:
        return self._one("select geo_api.begin_batch(%s, %s, %s, %s, %s, %s::int)",
                         region, provider, url, version, sha256, rules_version)

    def stage(self, batch_id: str, rows: list[StagingRow], rejects: list[Reject], chunk: int = 1000) -> None:
        """Grava o lote inteiro em uma transação (COPY não é suportado em tabelas com RLS)."""
        with self.conn.transaction(), self.conn.cursor() as cur:
            for start in range(0, len(rows), chunk):
                cur.executemany(_INSERT_ROW, [(
                    batch_id, r.canonical_ref, Jsonb([s.to_json() for s in r.source_refs]), r.category_code, r.subtype,
                    r.classification_status, r.classification_confidence, r.address_label,
                    ewkb_hex(r.point), ewkb_hex(r.footprint), ewkb_hex(r.interaction_point),
                    r.interaction_quality, r.height_m, r.height_source, r.visual_model, r.units_count,
                    r.eligibility, r.eligibility_reason, Jsonb(r.source_tags),
                ) for r in rows[start:start + chunk]])
            for start in range(0, len(rejects), chunk):
                cur.executemany(_INSERT_REJECT, [(batch_id, j.source_ref, j.reason, Jsonb(j.detail))
                                                 for j in rejects[start:start + chunk]])

    def complete_staging(self, batch_id: str, counts: dict, report: dict) -> dict:
        return self._one("select geo_api.complete_staging(%s::uuid, %s, %s)", batch_id, Jsonb(counts), Jsonb(report))

    def validate_batch(self, batch_id: str) -> dict:
        return self._one("select geo_api.validate_batch(%s::uuid)", batch_id)

    def publish_batch(self, batch_id: str) -> dict:
        return self._one("select geo_api.publish_batch(%s::uuid)", batch_id)

    def fail_batch(self, batch_id: str, errors: list[dict]) -> None:
        self._one("select geo_api.fail_batch(%s::uuid, %s)::text", batch_id, Jsonb(errors))
