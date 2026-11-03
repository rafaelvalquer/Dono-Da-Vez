"""Obtenção do extrato: download com retomada, hash em fluxo, conferência MD5 e recorte pelo polígono."""
from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
import urllib.error
import urllib.request
from pathlib import Path

from shapely.geometry import shape
from shapely.geometry.base import BaseGeometry

USER_AGENT = "dv-geo-importer/1.0 (importador do jogo Dono da Vez)"
CHUNK = 1024 * 1024


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        while chunk := f.read(CHUNK):
            h.update(chunk)
    return h.hexdigest()


def md5_file(path: Path) -> str:
    h = hashlib.md5()  # noqa: S324 — apenas conferência do arquivo publicado pela Geofabrik
    with path.open("rb") as f:
        while chunk := f.read(CHUNK):
            h.update(chunk)
    return h.hexdigest()


def _get(url: str, headers: dict[str, str] | None = None):
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, **(headers or {})})
    return urllib.request.urlopen(req, timeout=60)  # noqa: S310 — URL vem da configuração da região


def download(url: str, dest_dir: Path) -> Path:
    """Baixa para dest_dir com retomada (.part) e confere o .md5 publicado ao lado do arquivo, se existir."""
    if not url.startswith("https://"):
        raise ValueError("Somente URLs https são aceitas")
    dest_dir.mkdir(parents=True, exist_ok=True)
    final = dest_dir / url.rsplit("/", 1)[-1]
    part = final.with_suffix(final.suffix + ".part")
    offset = part.stat().st_size if part.exists() else 0
    with _get(url, {"Range": f"bytes={offset}-"} if offset else None) as res:
        mode = "ab" if offset and res.status == 206 else "wb"
        with part.open(mode) as out:
            shutil.copyfileobj(res, out, CHUNK)
    try:
        with _get(url + ".md5") as res:
            expected = res.read().decode().split()[0].lower()
        if md5_file(part) != expected:
            part.unlink()
            raise RuntimeError("MD5 do extrato não confere; download descartado")
    except urllib.error.HTTPError as e:
        if e.code != 404:
            raise
    part.replace(final)
    return final


def load_region(path: Path) -> tuple[BaseGeometry, dict, dict]:
    """(geometria, geojson_da_geometria, propriedades) do primeiro Feature/geometria do arquivo."""
    data = json.loads(path.read_text(encoding="utf-8"))
    props: dict = {}
    if data.get("type") == "FeatureCollection":
        data = data["features"][0]
    if data.get("type") == "Feature":
        props = data.get("properties") or {}
        data = data["geometry"]
    geom = shape(data)
    if geom.geom_type not in ("Polygon", "MultiPolygon") or not geom.is_valid:
        raise ValueError("Polígono da região inválido")
    return geom, data, props


def extract(src: Path, region_geojson: Path, workdir: Path) -> Path:
    """Recorta com osmium-tool quando disponível (evita carregar o estado inteiro na leitura)."""
    if shutil.which("osmium") is None:
        return src
    workdir.mkdir(parents=True, exist_ok=True)
    out = workdir / "region.osm.pbf"
    subprocess.run(["osmium", "extract", "--polygon", str(region_geojson), "--strategy", "smart",
                    "--set-bounds", "--overwrite", "-o", str(out), str(src)], check=True)
    return out
