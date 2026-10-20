"""atlas basemap: site/data/campus.pmtiles from a Protomaps daily build, via `pmtiles extract`.

`pmtiles extract <remote> <out> --bbox=...` reads only the byte ranges it needs from the multi-GB planet file:
no tile scraping. The extract is cached in engine/.cache/basemap/ by build date. With --xyz the archive is also
unpacked into site/data/tiles/{z}/{x}/{y}.mvt for hosts that cannot serve Range requests.

The pmtiles CLI is fetched from its GitHub release into C:/Dev/tools/pmtiles/<version>/ (or ~/.cache on other
systems) and checked against the release's checksums.txt.
"""

from __future__ import annotations

import gzip
import hashlib
import io
import json
import os
import platform
import shutil
import subprocess
import zipfile
import tarfile
from dataclasses import dataclass
from pathlib import Path

import requests

from .config import Config
from .osm import USER_AGENT
from .paths import CACHE

GH = "https://github.com/protomaps/go-pmtiles/releases/download/v{v}/{asset}"


def tools_dir() -> Path:
    env = os.environ.get("ATLAS_TOOLS")
    if env:
        return Path(env)
    if os.name == "nt" and Path("C:/Dev/tools").exists():
        return Path("C:/Dev/tools")
    return Path.home() / ".cache" / "uthm-atlas-tools"


def _asset(version: str) -> str:
    sysname = platform.system()
    mach = platform.machine().lower()
    arch = "arm64" if mach in ("arm64", "aarch64") else "x86_64"
    if sysname == "Windows":
        return f"go-pmtiles_{version}_Windows_{arch}.zip"
    if sysname == "Darwin":
        return f"go-pmtiles-{version}_Darwin_{arch}.zip"
    return f"go-pmtiles_{version}_Linux_{arch}.tar.gz"


def ensure_cli(version: str, log=print) -> Path:
    exe_name = "pmtiles.exe" if os.name == "nt" else "pmtiles"
    found = shutil.which("pmtiles")
    target_dir = tools_dir() / "pmtiles" / version
    exe = target_dir / exe_name
    if exe.exists():
        return exe
    if found and not os.environ.get("ATLAS_FORCE_DOWNLOAD"):
        return Path(found)
    asset = _asset(version)
    log(f"  downloading {asset} -> {target_dir}")
    s = requests.Session()
    s.headers["User-Agent"] = USER_AGENT
    # GitHub's release API publishes a sha256 digest per asset
    rel = s.get(f"https://api.github.com/repos/protomaps/go-pmtiles/releases/tags/v{version}", timeout=60)
    rel.raise_for_status()
    meta = next((a for a in rel.json().get("assets", []) if a["name"] == asset), None)
    if meta is None:
        raise RuntimeError(f"release v{version} has no asset {asset}")
    want = (meta.get("digest") or "").removeprefix("sha256:")
    blob = s.get(meta["browser_download_url"], timeout=300)
    blob.raise_for_status()
    got = hashlib.sha256(blob.content).hexdigest()
    if not want:
        log(f"  warning: no published digest for {asset}; sha256 {got}")
    elif got != want:
        raise RuntimeError(f"{asset}: sha256 {got} does not match the published digest {want}")
    target_dir.mkdir(parents=True, exist_ok=True)
    if asset.endswith(".zip"):
        with zipfile.ZipFile(io.BytesIO(blob.content)) as z:
            member = next(n for n in z.namelist() if n.endswith(exe_name))
            exe.write_bytes(z.read(member))
    else:
        with tarfile.open(fileobj=io.BytesIO(blob.content)) as t:
            member = next(m for m in t.getmembers() if m.name.endswith(exe_name))
            exe.write_bytes(t.extractfile(member).read())
        exe.chmod(0o755)
    return exe


def newest_build(cfg: Config) -> str:
    pinned = cfg["basemap"].get("protomaps_build")
    if pinned:
        return pinned
    r = requests.get(cfg["basemap"]["builds_url"], headers={"User-Agent": USER_AGENT}, timeout=60)
    r.raise_for_status()
    keys = sorted(b["key"].split(".")[0] for b in r.json() if str(b.get("key", "")).endswith(".pmtiles"))
    if not keys:
        raise RuntimeError("no Protomaps builds listed")
    return keys[-1]


@dataclass
class BasemapResult:
    date: str
    path: Path
    bytes: int
    tiles: int = 0

    def __str__(self) -> str:
        s = f"basemap: Protomaps build {self.date} -> {self.path.name} ({self.bytes / 1024:.0f} KB)"
        if self.tiles:
            s += f"; xyz fallback: {self.tiles} tiles in data/tiles/"
        return s + "\n  attribution: © OpenStreetMap contributors, Protomaps"


def run(cfg: Config, site_data: Path, refresh: bool = False, date: str | None = None, xyz: bool = False,
        log=print) -> BasemapResult:
    bm = cfg["basemap"]
    date = date or newest_build(cfg)
    cache = CACHE / "basemap"
    cache.mkdir(parents=True, exist_ok=True)
    out = cache / f"campus-{date}.pmtiles"
    if refresh or not out.exists():
        exe = ensure_cli(bm["pmtiles_version"], log=log)
        w, s, e, n = cfg.bbox
        src = bm["build_url"].format(date=date)
        log(f"  extracting {src} bbox={w},{s},{e},{n} maxzoom={bm['maxzoom']}")
        tmp = out.with_suffix(".part")
        subprocess.run([str(exe), "extract", src, str(tmp), f"--bbox={w},{s},{e},{n}", f"--maxzoom={bm['maxzoom']}"],
                       check=True)
        tmp.replace(out)
    site_data.mkdir(parents=True, exist_ok=True)
    dest = site_data / "campus.pmtiles"
    shutil.copyfile(out, dest)
    (cache / "latest.json").write_text(json.dumps({"date": date}), encoding="utf-8")
    res = BasemapResult(date, dest, dest.stat().st_size)
    budget = int(cfg["budget"]["pmtiles_kb"]) * 1024
    if res.bytes > budget:
        raise RuntimeError(f"campus.pmtiles is {res.bytes / 1024:.0f} KB, over the {budget // 1024} KB budget; "
                           "lower basemap.maxzoom or shrink campus.margin_deg")
    if xyz:
        res.tiles = export_xyz(dest, site_data / "tiles")
    # glyph ranges for every label in the tiles
    from . import glyphs
    texts: set[str] = set()
    minzoom = int(bm.get("minzoom", 0))
    for z, _x, _y, blob in read_tiles(dest):
        if z >= minzoom:
            texts |= glyphs.tile_strings(blob)
    glyphs.ensure(site_data.parent / "map", glyphs.ranges_for(texts), log=log)
    write_file_list(site_data)
    return res


def write_file_list(site_data: Path) -> list[str]:
    """data/files.json: everything the service worker must cache for full offline use (paths relative to site/)."""
    site = site_data.parent
    files = []
    for folder in (site_data, site / "map"):
        if folder.exists():
            files += [p.relative_to(site).as_posix() for p in folder.rglob("*")
                      if p.is_file() and p.name != "files.json" and "/tiles/" not in p.as_posix()]
    files.sort()
    total = sum((site / f).stat().st_size for f in files)
    (site_data / "files.json").write_text(json.dumps({"files": files, "bytes": total}, indent=0) + "\n",
                                          encoding="utf-8")
    return files


# ---------------------------------------------------------------- minimal PMTiles v3 reader (xyz fallback)

def _varint(buf: bytes, pos: int) -> tuple[int, int]:
    shift = result = 0
    while True:
        b = buf[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, pos
        shift += 7


def _decompress(data: bytes, kind: int) -> bytes:
    if kind in (0, 1):  # unknown / none
        return data
    if kind == 2:
        return gzip.decompress(data)
    raise ValueError(f"unsupported PMTiles compression {kind}")


def _directory(buf: bytes) -> list[tuple[int, int, int, int]]:
    n, pos = _varint(buf, 0)
    ids, runs, lens, offs = [], [], [], []
    last = 0
    for _ in range(n):
        d, pos = _varint(buf, pos)
        last += d
        ids.append(last)
    for _ in range(n):
        v, pos = _varint(buf, pos)
        runs.append(v)
    for _ in range(n):
        v, pos = _varint(buf, pos)
        lens.append(v)
    for i in range(n):
        v, pos = _varint(buf, pos)
        offs.append(offs[i - 1] + lens[i - 1] if v == 0 and i > 0 else v - 1)
    return list(zip(ids, runs, lens, offs))


def _zxy(tile_id: int) -> tuple[int, int, int]:
    acc = 0
    z = 0
    while True:
        n = 1 << (2 * z)
        if acc + n > tile_id:
            break
        acc += n
        z += 1
    pos = tile_id - acc
    # Hilbert d -> (x, y)
    size = 1 << z
    x = y = 0
    t = pos
    s = 1
    while s < size:
        rx = 1 & (t // 2)
        ry = 1 & (t ^ rx)
        if ry == 0:
            if rx == 1:
                x, y = s - 1 - x, s - 1 - y
            x, y = y, x
        x += s * rx
        y += s * ry
        t //= 4
        s *= 2
    return z, x, y


def read_tiles(path: Path):
    """Yield (z, x, y, raw tile bytes) from a PMTiles v3 archive."""
    data = path.read_bytes()
    if data[:7] != b"PMTiles" or data[7] != 3:
        raise ValueError("not a PMTiles v3 archive")
    h = [int.from_bytes(data[8 + i * 8: 16 + i * 8], "little") for i in range(12)]
    root_off, root_len, _meta_off, _meta_len, leaf_off, _leaf_len, tile_off = h[0], h[1], h[2], h[3], h[4], h[5], h[6]
    internal = data[97]

    def walk(off: int, length: int):
        for tid, run, ln, o in _directory(_decompress(data[off:off + length], internal)):
            if run == 0:
                yield from walk(leaf_off + o, ln)
            else:
                for k in range(run):
                    yield tid + k, data[tile_off + o: tile_off + o + ln]

    yield from ((*_zxy(tid), blob) for tid, blob in walk(root_off, root_len))


def export_xyz(pmtiles: Path, out_dir: Path) -> int:
    if out_dir.exists():
        shutil.rmtree(out_dir)
    n = 0
    for z, x, y, blob in read_tiles(pmtiles):
        p = out_dir / str(z) / str(x) / f"{y}.mvt"
        p.parent.mkdir(parents=True, exist_ok=True)
        # static hosts don't send Content-Encoding for .mvt, so store the tiles inflated
        p.write_bytes(gzip.decompress(blob) if blob[:2] == bytes([0x1F, 0x8B]) else blob)
        n += 1
    return n
