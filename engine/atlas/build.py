"""atlas build: work/features.geojson + work/footprints.geojson + curation.json -> site/data/campus.bundle.

Fails loudly (BuildError) on schema violations, unresolved tour refs and size-budget breaches; everything else
(missing footprints, unreviewed photos, drafts) is listed in the build report.
"""

from __future__ import annotations

import json
import statistics
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

from shapely.geometry import Point, shape

from . import SCHEMA_VERSION, __version__, bundle, categories, geo, photos, schema, search
from .config import Config

TIMEZONE_OFFSET_H = 8  # Asia/Kuala_Lumpur, no DST


class BuildError(RuntimeError):
    pass


@dataclass
class Report:
    lines: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    errors: list[str] = field(default_factory=list)
    stats: dict = field(default_factory=dict)

    def add(self, s: str = "") -> None:
        self.lines.append(s)

    def text(self) -> str:
        out = list(self.lines)
        if self.warnings:
            out += ["", f"Warnings ({len(self.warnings)}):"] + [f"  - {w}" for w in self.warnings]
        if self.errors:
            out += ["", f"ERRORS ({len(self.errors)}):"] + [f"  ! {e}" for e in self.errors]
        return "\n".join(out) + "\n"


def _read_fc(path: Path) -> list[dict]:
    if not path.exists():
        return []
    doc = json.loads(path.read_text(encoding="utf-8"))
    return doc.get("features", [])


def load_curation(path: Path | None) -> dict:
    if not path or not path.exists():
        return {}
    doc = json.loads(path.read_text(encoding="utf-8"))
    schema.check("curation", doc, what=str(path.name))
    return doc


def _ref_matches(ref: str, f: dict) -> bool:
    p = f["properties"]
    fid = f["id"]
    return ref in (fid, fid[:8]) or (p.get("code") and ref.lower() == str(p["code"]).lower()) \
        or ref == p.get("osm_ref")


def _apply_curation(features: list[dict], cur: dict, rep: Report) -> list[dict]:
    edits = cur.get("places", {})
    for ref, patch in edits.items():
        hits = [f for f in features if _ref_matches(ref, f)]
        if not hits:
            rep.warnings.append(f"curation.places: ref {ref!r} matches nothing")
        for f in hits:
            p = f["properties"]
            for k, v in patch.items():
                if k == "add_aliases":
                    p["aliases"] = sorted(set(p.get("aliases") or []) | set(v))
                elif k == "height_m":
                    p["_height_m"] = v
                elif k != "hide":
                    p[k] = v
            if patch.get("hide"):
                p["_hide"] = True
    return [f for f in features if not f["properties"].get("_hide")]


def _local_date(ts: int) -> str:
    return datetime.fromtimestamp(ts + TIMEZONE_OFFSET_H * 3600, tz=timezone.utc).strftime("%Y-%m-%d")


def _strip_private(f: dict) -> dict:
    """Schema-check a copy without engine-internal keys (prefixed _ or osm_ref)."""
    g = json.loads(json.dumps(f))
    g["properties"] = {k: v for k, v in g["properties"].items() if not k.startswith("_") and k != "osm_ref"}
    return g


def build(cfg: Config, work_dir: Path, out_dir: Path, curation_path: Path | None = None,
          ts: int | None = None, tiles: str = "pmtiles") -> tuple[bytes, Report]:
    rep = Report()
    ts = int(ts if ts is not None else time.time())
    cur = load_curation(curation_path)

    features = _read_fc(work_dir / "features.geojson")
    try:
        schema.check_features([_strip_private(f) for f in features], "work/features.geojson")
    except schema.SchemaError as e:
        raise BuildError(str(e)) from e
    features = _apply_curation(features, cur, rep)
    if not cfg["build"].get("include_osm_drafts", True):
        features = [f for f in features if f["properties"]["source"] != "osm"]

    footprints = _read_fc(work_dir / "footprints.geojson")

    # ---- places (everything except entrances), deterministic order
    kind_order = {"building": 0, "poi": 1, "path_note": 2}
    place_feats = sorted(
        (f for f in features if f["properties"]["kind"] != "entrance"),
        key=lambda f: (kind_order[f["properties"]["kind"]], f["properties"]["name_en"].lower(), f["id"]),
    )
    ent_feats = [f for f in features if f["properties"]["kind"] == "entrance"]
    index_of = {f["id"]: i for i, f in enumerate(place_feats)}

    short_ids = {}
    for f in place_feats:
        sid = f["id"][:8]
        if sid in short_ids:
            raise BuildError(f"short id clash {sid}: {short_ids[sid]} / {f['id']}")
        short_ids[sid] = f["id"]

    storey_h = float(cfg["build"]["storey_height_m"])
    default_storeys = int(cfg["build"]["default_storeys"])
    simplify_m = float(cfg["build"]["footprint_simplify_m"])

    # ---- footprints
    fp_out: list[dict] = []
    fp_of_place: dict[int, int] = {}
    unlinked_ctx = 0
    for fp in footprints:
        fpp = fp.get("properties", {})
        linked = fpp.get("linked")
        pl = index_of.get(linked) if linked else None
        if pl is None and not cfg["build"].get("context_buildings", True):
            continue
        g = geo.simplify_wgs84(shape(fp["geometry"]), simplify_m)
        rings = geo.polygon_rings(g)
        if not rings:
            continue
        storeys = None
        if pl is not None:
            storeys = place_feats[pl]["properties"].get("floors")
        storeys = storeys or fpp.get("levels")
        h = place_feats[pl]["properties"].get("_height_m") if pl is not None else None
        h = h or fpp.get("height") or (storeys or (default_storeys if pl is not None else 1)) * storey_h
        fp_out.append({"pl": pl, "h": round(float(h), 1), "src": fpp.get("src", "osm"), "r": rings})
        if pl is not None:
            # a complex can own several outlines; the place points at the first (largest-first is not needed)
            fp_of_place.setdefault(pl, len(fp_out) - 1)
        else:
            unlinked_ctx += 1

    # ---- photos
    budget_photo = int(cfg["budget"]["photo_kb"]) * 1024
    pc = cfg["photos"]
    excluded = set(cur.get("photos", {}).get("exclude", []))
    reviewed = set(cur.get("photos", {}).get("reviewed", []))
    photo_out: list[dict] = []
    photo_index: dict[str, int] = {}
    needs_review: list[str] = []
    written: set[str] = set()

    def photo_ids(f: dict) -> list[int]:
        ids = []
        for ph in f["properties"].get("photos") or []:
            src = work_dir / ph["file"]
            if not src.exists():
                rep.warnings.append(f"{f['properties']['name_en']}: photo {ph['file']} missing")
                continue
            digest = photos.sha256_file(src)
            keys = {digest, src.name, ph["file"]}
            if keys & excluded or any(len(x) >= 12 and digest.startswith(x) for x in excluded):
                rep.add(f"  photo excluded by curation: {src.name}")
                continue
            if digest not in photo_index:
                try:
                    pr = photos.process(src, out_dir, max_px=int(pc["max_px"]), quality=int(pc["quality"]),
                                        min_quality=int(pc["min_quality"]), budget_bytes=budget_photo)
                except photos.PhotoError as e:
                    rep.errors.append(str(e))
                    continue
                photo_index[digest] = len(photo_out)
                photo_out.append({"f": pr.out_name, "w": pr.width, "h": pr.height, "cap": ph.get("caption")})
                written.add(pr.out_name)
                if not (keys & reviewed or any(len(x) >= 12 and digest.startswith(x) for x in reviewed)):
                    needs_review.append(f"{pr.out_name}  <- {src.name} ({f['properties']['name_en']})")
            ids.append(photo_index[digest])
        return ids

    # ---- place records
    places: list[dict] = []
    for i, f in enumerate(place_feats):
        p = f["properties"]
        lon, lat = f["geometry"]["coordinates"][:2]
        rec = {
            "id": f["id"][:8],
            "k": p["kind"],
            "c": p["category"],
            "en": p["name_en"],
            "ms": p.get("name_ms"),
            "code": p.get("code"),
            "al": list(p.get("aliases") or []),
            "p": geo.micro_pt(lon, lat),
            "par": index_of.get(p.get("parent_id")) if p.get("parent_id") else None,
            "fl": p.get("floor"),
            "nf": p.get("floors"),
            "h": fp_out[fp_of_place[i]]["h"] if i in fp_of_place else None,
            "rooms": [[r["name"], r.get("code"), r.get("floor"), r.get("kind", "other")] for r in p.get("rooms") or []],
            "fp": fp_of_place.get(i),
            "ents": [],
            "ph": photo_ids(f),
            "lv": p.get("last_verified"),
            "src": p["source"],
            "ok": bool(p.get("verified")) and p["source"] == "survey",
            "acc": round(p["capture"]["std_m"], 1) if p.get("capture") else None,
            "sf": p.get("accessible"),
            "note": p.get("notes") if p["kind"] == "path_note" else None,
            "note_ms": None,
        }
        places.append(rec)

    # ---- entrances
    entrances: list[dict] = []
    orphans = []
    for f in sorted(ent_feats, key=lambda f: f["id"]):
        p = f["properties"]
        lon, lat = f["geometry"]["coordinates"][:2]
        pl = index_of.get(p.get("parent_id"))
        if pl is None:
            # nearest building within 60 m
            best = None
            for j, pf in enumerate(place_feats):
                if pf["properties"]["kind"] != "building":
                    continue
                d = geo.haversine_m((lon, lat), pf["geometry"]["coordinates"][:2])
                if d < 60 and (best is None or d < best[0]):
                    best = (d, j)
            pl = best[1] if best else None
        if pl is None:
            orphans.append(p["name_en"])
            continue
        entrances.append({
            "pl": pl, "p": geo.micro_pt(lon, lat), "b": round(float(p["bearing_deg"]), 1),
            "en": p.get("name_en"), "ms": p.get("name_ms"),
            "main": "main" in p["name_en"].lower() or "utama" in (p.get("name_ms") or "").lower(),
            "sf": p.get("accessible"), "snap": bool(p.get("_snapped")),
        })
        places[pl]["ents"].append(len(entrances) - 1)
        for ph in photo_ids(f):
            if ph not in places[pl]["ph"]:
                places[pl]["ph"].append(ph)
    for name in orphans:
        rep.warnings.append(f"entrance {name!r} has no building within 60 m; left out")

    # ---- tour
    tour = []
    for step in cur.get("tour", []):
        hits = [i for i, f in enumerate(place_feats) if _ref_matches(step["ref"], f)]
        if not hits:
            rep.errors.append(f"tour step {step['title_en']!r}: ref {step['ref']!r} matches no place")
            continue
        tour.append({"pl": hits[0], "en": step["title_en"], "ms": step.get("title_ms"),
                     "t_en": step.get("text_en"), "t_ms": step.get("text_ms")})

    # ---- campus + payload
    c = cfg.campus
    w, s, e, n = cfg.bbox
    counts = {
        "places": len(places), "verified": sum(1 for p in places if p["ok"]),
        "entrances": len(entrances), "footprints": len(fp_out), "photos": len(photo_out),
    }
    payload = {
        "v": SCHEMA_VERSION,
        "build": {"ts": ts, "date": _local_date(ts), "engine": __version__, "tiles": tiles, "counts": counts},
        "campus": {
            "id": c["id"], "en": c["name_en"], "ms": c["name_ms"],
            "center": geo.micro_pt(*c["center"]), "bbox": [geo.micro(w), geo.micro(s), geo.micro(e), geo.micro(n)],
            "zoom": float(c["zoom"]), "bearing": float(c.get("bearing", 0)), "tz": c.get("tz", "Asia/Kuala_Lumpur"),
        },
        "categories": categories.table(),
        "places": places,
        "entrances": entrances,
        "footprints": fp_out,
        "search": search.index(places),
        "tour": tour,
        "photos": photo_out,
    }
    errs = schema.errors("bundle", payload)
    if errs:
        raise BuildError("bundle payload violates bundle.schema.json:\n  " + "\n  ".join(errs[:20]))

    data = bundle.encode(payload, ts)

    # ---- report
    survey = [f for f in place_feats + ent_feats if f["properties"]["source"] == "survey"]
    stds = [f["properties"]["capture"]["std_m"] for f in survey if f["properties"].get("capture")]
    bests = [f["properties"]["capture"]["best_acc_m"] for f in survey if f["properties"].get("capture")]
    warn_acc = float(cfg["survey"]["warn_accuracy_m"])
    rep.add(f"UTHM Atlas build  {payload['build']['date']}  (engine {__version__}, schema v{SCHEMA_VERSION}, ts {ts})")
    rep.add("")
    rep.add(f"Places      {len(places):4d}   buildings {sum(p['k'] == 'building' for p in places)}, "
            f"POIs {sum(p['k'] == 'poi' for p in places)}, path notes {sum(p['k'] == 'path_note' for p in places)}")
    rep.add(f"Verified    {counts['verified']:4d}   walked + confirmed")
    rep.add(f"Drafts      {sum(1 for p in places if not p['ok']):4d}   from OSM, badged 'Not walked yet'")
    rep.add(f"Entrances   {len(entrances):4d}   snapped {sum(e['snap'] for e in entrances)}")
    rep.add(f"Footprints  {len(fp_out):4d}   linked {len(fp_of_place)}, context {unlinked_ctx}")
    rep.add(f"Photos      {len(photo_out):4d}   needing review {len(needs_review)}")
    rep.add(f"Tour steps  {len(tour):4d}")
    if stds:
        rep.add("")
        rep.add(f"Capture accuracy (n={len(stds)}): std-dev median {statistics.median(stds):.1f} m, "
                f"max {max(stds):.1f} m; best-fix median {statistics.median(bests):.1f} m")
        weak = [f["properties"]["name_en"] for f in survey
                if f["properties"].get("capture") and f["properties"]["capture"]["best_acc_m"] > warn_acc]
        if weak:
            rep.warnings.append(f"{len(weak)} capture(s) worse than {warn_acc:g} m: " + ", ".join(weak[:10]))
    no_fp = [p["en"] for i, p in enumerate(places) if p["k"] == "building" and i not in fp_of_place]
    if no_fp:
        rep.warnings.append(f"{len(no_fp)} building(s) without a footprint (trace in engine/overrides/): "
                            + ", ".join(no_fp[:12]) + (" ..." if len(no_fp) > 12 else ""))
    conflicts_path = work_dir / "conflicts.json"
    if conflicts_path.exists():
        conflicts = json.loads(conflicts_path.read_text(encoding="utf-8"))
        if conflicts:
            rep.warnings.append(f"{len(conflicts)} dedupe conflict(s) logged in engine/work/conflicts.json")
    if needs_review:
        rep.add("")
        rep.add("Photos to review for faces / number plates (add to curation.json photos.reviewed or .exclude):")
        rep.lines += [f"  {x}" for x in needs_review]

    # ---- budgets
    rep.add("")
    rep.add("Size budget")
    budget_kb = int(cfg["budget"]["bundle_kb"])
    rep.add(f"  campus.bundle  {len(data) / 1024:8.1f} KB / {budget_kb} KB")
    if len(data) > budget_kb * 1024:
        rep.errors.append(f"campus.bundle is {len(data) / 1024:.0f} KB, budget {budget_kb} KB")
    pm = out_dir / "campus.pmtiles"
    if pm.exists():
        pm_kb = int(cfg["budget"]["pmtiles_kb"])
        rep.add(f"  campus.pmtiles {pm.stat().st_size / 1024:8.1f} KB / {pm_kb} KB")
        if pm.stat().st_size > pm_kb * 1024:
            rep.errors.append(f"campus.pmtiles is {pm.stat().st_size / 1024:.0f} KB, budget {pm_kb} KB")
    if photo_out:
        sizes = [(out_dir / ph["f"]).stat().st_size for ph in photo_out]
        rep.add(f"  photos         {sum(sizes) / 1024:8.1f} KB total, largest {max(sizes) / 1024:.1f} KB / "
                f"{budget_photo // 1024} KB")

    rep.stats = {"counts": counts, "bytes": len(data), "needs_review": len(needs_review), "written_photos": written}
    if rep.errors:
        raise BuildError(rep.text())
    return data, rep


def write(data: bytes, rep: Report, out_dir: Path, tiles: str) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "campus.bundle").write_bytes(data)
    version, ts = bundle.read_header(data)
    (out_dir / "version.json").write_text(
        json.dumps({"v": version, "ts": ts, "bytes": len(data), "tiles": tiles}) + "\n", encoding="utf-8")
    # glyphs for every place name (MapLibre renders the labels), then the offline file list
    from . import basemap, glyphs
    b = bundle.decode(data).payload
    texts = [p["en"] for p in b["places"]] + [p["ms"] or "" for p in b["places"]]
    glyphs.ensure(out_dir.parent / "map", glyphs.ranges_for(texts), log=lambda *_: None)
    # drop photos no longer referenced
    keep = rep.stats.get("written_photos", set())
    pdir = out_dir / "photos"
    if pdir.exists():
        for f in pdir.glob("*.webp"):
            if f"photos/{f.name}" not in keep:
                f.unlink()
    basemap.write_file_list(out_dir)
