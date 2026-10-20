"""Polite Overpass client: one query per need, cached on disk, exponential backoff across mirrors.

OSM usage policy: identify ourselves, cache, never hammer. Every response is stored in engine/.cache/overpass/
keyed by a hash of the query, and reused until `max_age_days` (atlas.toml) expires or --refresh is given.
"""

from __future__ import annotations

import hashlib
import json
import time
from pathlib import Path

import requests

from . import __version__
from .paths import CACHE

USER_AGENT = f"uthm-atlas-engine/{__version__} (+https://github.com/Schwi2807/uthm-atlas)"
DEFAULT_ENDPOINTS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
    "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
]


class OverpassError(RuntimeError):
    pass


def query(ql: str, *, endpoints: list[str] | None = None, max_age_days: float = 30, refresh: bool = False,
          cache_dir: Path | None = None, attempts: int = 6, log=print) -> dict:
    cache_dir = cache_dir or CACHE / "overpass"
    cache_dir.mkdir(parents=True, exist_ok=True)
    key = hashlib.sha256(ql.encode()).hexdigest()[:16]
    path = cache_dir / f"{key}.json"
    if path.exists() and not refresh and (time.time() - path.stat().st_mtime) < max_age_days * 86400:
        return json.loads(path.read_text(encoding="utf-8"))

    endpoints = endpoints or DEFAULT_ENDPOINTS
    wait = 5.0
    last = ""
    for i in range(attempts):
        ep = endpoints[i % len(endpoints)]
        try:
            r = requests.post(ep, data={"data": ql}, headers={"User-Agent": USER_AGENT}, timeout=120)
            if r.status_code == 200 and r.text.lstrip().startswith("{"):
                data = r.json()
                path.write_text(json.dumps(data), encoding="utf-8", newline="\n")
                return data
            last = f"{ep}: HTTP {r.status_code} {r.text[:120]!r}"
        except requests.RequestException as e:  # network trouble: try the next mirror
            last = f"{ep}: {e}"
        log(f"  overpass busy ({last.splitlines()[0][:100]}); retry in {wait:.0f}s")
        time.sleep(wait)
        wait = min(wait * 2, 120)
    if path.exists():
        log("  overpass unreachable; using the stale cached copy")
        return json.loads(path.read_text(encoding="utf-8"))
    raise OverpassError(f"Overpass failed after {attempts} attempts: {last}")


def bbox_ql(bbox: list[float]) -> str:
    """Overpass wants (south, west, north, east)."""
    w, s, e, n = bbox
    return f"({s},{w},{n},{e})"
