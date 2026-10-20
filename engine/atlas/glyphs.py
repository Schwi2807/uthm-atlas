"""Offline map assets: glyph ranges (SDF .pbf) and sprites from Protomaps' basemaps-assets.

MapLibre asks for a 256-codepoint glyph range per font whenever a label needs one. Missing ranges are 404s in the
console and missing letters on the map, so we ship exactly the ranges that the basemap labels and the bundle's
place names use, computed by reading every string in the vector tiles.
"""

from __future__ import annotations

import gzip
from pathlib import Path
from urllib.parse import quote

import requests

from .osm import USER_AGENT
from .paths import CACHE

ASSETS = "https://protomaps.github.io/basemaps-assets"
FONTS = ["Noto Sans Regular", "Noto Sans Medium", "Noto Sans Italic"]
SPRITES = ["light", "dark"]
ALWAYS = {0, 256, 8192}  # Basic Latin + Latin-1, Latin Extended-A/B, General Punctuation
GZIP_MAGIC = bytes([0x1F, 0x8B])


def _varint(b: bytes, i: int) -> tuple[int, int]:
    shift = val = 0
    while True:
        c = b[i]
        i += 1
        val |= (c & 0x7F) << shift
        if not c & 0x80:
            return val, i
        shift += 7


def _fields(b: bytes):
    i = 0
    while i < len(b):
        key, i = _varint(b, i)
        field, wt = key >> 3, key & 7
        if wt == 0:
            _, i = _varint(b, i)
        elif wt == 1:
            i += 8
        elif wt == 5:
            i += 4
        elif wt == 2:
            n, i = _varint(b, i)
            yield field, b[i:i + n]
            i += n
        else:
            return


# Only these properties are ever drawn as text by our style (lang "en" uses name:en, falling back to name)
LABEL_KEYS = {"name", "name:en", "name:ms", "ref", "shield_text", "addr_housenumber"}


def _packed(b: bytes) -> list[int]:
    out, i = [], 0
    while i < len(b):
        v, i = _varint(b, i)
        out.append(v)
    return out


def tile_strings(tile: bytes) -> set[str]:
    """Strings of label properties (LABEL_KEYS) in an MVT tile."""
    if tile[:2] == GZIP_MAGIC:
        tile = gzip.decompress(tile)
    out = set()
    for f, layer in _fields(tile):
        if f != 3:
            continue
        keys, values, feats = [], [], []
        for lf, val in _fields(layer):
            if lf == 3:
                keys.append(val.decode("utf-8", "replace"))
            elif lf == 4:
                sv = None
                for vf, s in _fields(val):
                    if vf == 1:
                        sv = s.decode("utf-8", "replace")
                values.append(sv)
            elif lf == 2:
                feats.append(val)
        want = {i for i, k in enumerate(keys) if k in LABEL_KEYS}
        for feat in feats:
            for ff, tags in _fields(feat):
                if ff != 2:
                    continue
                t = _packed(tags)
                for k, v in zip(t[::2], t[1::2]):
                    if k in want and v < len(values) and values[v]:
                        out.add(values[v])
    return out


# MapLibre draws these blocks with a local system font (localIdeographFontFamily), never fetching glyphs
LOCAL_BLOCKS = [(0x3040, 0x30FF), (0x3400, 0x9FFF), (0xAC00, 0xD7AF), (0xF900, 0xFAFF)]


def ranges_for(texts) -> set[int]:
    starts = set(ALWAYS)
    for t in texts:
        for ch in t:
            cp = ord(ch)
            if cp < 0x10000 and not any(a <= cp <= b for a, b in LOCAL_BLOCKS):
                starts.add(cp // 256 * 256)
    return starts


def _get(s: requests.Session, url: str) -> requests.Response:
    import time
    for attempt in range(5):
        r = s.get(url, timeout=60)
        if r.status_code < 500:
            return r
        time.sleep(2 * (attempt + 1))
    return r


def ensure(map_dir: Path, starts: set[int], log=print) -> int:
    """Download any missing glyph range for every font, plus the sprites. Returns files written."""
    s = requests.Session()
    s.headers["User-Agent"] = USER_AGENT
    cache = CACHE / "glyphs"
    written = 0
    for font in FONTS:
        for start in sorted(starts):
            rng = f"{start}-{start + 255}"
            dest = map_dir / "fonts" / font / f"{rng}.pbf"
            if dest.exists():
                continue
            c = cache / font / f"{rng}.pbf"
            if not c.exists():
                r = _get(s, f"{ASSETS}/fonts/{quote(font)}/{rng}.pbf")
                if r.status_code == 404:
                    log(f"  note: {font} has no {rng} range upstream")
                    continue
                r.raise_for_status()
                c.parent.mkdir(parents=True, exist_ok=True)
                c.write_bytes(r.content)
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(c.read_bytes())
            written += 1
    for name in SPRITES:
        for suffix in (".json", ".png", "@2x.json", "@2x.png"):
            dest = map_dir / "sprites" / f"{name}{suffix}"
            if dest.exists():
                continue
            r = _get(s, f"{ASSETS}/sprites/v4/{name}{suffix}")
            r.raise_for_status()
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(r.content)
            written += 1
    if written:
        log(f"  map assets: {written} glyph/sprite files added under site/map/")
    return written


def manifest(map_dir: Path) -> list[str]:
    """Relative paths (from site/) of every glyph + sprite file, for the service worker precache list."""
    root = map_dir.parent
    return sorted(p.relative_to(root).as_posix() for p in map_dir.rglob("*") if p.is_file())
