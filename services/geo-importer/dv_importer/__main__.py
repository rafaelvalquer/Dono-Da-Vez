"""CLI do importador.

  import    registra a região, importa e publica (se aprovado automaticamente)
  coverage  mede a cobertura de um extrato sem banco (escolha do polígono piloto)
"""
from __future__ import annotations

import argparse
import csv
import json
import logging
import os
import sys
from pathlib import Path

from .pipeline import ImportArgs, analyze, run_import
from .city import OSM_URL, run_city


def _coverage(args: argparse.Namespace) -> int:
    result = analyze(Path(args.pbf), Path(args.region_geojson))
    summary = {"counts": result.counts, "report": result.report}
    out = Path(args.out) if args.out else None
    if out:
        out.mkdir(parents=True, exist_ok=True)
        (out / "coverage.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
        with (out / "properties.csv").open("w", newline="", encoding="utf-8") as f:
            w = csv.writer(f)
            w.writerow(["canonical_ref", "category", "subtype", "status", "confidence", "eligibility", "reason",
                        "interaction_quality", "address", "lat", "lon"])
            for r in result.rows:
                w.writerow([r.canonical_ref, r.category_code or "", r.subtype or "", r.classification_status,
                            r.classification_confidence, r.eligibility, r.eligibility_reason or "",
                            r.interaction_quality, r.address_label or "", r.point.y, r.point.x])
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


def _import(args: argparse.Namespace) -> int:
    db_url = os.environ.get("DV_IMPORTER_DB_URL")
    if not db_url:
        print("DV_IMPORTER_DB_URL não definido", file=sys.stderr)
        return 2
    if bool(args.pbf) == bool(args.url):
        print("Informe --pbf OU --url", file=sys.stderr)
        return 2
    out = run_import(ImportArgs(
        region_code=args.region, region_name=args.name, region_geojson=Path(args.region_geojson),
        pbf=Path(args.pbf) if args.pbf else None, url=args.url, db_url=db_url,
        workdir=Path(args.workdir) if args.workdir else None, publish=not args.no_publish,
    ))
    print(json.dumps(out, ensure_ascii=False, indent=2, default=str))
    return 0


def _city(args: argparse.Namespace) -> int:
    result = run_city(
        db_url=os.environ.get("DV_IMPORTER_DB_URL"),
        boundary=Path(args.boundary) if args.boundary else None,
        pbf=Path(args.pbf) if args.pbf else None,
        url=args.url, workdir=Path(args.workdir), max_cells=args.max_cells,
        dry_run=args.dry_run, publish=not args.no_publish,
    )
    print(json.dumps(result, ensure_ascii=False, indent=2, default=str))
    return 1 if result.get("failed") else 0


def main(argv: list[str] | None = None) -> int:
    logging.basicConfig(level=logging.INFO, format='{"level":"%(levelname)s","msg":"%(message)s"}')
    p = argparse.ArgumentParser(prog="dv-geo-importer")
    sub = p.add_subparsers(dest="cmd", required=True)

    imp = sub.add_parser("import", help="importa e publica uma região")
    imp.add_argument("--region", required=True, help="código da região (a-z, 0-9, -)")
    imp.add_argument("--name", help="nome exibido da região")
    imp.add_argument("--region-geojson", required=True)
    imp.add_argument("--pbf", help="arquivo .osm.pbf/.osm local")
    imp.add_argument("--url", help="URL https do extrato (ex.: Geofabrik)")
    imp.add_argument("--workdir")
    imp.add_argument("--no-publish", action="store_true", help="para no estado aprovado/validado")
    imp.set_defaults(fn=_import)

    cov = sub.add_parser("coverage", help="relatório de cobertura sem banco")
    cov.add_argument("--pbf", required=True)
    cov.add_argument("--region-geojson", required=True)
    cov.add_argument("--out", help="diretório para coverage.json e properties.csv")
    cov.set_defaults(fn=_coverage)

    city = sub.add_parser("import-city", help="gera células do município de São Paulo e importa edificações OSM")
    city.add_argument("--boundary", help="GeoJSON municipal oficial previamente baixado (sem isso: IBGE)")
    city.add_argument("--pbf", help="extrato Sudeste já baixado (sem isso: Geofabrik)")
    city.add_argument("--url", default=OSM_URL)
    city.add_argument("--workdir", default="/data/sp-city")
    city.add_argument("--max-cells", type=int, help="executa só N células para homologação")
    city.add_argument("--dry-run", action="store_true", help="somente gera os polígonos de trabalho")
    city.add_argument("--no-publish", action="store_true")
    city.set_defaults(fn=_city)

    args = p.parse_args(argv)
    return args.fn(args)


if __name__ == "__main__":
    sys.exit(main())
