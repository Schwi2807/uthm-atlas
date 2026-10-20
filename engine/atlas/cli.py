"""`atlas` command line. Run `atlas <command> -h` for options.

Release flow: atlas ingest surveys/*.uthmsurvey -> atlas footprints -> atlas build -> atlas preview -> git push.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from . import __version__
from .paths import ENGINE, REPO, SITE, SITE_DATA, SURVEYS, WORK

CURATION = ENGINE / "curation.json"


def _cfg(args):
    from . import config
    return config.load(Path(args.config) if args.config else None)


def cmd_ingest(args) -> int:
    from . import ingest
    files = [Path(f) for f in args.files] or sorted(SURVEYS.glob("*.uthmsurvey"))
    if not files:
        print("no .uthmsurvey files given or found in surveys/", file=sys.stderr)
        return 2
    res = ingest.ingest(_cfg(args), files, WORK, replace=args.replace)
    print(res.summary())
    return 0 if res.ok else 2


def cmd_seed(args) -> int:
    from . import seed
    res = seed.seed(_cfg(args), WORK, refresh=args.refresh)
    print(res)
    return 0


def cmd_footprints(args) -> int:
    from . import footprints
    res = footprints.run(_cfg(args), WORK, refresh=args.refresh)
    print(res.summary())
    return 0


def cmd_suggest(args) -> int:
    from . import seed
    out = Path(args.out) if args.out else SITE_DATA / "suggestions.json"
    n = seed.suggestion_pack(_cfg(args), out, refresh=args.refresh)
    print(f"suggestion pack: {n} names -> {out.relative_to(REPO) if out.is_relative_to(REPO) else out}")
    return 0


def cmd_basemap(args) -> int:
    from . import basemap
    res = basemap.run(_cfg(args), SITE_DATA, refresh=args.refresh, date=args.date, xyz=args.xyz)
    print(res)
    return 0


def cmd_build(args) -> int:
    from . import build, check
    cfg = _cfg(args)
    tiles = args.tiles
    if tiles == "auto":
        tiles = "xyz" if (SITE_DATA / "tiles").is_dir() and not (SITE_DATA / "campus.pmtiles").exists() else "pmtiles"
    try:
        data, rep = build.build(cfg, WORK, SITE_DATA, CURATION, ts=args.ts, tiles=tiles)
    except build.BuildError as e:
        print(str(e), file=sys.stderr)
        print("BUILD FAILED", file=sys.stderr)
        return 2
    build.write(data, rep, SITE_DATA, tiles)
    text = rep.text()
    (WORK / "build-report.txt").write_text(text, encoding="utf-8", newline="\n")
    print(text)
    problems = check.check_site(SITE_DATA)
    for p in problems:
        print(f"  check: {p}", file=sys.stderr)
    print("wrote site/data/campus.bundle" + ("" if not problems else "  (check found problems)"))
    return 0 if not problems else 2


def cmd_check(args) -> int:
    from . import check
    data_dir = Path(args.data) if args.data else SITE_DATA
    problems = check.check_site(data_dir)
    if problems:
        print("campus.bundle check FAILED:", file=sys.stderr)
        for p in problems:
            print(f"  - {p}", file=sys.stderr)
        return 2
    from . import bundle
    b = bundle.decode((data_dir / "campus.bundle").read_bytes())
    c = b.payload["build"]["counts"]
    print(f"campus.bundle OK: schema v{b.version}, built {b.payload['build']['date']}, "
          f"{c['places']} places ({c['verified']} verified), {c['photos']} photos, tiles={b.payload['build']['tiles']}")
    return 0


def cmd_xyz(args) -> int:
    """Pages fallback: unpack the committed campus.pmtiles into data/tiles/{z}/{x}/{y}.mvt (no network)."""
    from . import basemap, sitefiles
    src = SITE_DATA / "campus.pmtiles"
    if not src.exists():
        print("site/data/campus.pmtiles missing; run atlas basemap first", file=sys.stderr)
        return 2
    n = basemap.export_xyz(src, SITE_DATA / "tiles")
    if args.drop_pmtiles:
        src.unlink()
    version, files = sitefiles.update_sw(SITE)
    print(f"xyz fallback: {n} tiles in site/data/tiles/; now run: atlas retile --tiles xyz")
    return 0


def cmd_retile(args) -> int:
    """Switch an existing campus.bundle between pmtiles and xyz delivery without rebuilding (CI fallback)."""
    import json
    import time
    from . import bundle, sitefiles
    path = SITE_DATA / "campus.bundle"
    b = bundle.decode(path.read_bytes())
    ts = max(int(time.time()), b.ts + 1)
    b.payload["build"]["tiles"] = args.tiles
    b.payload["build"]["ts"] = ts
    data = bundle.encode(b.payload, ts)
    path.write_bytes(data)
    (SITE_DATA / "version.json").write_text(
        json.dumps({"v": b.version, "ts": ts, "bytes": len(data), "tiles": args.tiles}) + "\n", encoding="utf-8", newline="\n")
    sitefiles.update_sw(SITE)
    print(f"campus.bundle now uses tiles={args.tiles} (ts {ts})")
    return 0


def cmd_sw(args) -> int:
    from . import sitefiles
    version, n = sitefiles.update_sw(SITE)
    print(f"site/sw.js: VERSION {version}, {n} precached files")
    return 0


def cmd_preview(args) -> int:
    from . import preview
    preview.serve(SITE, port=args.port, host=args.host)
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="atlas", description="UTHM Atlas engine")
    ap.add_argument("--version", action="version", version=f"atlas {__version__}")
    ap.add_argument("--config", help="path to atlas.toml (default engine/atlas.toml)")
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("ingest", help="validate + merge .uthmsurvey exports into engine/work/")
    p.add_argument("files", nargs="*", help="survey files (default: surveys/*.uthmsurvey)")
    p.add_argument("--replace", action="store_true", help="re-ingest sessions that were ingested before")
    p.set_defaults(fn=cmd_ingest)

    p = sub.add_parser("seed", help="add OSM draft places (named buildings/amenities, 'not walked yet')")
    p.add_argument("--refresh", action="store_true", help="ignore the Overpass cache")
    p.set_defaults(fn=cmd_seed)

    p = sub.add_parser("footprints", help="fetch OSM footprints, link building anchors, snap entrances")
    p.add_argument("--refresh", action="store_true", help="ignore the Overpass cache")
    p.set_defaults(fn=cmd_footprints)

    p = sub.add_parser("suggest", help="write the Surveyor's offline name-suggestion pack (OSM + Wikidata)")
    p.add_argument("--out", help="output path (default site/data/suggestions.json, downloaded by the Surveyor)")
    p.add_argument("--refresh", action="store_true")
    p.set_defaults(fn=cmd_suggest)

    p = sub.add_parser("basemap", help="extract site/data/campus.pmtiles from a Protomaps build")
    p.add_argument("--refresh", action="store_true", help="re-extract even if cached")
    p.add_argument("--date", help="Protomaps build date YYYYMMDD (default: newest)")
    p.add_argument("--xyz", action="store_true", help="also export a z/x/y tile directory (Pages fallback)")
    p.set_defaults(fn=cmd_basemap)

    p = sub.add_parser("build", help="compile site/data/campus.bundle + photos and print the build report")
    p.add_argument("--ts", type=int, help="override the build timestamp (Unix seconds)")
    p.add_argument("--tiles", choices=["auto", "pmtiles", "xyz"], default="auto")
    p.set_defaults(fn=cmd_build)

    p = sub.add_parser("check", help="deploy gate: decode + validate site/data/campus.bundle")
    p.add_argument("--data", help="data dir (default site/data)")
    p.set_defaults(fn=cmd_check)

    p = sub.add_parser("xyz", help="export site/data/tiles/{z}/{x}/{y}.mvt from campus.pmtiles (no-Range fallback)")
    p.add_argument("--drop-pmtiles", action="store_true", help="remove campus.pmtiles afterwards")
    p.set_defaults(fn=cmd_xyz)

    p = sub.add_parser("retile", help="switch the built bundle to pmtiles or xyz tiles (no rebuild)")
    p.add_argument("--tiles", choices=["pmtiles", "xyz"], required=True)
    p.set_defaults(fn=cmd_retile)

    p = sub.add_parser("sw", help="refresh site/sw.js precache list + version after editing site code")
    p.set_defaults(fn=cmd_sw)

    p = sub.add_parser("preview", help="serve site/ locally with Range support")
    p.add_argument("--port", type=int, default=8000)
    p.add_argument("--host", default="127.0.0.1")
    p.set_defaults(fn=cmd_preview)

    args = ap.parse_args(argv)
    return args.fn(args)


if __name__ == "__main__":
    sys.exit(main())
