"""Pipeline completo: registro → recorte → leitura → consolidação → staging → validação → publicação."""
from __future__ import annotations

import logging
import tempfile
from dataclasses import dataclass
from pathlib import Path

from .build import build
from .config import PROVIDER, RULES_VERSION
from .model import BuildResult
from .parse import read_osm, source_version
from .source import download, extract, load_region, sha256_file

log = logging.getLogger("dv_importer")


@dataclass
class ImportArgs:
    region_code: str
    region_name: str | None
    region_geojson: Path
    pbf: Path | None
    url: str | None
    db_url: str
    workdir: Path | None = None
    publish: bool = True
    # Em importações grandes, não recalcular o hash do mesmo PBF a cada célula.
    source_sha256: str | None = None


def analyze(pbf: Path, region_geojson: Path, workdir: Path | None = None) -> BuildResult:
    region, _, _ = load_region(region_geojson)
    with tempfile.TemporaryDirectory() as tmp:
        clipped = extract(pbf, region_geojson, workdir or Path(tmp))
        return build(read_osm(clipped), region)


def run_import(a: ImportArgs) -> dict:
    from .db import GeoDb  # psycopg só é necessário quando há banco

    region, region_geom, props = load_region(a.region_geojson)
    name = a.region_name or props.get("name") or a.region_code
    workdir = a.workdir or Path(tempfile.mkdtemp(prefix="dv-import-"))
    src = a.pbf if a.pbf is not None else download(a.url or "", workdir / "download")
    digest = a.source_sha256 or sha256_file(src)
    version = source_version(src)

    with GeoDb(a.db_url) as db:
        db.upsert_region(a.region_code, name, region_geom)
        began = db.begin_batch(a.region_code, PROVIDER, a.url, version, digest, RULES_VERSION)
        batch_id = began["batch_id"]
        if began.get("skipped"):
            log.info("lote já publicado para este arquivo e regras; nada a fazer", extra={"batch_id": batch_id})
            return {"batch_id": batch_id, "status": "published", "skipped": True}
        try:
            result = build(read_osm(extract(src, a.region_geojson, workdir)), region)
            db.stage(batch_id, result.rows, result.rejects)
            db.complete_staging(batch_id, result.counts, result.report)
            validated = db.validate_batch(batch_id)
            out = {"batch_id": batch_id, "status": validated["status"], "diff": validated["diff"],
                   "counts": result.counts, "coverage": result.report["coverage"]}
            if validated["status"] == "approved" and a.publish:
                out["publication"] = db.publish_batch(batch_id)
                out["status"] = "published"
            elif validated["status"] != "approved":
                log.warning("lote aguardando aprovação no painel (remoção acima do limite ou fusões)")
            return out
        except Exception as e:
            db.fail_batch(batch_id, [{"error": type(e).__name__, "message": str(e)[:500]}])
            raise
