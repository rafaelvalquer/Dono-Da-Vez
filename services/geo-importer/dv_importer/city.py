"""Importação automática do município de São Paulo (IBGE 3550308).

Nenhum imóvel é cadastrado manualmente: a malha municipal vem do IBGE, os
edifícios do OSM/Geofabrik e cada região é publicada pelo pipeline existente.
Usa recortes UTM estáveis de 4 km para respeitar o limite SQL de 50 km².
"""
from __future__ import annotations

import json
import logging
import math
import os
import urllib.request
from dataclasses import dataclass
from pathlib import Path

from shapely.geometry import box, mapping
from shapely.geometry.base import BaseGeometry

from .geometry import LocalProjection, to_multipolygon
from .pipeline import ImportArgs, run_import
from .source import download, extract, load_region, sha256_file

LOG = logging.getLogger("dv_importer.city")
CITY_ID = "3550308"
IBGE_URL = (
    "https://servicodados.ibge.gov.br/api/v3/malhas/municipios/3550308"
    "?formato=application%2Fvnd.geo%2Bjson&qualidade=intermediaria"
)
OSM_URL = "https://download.geofabrik.de/south-america/brazil/sudeste-latest.osm.pbf"
CELL_METERS = 4000
CITY_CENTER = (-46.6333, -23.5505)
# Área municipal aproximada (km²); protege contra resposta errada do IBGE.
MIN_CITY_KM2 = 1000
MAX_CITY_KM2 = 2000


@dataclass(frozen=True)
class CityCell:
    code: str
    name: str
    geometry: BaseGeometry


def download_boundary(destination: Path) -> Path:
    """Cacheia a versão do polígono para manter os IDs estáveis entre importações."""
    if destination.exists():
        return destination
    destination.parent.mkdir(parents=True, exist_ok=True)
    request = urllib.request.Request(IBGE_URL, headers={
        "Accept": "application/vnd.geo+json",
        "User-Agent": "DonoDaVez-geo-importer/1.0",
    })
    with urllib.request.urlopen(request, timeout=120) as response:  # noqa: S310
        payload = response.read(12 * 1024 * 1024)
    data = json.loads(payload.decode("utf-8"))
    tmp = destination.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    # Valida antes de fixar no cache.
    load_city_boundary(tmp)
    tmp.replace(destination)
    return destination


def load_city_boundary(path: Path) -> BaseGeometry:
    geom, _, _ = load_region(path)
    proj = LocalProjection(*CITY_CENTER)
    area = proj.to_m(geom).area / 1e6
    if not (MIN_CITY_KM2 <= area <= MAX_CITY_KM2):
        raise ValueError(f"Malha municipal incorreta: {area:.1f} km² (esperado município 3550308)")
    if not geom.contains(__import__("shapely").geometry.Point(*CITY_CENTER)):
        raise ValueError("Malha municipal não contém o centro de São Paulo")
    return geom


def split_city(city: BaseGeometry, cell_meters: int = CELL_METERS) -> list[CityCell]:
    if cell_meters < 500 or cell_meters > 6000:
        raise ValueError("Tamanho de célula fora do intervalo permitido (500 a 6000 metros)")
    proj = LocalProjection(*CITY_CENTER)
    area = proj.to_m(city)
    minx, miny, maxx, maxy = area.bounds
    result: list[CityCell] = []
    for ix in range(math.floor(minx / cell_meters), math.ceil(maxx / cell_meters)):
        for iy in range(math.floor(miny / cell_meters), math.ceil(maxy / cell_meters)):
            part = area.intersection(box(ix * cell_meters, iy * cell_meters,
                                         (ix + 1) * cell_meters, (iy + 1) * cell_meters))
            if part.is_empty or part.area < 1:
                continue
            geo = to_multipolygon(proj.to_ll(part))
            if geo is None or geo.is_empty:
                continue
            code = f"sp-3550308-{ix}-{iy}"
            result.append(CityCell(code=code, name=f"São Paulo · quadra geográfica {ix}/{iy}", geometry=geo))
    return sorted(result, key=lambda cell: cell.code)


def write_cells(cells: list[CityCell], directory: Path) -> list[tuple[CityCell, Path]]:
    directory.mkdir(parents=True, exist_ok=True)
    output: list[tuple[CityCell, Path]] = []
    for cell in cells:
        path = directory / (cell.code + ".geojson")
        path.write_text(json.dumps({
            "type": "Feature", "properties": {"name": cell.name, "ibge_municipio": CITY_ID},
            "geometry": mapping(cell.geometry),
        }, ensure_ascii=False), encoding="utf-8")
        output.append((cell, path))
    return output


def run_city(*, db_url: str | None, boundary: Path | None = None, pbf: Path | None = None,
             url: str = OSM_URL, workdir: Path, max_cells: int | None = None,
             dry_run: bool = False, publish: bool = True) -> dict:
    """Executa sequencialmente e reaproveita o download e o recorte municipal.

    Publicação preserva IDs e proprietários via geo_api.publish_batch. Um lote que
    exige revisão permanece em 'validated', sem ser publicado silenciosamente.
    """
    if max_cells is not None and max_cells < 1:
        raise ValueError("--max-cells deve ser positivo")
    workdir.mkdir(parents=True, exist_ok=True)
    boundary = boundary or download_boundary(workdir / "sao-paulo-3550308.geojson")
    city = load_city_boundary(boundary)
    cells = split_city(city)
    cell_files = write_cells(cells, workdir / "cells")
    if dry_run:
        return {"city": CITY_ID, "cells": len(cells), "status": "planned",
                "cell_dir": str(workdir / "cells")}

    if not db_url:
        raise ValueError("DV_IMPORTER_DB_URL não definido")
    if pbf and not pbf.exists():
        raise FileNotFoundError(pbf)
    # Faz download somente uma vez. 'extract' reduz o arquivo Sudeste para o município.
    original = pbf if pbf is not None else download(url, workdir / "download")
    city_pbf = workdir / "municipality" / "region.osm.pbf"
    if not city_pbf.exists():
        city_pbf = extract(original, boundary, workdir / "municipality")
    if city_pbf == original:
        LOG.warning("osmium-tool ausente: importação ficará lenta sem recorte")
    digest = sha256_file(city_pbf)
    report = {"city": CITY_ID, "cells": len(cells), "processed": 0,
              "published": 0, "skipped": 0, "needs_review": 0, "failed": []}
    selected = cell_files[:max_cells] if max_cells else cell_files
    for cell, path in selected:
        LOG.info("Importando %s (%s/%s)", cell.code, report["processed"] + 1, len(selected))
        try:
            outcome = run_import(ImportArgs(
                region_code=cell.code, region_name=cell.name, region_geojson=path,
                pbf=city_pbf, url=None, db_url=db_url,
                workdir=workdir / "batches" / cell.code, publish=publish,
                source_sha256=digest,
            ))
            report["processed"] += 1
            if outcome.get("skipped"):
                report["skipped"] += 1
            elif outcome["status"] == "published":
                report["published"] += 1
            else:
                report["needs_review"] += 1
        except Exception as exc:  # noqa: BLE001 — seguir outras células; registrar erro
            LOG.exception("Falha na célula %s", cell.code)
            report["failed"].append({"cell": cell.code, "error": str(exc)[:300]})
        (workdir / "city-report.json").write_text(
            json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    return report
