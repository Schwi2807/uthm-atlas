"""atlas footprints: OSM building outlines + hand-traced overrides, linked to building anchors; entrances snapped.

Overrides: engine/overrides/*.geojson, Polygon features. Properties (all optional):
  link      ref of the building feature it belongs to (uuid, 8-hex short id or code)
  replaces  OSM ref ("osm:way/123") of an outline it replaces; otherwise any OSM outline it covers by >50% is dropped
  levels    storeys, name  a label for the report
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from pathlib import Path

from shapely.geometry import MultiPolygon, Point, Polygon, mapping, shape
from shapely.ops import nearest_points

from . import geo, osmdata
from .config import Config
from .ingest import _merge_into, load_features, save_features
from .paths import OVERRIDES


@dataclass
class Footprint:
    ref: str
    geom: object  # lon/lat polygon
    metric: object
    levels: int | None = None
    height: float | None = None
    name: str | None = None
    src: str = "osm"
    linked: str | None = None
    link_hint: str | None = None


@dataclass
class FootprintResult:
    footprints: int = 0
    overrides: int = 0
    linked: int = 0
    needs_tracing: list[str] = field(default_factory=list)
    snapped: int = 0
    not_snapped: list[str] = field(default_factory=list)
    drafts_replaced: int = 0
    clashes: list[str] = field(default_factory=list)

    def summary(self) -> str:
        out = [f"footprints: {self.footprints} outlines ({self.overrides} hand-traced), {self.linked} linked to buildings",
               f"  entrances snapped to an edge: {self.snapped}"]
        if self.drafts_replaced:
            out.append(f"  walked buildings replaced {self.drafts_replaced} OSM draft(s)")
        if self.needs_tracing:
            out.append(f"  needs tracing ({len(self.needs_tracing)}), add polygons to engine/overrides/: "
                       + ", ".join(self.needs_tracing))
        if self.not_snapped:
            out.append("  not snapped (too far from the outline): " + ", ".join(self.not_snapped))
        out += [f"  clash: {c}" for c in self.clashes]
        return "\n".join(out)


def _num(v) -> float | None:
    if v is None:
        return None
    m = re.match(r"\s*([\d.]+)", str(v))
    return float(m.group(1)) if m else None


def osm_footprints(elements: list[dict]) -> list[Footprint]:
    out = []
    for el in elements:
        if not osmdata.is_building(el):
            continue
        g = osmdata.geometry(el)
        if not isinstance(g, (Polygon, MultiPolygon)) or g.is_empty:
            continue
        t = el.get("tags") or {}
        lv = _num(t.get("building:levels"))
        out.append(Footprint(osmdata.ref(el), g, geo.to_metric(g), int(lv) if lv else None, _num(t.get("height")),
                             t.get("name")))
    return out


def load_overrides(folder: Path = OVERRIDES) -> list[tuple[Footprint, str | None]]:
    out = []
    for path in sorted(folder.glob("*.geojson")):
        doc = json.loads(path.read_text(encoding="utf-8"))
        feats = doc["features"] if doc.get("type") == "FeatureCollection" else [doc]
        for i, f in enumerate(feats):
            g = shape(f["geometry"])
            if not isinstance(g, (Polygon, MultiPolygon)):
                continue
            p = f.get("properties") or {}
            fp = Footprint(f"override:{path.stem}/{i}", g, geo.to_metric(g), p.get("levels"), p.get("height"),
                           p.get("name"), "override", link_hint=p.get("link"))
            out.append((fp, p.get("replaces")))
    return out


def _matches(ref: str, f: dict) -> bool:
    p = f["properties"]
    return ref in (f["id"], f["id"][:8]) or (p.get("code") and ref.lower() == str(p["code"]).lower())


def run(cfg: Config, work: Path, refresh: bool = False, elements: list[dict] | None = None,
        overrides_dir: Path = OVERRIDES) -> FootprintResult:
    res = FootprintResult()
    if elements is None:
        elements = osmdata.fetch(cfg, refresh=refresh)
    fps = osm_footprints(elements)
    for ov, replaces in load_overrides(overrides_dir):
        keep = []
        for fp in fps:
            covered = fp.metric.intersection(ov.metric).area / max(fp.metric.area, 1e-9)
            if fp.ref == replaces or covered > 0.5:
                continue
            keep.append(fp)
        fps = keep + [ov]
        res.overrides += 1
    by_ref = {fp.ref: fp for fp in fps}

    features = load_features(work)
    by_id = {f["id"]: f for f in features}
    link_max = float(cfg["survey"]["link_max_m"])
    snap_max = float(cfg["survey"]["snap_max_m"])
    conflicts_path = work / "conflicts.json"
    conflicts = json.loads(conflicts_path.read_text(encoding="utf-8")) if conflicts_path.exists() else []

    # 1. explicit override links
    for fp in fps:
        if fp.link_hint:
            hits = [f for f in features if f["properties"]["kind"] == "building" and _matches(fp.link_hint, f)]
            if hits:
                fp.linked = hits[0]["id"]
            else:
                res.clashes.append(f"override {fp.ref} links to {fp.link_hint!r}, which matches no building")

    # 2. walked buildings first (they win footprints), then drafts by their own OSM ref
    buildings = [f for f in features if f["properties"]["kind"] == "building"]
    buildings.sort(key=lambda f: 0 if f["properties"]["source"] != "osm" else 1)
    linked_fp_of: dict[str, Footprint] = {fp.linked: fp for fp in fps if fp.linked}
    for b in buildings:
        if b["id"] in linked_fp_of:
            continue
        p = b["properties"]
        target = None
        if p["source"] == "osm":
            target = by_ref.get(p.get("osm_ref"))
            if target is not None and target.linked:
                target = None  # a walked building took it; this draft is replaced below
            # extra outlines of the same named complex (seed collapsed them into this one place)
            for extra in p.get("_osm_refs") or []:
                fp2 = by_ref.get(extra)
                if fp2 is not None and not fp2.linked and target is not None:
                    fp2.linked = b["id"]
        else:
            pt = Point(*geo.xy(*b["geometry"]["coordinates"][:2]))
            inside = [fp for fp in fps if fp.metric.contains(pt)]
            if inside:
                target = min(inside, key=lambda fp: fp.metric.area)
            else:
                d, fp = min(((fp.metric.distance(pt), fp) for fp in fps), default=(None, None), key=lambda x: x[0] or 0)
                if fp is not None and d <= link_max:
                    target = fp
            if target is not None and target.linked and target.linked != b["id"]:
                other = by_id.get(target.linked)
                res.clashes.append(f"{p['name_en']!r} and {other['properties']['name_en'] if other else target.linked!r} "
                                   f"both sit on {target.ref}; first one kept")
                target = None
        if target is None:
            continue
        target.linked = b["id"]
        linked_fp_of[b["id"]] = target

    # 3. drafts whose footprint went to a walked building are replaced by it
    for b in list(buildings):
        p = b["properties"]
        if p["source"] != "osm":
            continue
        fp = by_ref.get(p.get("osm_ref"))
        if fp is not None and fp.linked and fp.linked != b["id"]:
            walked = by_id[fp.linked]
            _merge_into(walked, b)
            walked["properties"].setdefault("osm_ref", p.get("osm_ref"))
            for g in features:  # re-parent POIs/entrances that pointed at the draft
                if g["properties"].get("parent_id") == b["id"]:
                    g["properties"]["parent_id"] = walked["id"]
            features.remove(b)
            del by_id[b["id"]]
            res.drafts_replaced += 1
            conflicts.append({"type": "replaced_draft", "kept": walked["id"], "dropped": b["id"], "via": fp.ref,
                              "kept_name": walked["properties"]["name_en"], "dropped_name": p["name_en"]})

    res.footprints = len(fps)
    res.linked = sum(1 for fp in fps if fp.linked and fp.linked in by_id)
    res.needs_tracing = sorted(f["properties"]["name_en"] for f in features
                               if f["properties"]["kind"] == "building" and f["properties"]["source"] != "osm"
                               and f["id"] not in linked_fp_of)

    # 4. snap walked entrances onto their building's outline (always from the original capture: idempotent)
    for e in features:
        p = e["properties"]
        if p["kind"] != "entrance" or p["source"] == "osm":
            continue
        orig = p.get("_orig") or e["geometry"]["coordinates"][:2]
        pt = Point(*geo.xy(*orig))
        fp = linked_fp_of.get(p.get("parent_id")) if p.get("parent_id") else None
        if fp is None:
            cands = [(f.metric.exterior.distance(pt) if isinstance(f.metric, Polygon) else f.metric.boundary.distance(pt), f)
                     for f in fps if f.linked]
            if cands:
                d, fp = min(cands, key=lambda x: x[0])
                if d > snap_max:
                    fp = None
                elif not p.get("parent_id"):
                    p["parent_id"] = fp.linked
        if fp is None:
            continue
        ring = fp.metric.exterior if isinstance(fp.metric, Polygon) else max(fp.metric.geoms, key=lambda g: g.area).exterior
        q = nearest_points(ring, pt)[0]
        d = q.distance(pt)
        if d <= snap_max:
            p["_orig"] = [round(orig[0], 7), round(orig[1], 7)]
            lon, lat = geo.lonlat(q.x, q.y)
            e["geometry"]["coordinates"] = [round(lon, 7), round(lat, 7)]
            p["_snapped"] = True
            res.snapped += 1
        else:
            p.pop("_snapped", None)
            res.not_snapped.append(f"{p['name_en']} ({d:.0f} m)")

    out = []
    for fp in fps:
        out.append({"type": "Feature", "geometry": mapping(fp.geom),
                    "properties": {"ref": fp.ref, "levels": fp.levels, "height": fp.height, "name": fp.name,
                                   "src": fp.src, "linked": fp.linked if fp.linked in by_id else None}})
    work.mkdir(parents=True, exist_ok=True)
    (work / "footprints.geojson").write_text(json.dumps({"type": "FeatureCollection", "features": out}),
                                             encoding="utf-8", newline="\n")
    save_features(work, features)
    conflicts_path.write_text(json.dumps(conflicts, indent=1, ensure_ascii=False) + "\n", encoding="utf-8", newline="\n")
    return res
