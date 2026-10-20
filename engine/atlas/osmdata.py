"""One cached Overpass query for the whole campus bbox, shared by seed, footprints and the suggestion pack."""

from __future__ import annotations

import uuid

from shapely.geometry import LineString, MultiPolygon, Point, Polygon
from shapely.ops import polygonize, unary_union

from . import osm
from .config import Config

# Stable feature ids for OSM drafts: uuid5(namespace, "osm:way/123")
OSM_NS = uuid.UUID("6f1d3a52-2b8e-4c51-9a6e-5b0d8e7c4a10")


def campus_ql(bbox: list[float]) -> str:
    b = osm.bbox_ql(bbox)
    return f"""[out:json][timeout:120];
(
  way["building"]{b};
  relation["building"]{b};
  nwr["amenity"]{b};
  nwr["shop"]{b};
  node["highway"="bus_stop"]{b};
  nwr["leisure"~"^(sports_centre|stadium|pitch|track|park)$"]["name"]{b};
  nwr["office"]["name"]{b};
  nwr["tourism"]["name"]{b};
);
out geom tags;"""


def fetch(cfg: Config, refresh: bool = False) -> list[dict]:
    oc = cfg.raw.get("overpass", {})
    data = osm.query(campus_ql(cfg.bbox), endpoints=oc.get("endpoints"), max_age_days=oc.get("max_age_days", 30),
                     refresh=refresh)
    return data.get("elements", [])


def ref(el: dict) -> str:
    return f"osm:{el['type']}/{el['id']}"


def feature_id(el: dict) -> str:
    return str(uuid.uuid5(OSM_NS, ref(el)))


def geometry(el: dict):
    """Shapely geometry (lon/lat) for an Overpass `out geom` element, or None."""
    t = el["type"]
    if t == "node":
        return Point(el["lon"], el["lat"])
    if t == "way":
        pts = [(p["lon"], p["lat"]) for p in el.get("geometry") or [] if p]
        if len(pts) >= 4 and pts[0] == pts[-1]:
            poly = Polygon(pts)
            return poly if poly.is_valid else poly.buffer(0)
        return LineString(pts) if len(pts) >= 2 else None
    if t == "relation":
        outers, inners = [], []
        for m in el.get("members") or []:
            if m.get("type") != "way" or not m.get("geometry"):
                continue
            line = LineString([(p["lon"], p["lat"]) for p in m["geometry"] if p])
            (inners if m.get("role") == "inner" else outers).append(line)
        polys = list(polygonize(unary_union(outers))) if outers else []
        if not polys:
            return None
        shell = unary_union(polys)
        if inners:
            holes = list(polygonize(unary_union(inners)))
            if holes:
                shell = shell.difference(unary_union(holes))
        if isinstance(shell, (Polygon, MultiPolygon)) and not shell.is_empty:
            return shell
    return None


def anchor(g) -> Point:
    return g if isinstance(g, Point) else g.representative_point()


def is_building(el: dict) -> bool:
    return "building" in (el.get("tags") or {}) and el["type"] in ("way", "relation")
