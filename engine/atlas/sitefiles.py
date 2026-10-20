"""Keeps site/sw.js in step with site/: the precache list (ASSETS) and a content-hash VERSION.

Every file the app may need offline is precached, so the list is regenerated whenever data or code changes
(`atlas build` and `atlas basemap` call this; `atlas sw` runs it alone after editing site code). VERSION is a hash
of every listed file, so any change makes browsers install the new worker and show the update toast.
"""

from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path

SKIP_SUFFIX = {".md", ".txt", ".map"}
SKIP_NAMES = {"sw.js", ".nojekyll", "files.json", "CNAME"}


def site_files(site: Path) -> list[str]:
    out = []
    for p in sorted(site.rglob("*")):
        if not p.is_file():
            continue
        rel = p.relative_to(site).as_posix()
        if p.name in SKIP_NAMES or p.suffix in SKIP_SUFFIX or p.name.startswith("."):
            continue
        out.append(rel)
    return out


def update_sw(site: Path) -> tuple[str, int]:
    sw = site / "sw.js"
    files = site_files(site)
    h = hashlib.sha256()
    for f in files:
        h.update(f.encode())
        h.update(hashlib.sha256((site / f).read_bytes()).digest())
    version = h.hexdigest()[:12]
    assets = ["./"] + files
    body = "\n".join(f"  {json.dumps(a)}," for a in assets)
    text = sw.read_text(encoding="utf-8")
    text, n1 = re.subn(r"const VERSION = .*// <version>", f"const VERSION = PREFIX + '{version}'; // <version>", text)
    text, n2 = re.subn(r"const ASSETS = \[[\s\S]*?\]; // </assets>", lambda _m: f"const ASSETS = [\n{body}\n]; // </assets>", text)
    if not (n1 and n2):
        raise RuntimeError("site/sw.js lost its <version> / </assets> markers")
    sw.write_text(text, encoding="utf-8", newline="\n")
    stale = site / "data" / "files.json"
    if stale.exists():
        stale.unlink()
    return version, len(assets)
