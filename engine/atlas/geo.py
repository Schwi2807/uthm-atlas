"""Geometry helpers: micro-degree encoding, delta-coded rings, metric projection.

UTHM Parit Raja sits at 103.08 E, inside UTM zone 48N (EPSG:32648), so all metric work (distances, dedupe,
snapping, simplification) happens in that projection and results are converted back to WGS84.
"""

from __future__ import annotations

import math
from functools import lru_cache

from pyproj import Transformer
from shapely.geometry import Polygon, MultiPolygon, shape
from shapely.geometry.base import BaseGeometry
import shapely

METRIC_CRS = "EPSG:32648"
EARTH_R = 6371008.8


@lru_cache(maxsize=None)
def _fwd():
    return Transformer.from_crs("EPSG:4326", METRIC_CRS, always_xy=True)


@lru_cache(maxsize=None)
def _inv():
    return Transformer.from_crs(METRIC_CRS, "EPSG:4326", always_xy=True)


def to_metric(g: BaseGeometry) -> BaseGeometry:
    return shapely.transform(g, lambda x, y: _fwd().transform(x, y), interleaved=False)


def to_wgs84(g: BaseGeometry) -> BaseGeometry:
    return shapely.transform(g, lambda x, y: _inv().transform(x, y), interleaved=False)


def xy(lon: float, lat: float) -> tuple[float, float]:
    return _fwd().transform(lon, lat)


def lonlat(x: float, y: float) -> tuple[float, float]:
    return _inv().transform(x, y)


def haversine_m(a: tuple[float, float], b: tuple[float, float]) -> float:
    lon1, lat1, lon2, lat2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lon2 - lon1) / 2) ** 2
    return 2 * EARTH_R * math.asin(math.sqrt(h))


def micro(v: float) -> int:
    return int(round(v * 1e6))


def micro_pt(lon: float, lat: float) -> list[int]:
    return [micro(lon), micro(lat)]


def delta_ring(coords) -> list[int]:
    """[(lon, lat), ...] -> [x0, y0, dx1, dy1, ...] in micro-degrees, closing vertex dropped."""
    pts = [(micro(x), micro(y)) for x, y, *_ in coords]
    if len(pts) > 1 and pts[0] == pts[-1]:
        pts = pts[:-1]
    out: list[int] = []
    px = py = 0
    for i, (x, y) in enumerate(pts):
        if i == 0:
            out += [x, y]
        else:
            out += [x - px, y - py]
        px, py = x, y
    return out


def undelta_ring(flat: list[int]) -> list[tuple[float, float]]:
    pts = []
    x = y = 0
    for i in range(0, len(flat), 2):
        if i == 0:
            x, y = flat[0], flat[1]
        else:
            x += flat[i]
            y += flat[i + 1]
        pts.append((x / 1e6, y / 1e6))
    if pts:
        pts.append(pts[0])
    return pts


def polygon_rings(g: BaseGeometry) -> list[list[int]]:
    """Largest polygon of g as delta-coded rings (outer first)."""
    if isinstance(g, MultiPolygon):
        g = max(g.geoms, key=lambda p: p.area)
    if not isinstance(g, Polygon) or g.is_empty:
        return []
    return [delta_ring(g.exterior.coords)] + [delta_ring(r.coords) for r in g.interiors]


def simplify_wgs84(g: BaseGeometry, tol_m: float) -> BaseGeometry:
    if tol_m <= 0:
        return g
    s = to_metric(g).simplify(tol_m, preserve_topology=True)
    return to_wgs84(s) if not s.is_empty else g


def geom(feature: dict) -> BaseGeometry:
    return shape(feature["geometry"])


def bearing_deg(a: tuple[float, float], b: tuple[float, float]) -> float:
    """Initial great-circle bearing from a to b (lon/lat), degrees true, [0, 360)."""
    lon1, lat1, lon2, lat2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    y = math.sin(lon2 - lon1) * math.cos(lat2)
    x = math.cos(lat1) * math.sin(lat2) - math.sin(lat1) * math.cos(lat2) * math.cos(lon2 - lon1)
    return (math.degrees(math.atan2(y, x)) + 360) % 360
