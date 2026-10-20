"""atlas check: the deploy gate. Decodes site/data/campus.bundle and checks it is publishable.

Used by .github/workflows/pages.yml before deploying, so it only needs jsonschema + msgpack.
"""

from __future__ import annotations

import json
from pathlib import Path

from . import SCHEMA_VERSION, bundle, schema


def check_site(data_dir: Path) -> list[str]:
    problems: list[str] = []
    path = data_dir / "campus.bundle"
    if not path.exists():
        return [f"{path} missing"]
    raw = path.read_bytes()
    try:
        b = bundle.decode(raw, expect_version=SCHEMA_VERSION)
    except bundle.BundleError as e:
        return [f"campus.bundle: {e}"]
    problems += [f"payload: {e}" for e in schema.errors("bundle", b.payload)]
    if b.payload.get("v") != b.version:
        problems.append(f"payload v={b.payload.get('v')} but header says {b.version}")
    if b.payload.get("build", {}).get("ts") != b.ts:
        problems.append("payload build.ts differs from the header timestamp")

    vpath = data_dir / "version.json"
    if not vpath.exists():
        problems.append("version.json missing")
    else:
        v = json.loads(vpath.read_text(encoding="utf-8"))
        if (v.get("v"), v.get("ts"), v.get("bytes")) != (b.version, b.ts, len(raw)):
            problems.append(f"version.json {v} does not match the bundle (v={b.version}, ts={b.ts}, bytes={len(raw)})")

    for ph in b.payload.get("photos", []):
        if not (data_dir / ph["f"]).exists():
            problems.append(f"photo {ph['f']} referenced but missing")

    tiles = b.payload.get("build", {}).get("tiles", "pmtiles")
    if tiles == "pmtiles" and not (data_dir / "campus.pmtiles").exists():
        problems.append("bundle expects data/campus.pmtiles but it is missing")
    if tiles == "xyz" and not (data_dir / "tiles").is_dir():
        problems.append("bundle expects data/tiles/ (xyz fallback) but it is missing")

    places = b.payload.get("places", [])
    n = len(places)
    for i, p in enumerate(places):
        for key in ("par", "fp"):
            j = p.get(key)
            if j is not None and not (0 <= j < (n if key == "par" else len(b.payload["footprints"]))):
                problems.append(f"place {i} {key}={j} out of range")
        for j in p.get("ents", []):
            if not 0 <= j < len(b.payload["entrances"]):
                problems.append(f"place {i} entrance {j} out of range")
    for t in b.payload.get("tour", []):
        if not 0 <= t["pl"] < n:
            problems.append(f"tour step {t['en']!r} points outside places")
    return problems
