"""Importação real contra banco local/CI (nunca produção). Ativado por DV_TEST_IMPORTER_DB_URL."""
import os
from pathlib import Path

import pytest

DB_URL = os.environ.get("DV_TEST_IMPORTER_DB_URL")
pytestmark = pytest.mark.skipif(not DB_URL, reason="DV_TEST_IMPORTER_DB_URL não definido")
pytest.importorskip("osmium")

from dv_importer.pipeline import ImportArgs, run_import  # noqa: E402

FIXTURES = Path(__file__).resolve().parent.parent / "fixtures"


def test_import_is_idempotent_and_publishes_once(tmp_path):
    args = ImportArgs(region_code="fixture", region_name=None, region_geojson=FIXTURES / "sample-region.geojson",
                      pbf=FIXTURES / "sample.osm", url=None, db_url=DB_URL, workdir=tmp_path)
    first = run_import(args)
    assert first["status"] == "published" or first.get("skipped")
    second = run_import(args)
    assert second["skipped"] is True and second["batch_id"] == first["batch_id"]
