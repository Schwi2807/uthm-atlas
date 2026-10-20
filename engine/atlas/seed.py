"""atlas seed / atlas suggest: OSM-derived draft places and the Surveyor's name-suggestion pack.

Drafts are clearly second-class: source="osm", verified=false, last_verified=null, and the site badges them
"Not walked yet". A walked survey feature that lands on a draft replaces it during ingest (see ingest.py).
"""

from __future__ import annotations

import json
import re
from datetime import datetime, timezone
from pathlib import Path

import requests
from shapely.geometry import Point, mapping, shape
from shapely.ops import unary_union

from . import geo, osm, osmdata
from .glossary import gloss
from .config import Config
from .paths import CACHE

# "A1 Dewan Tunku Mahkota Ismail" -> code A1, rest; "C14A Pentas Terbuka"; "E16\ Bilik Kuliah"
_CODE_PREFIX = re.compile(r"^([A-Z]{1,2}\d{1,2}[A-Z]?)\b[\\/:\-\s]*(.*)$")
_MALAY_HINT = re.compile(r"\b(fakulti|dewan|kolej|pusat|perpustakaan|bangunan|blok|masjid|surau|pejabat|makmal|kediaman|"
                         r"bilik|kuliah|asrama|bengkel|pensyarah|pengajar|kompleks|stor|rumah|peperiksaan|siswa|siswi|"
                         r"tempat|pentas|terbuka|dataran|kabin|garaj|pentadbiran|penerbitan|kafeteria|desasiswa|"
                         r"kokurikulum|pengajian)\b", re.I)
_GENERIC_NAMES = {"car park": "Car park", "motor parking": "Motorcycle parking", "bus stop": "Bus stop"}

# Parit Raja faculties (public names). Used to expand OSM's bare acronyms on draft buildings.
FACULTIES = {
    "FKEE": ("Faculty of Electrical and Electronic Engineering", "Fakulti Kejuruteraan Elektrik dan Elektronik"),
    "FKAAB": ("Faculty of Civil Engineering and Built Environment", "Fakulti Kejuruteraan Awam dan Alam Bina"),
    "FKAAS": ("Faculty of Civil Engineering and Built Environment", "Fakulti Kejuruteraan Awam dan Alam Bina"),
    "FKMP": ("Faculty of Mechanical and Manufacturing Engineering", "Fakulti Kejuruteraan Mekanikal dan Pembuatan"),
    "FSKTM": ("Faculty of Computer Science and Information Technology", "Fakulti Sains Komputer dan Teknologi Maklumat"),
    "FPTP": ("Faculty of Technology Management and Business", "Fakulti Pengurusan Teknologi dan Perniagaan"),
    "FPTV": ("Faculty of Technical and Vocational Education", "Fakulti Pendidikan Teknikal dan Vokasional"),
}
FACULTY_ACRONYMS = set(FACULTIES) | {"FAST", "FTK"}


def _now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def campus_area(cfg: Config, refresh: bool = False):
    """The university's OSM boundary (relation), buffered; falls back to the core bbox."""
    rel = cfg.campus.get("osm_relation")
    buf = float(cfg.campus.get("draft_buffer_m", 80))
    if rel:
        oc = cfg.raw.get("overpass", {})
        data = osm.query(f"[out:json][timeout:60];relation({int(rel)});out geom;", endpoints=oc.get("endpoints"),
                         max_age_days=oc.get("max_age_days", 30), refresh=refresh)
        els = data.get("elements", [])
        if els:
            g = osmdata.geometry(els[0])
            if g is not None and not g.is_empty:
                return geo.to_wgs84(geo.to_metric(g).buffer(buf))
    w, s, e, n = cfg.core_bbox
    from shapely.geometry import box
    return box(w, s, e, n)


def split_code(name: str) -> tuple[str | None, str]:
    m = _CODE_PREFIX.match(name.strip())
    if m:
        return m.group(1), m.group(2).strip()
    return None, name.strip()


def category_for(tags: dict, name: str, building: bool) -> str | None:
    n = name.lower()
    a = tags.get("amenity", "")
    b = tags.get("building", "")
    if tags.get("highway") == "bus_stop" or n.endswith("bus stop"):
        return "bus_stop"
    if a == "place_of_worship" or b in ("mosque", "musalla"):
        return "mosque" if ("masjid" in n or b == "mosque" and "surau" not in n) else "surau"
    if a == "library" or "perpustakaan" in n or "library" in n:
        return "library"
    if a in ("cafe", "restaurant", "fast_food", "food_court") or "kafeteria" in n or "dewan makan" in n:
        return "cafe"
    if a in ("atm", "bank"):
        return "atm"
    if a == "toilets":
        return "toilet"
    if a in ("parking", "motorcycle_parking"):
        return "parking"
    if a in ("clinic", "doctors", "hospital", "pharmacy") or "kesihatan" in n:
        return "clinic"
    if tags.get("shop") == "copyshop" or "print" in n or "cetak" in n:
        return "printing"
    if "shop" in tags:
        return "shop"
    if tags.get("leisure") in ("sports_centre", "stadium", "pitch", "track") or "sukan" in n or "stadium" in n:
        return "sports"
    if "fakulti" in n or "faculty" in n or name.strip().upper() in FACULTY_ACRONYMS or n.startswith(("fk", "fs", "fp")) and len(name) <= 6:
        return "faculty"
    if b == "dormitory" or "kolej kediaman" in n or "residential college" in n or "asrama" in n or "desasiswa" in n \
            or tags.get("tourism") == "hostel":
        return "residential" if building else "kolej_block"
    if re.search(r"\b(dewan kuliah|bilik kuliah|kompleks dewan kuliah|lecture (hall|room)s?)\b", n):
        return "lecture_hall"
    if "makmal" in n or "lab" in n.split() or "bengkel" in n or "workshop" in n:
        return "lab" if not building else "building_other"
    if "dewan" in n or "hall" in n or tags.get("amenity") == "theatre":
        return "hall"
    if "pejabat" in n or "pentadbiran" in n or "canselori" in n or "office" in n or "office" in tags:
        return "admin" if building else "office"
    if building:
        return "building_other"
    if tags.get("amenity") == "fountain" or tags.get("tourism") in ("viewpoint", "artwork"):
        return "landmark"
    return None


def _names(tags: dict, building: bool) -> dict | None:
    raw = (tags.get("name") or "").strip()
    if not raw and not building:
        return None
    raw = raw.split(";")[0].strip()
    code, rest = split_code(raw) if raw else (None, "")
    if not code and (tags.get("ref") or "").strip():
        code = tags["ref"].strip()
    aliases: list[str] = []
    for k in ("alt_name", "short_name", "old_name", "official_name", "loc_name"):
        aliases += [x.strip() for x in (tags.get(k) or "").split(";") if x.strip()]
    if raw.upper() in FACULTIES:
        acr = raw.upper()
        en_full, ms_full = FACULTIES[acr]
        aliases += [acr] + ([ms_full] if tags.get("name:ms") else [])
        return {"name_en": tags.get("name:en") or en_full, "name_ms": tags.get("name:ms") or ms_full,
                "code": tags.get("ref") or None, "aliases": sorted(set(aliases) - {en_full})}
    en = (tags.get("name:en") or "").strip()
    ms = (tags.get("name:ms") or "").strip()
    generic = _GENERIC_NAMES.get(rest.lower()) if rest else None
    if generic:
        en = en or generic
    if not rest and code:
        en = en or f"Building {code}"
        ms = ms or f"Bangunan {code}"
        aliases.append(f"Blok {code}")
    elif rest:
        if not ms and _MALAY_HINT.search(rest):
            ms = rest
        if not en:
            g = gloss(rest)
            if g:
                ms = ms or rest
            en = g or rest
    if not en:
        return None
    if ms == en:
        ms = ms if _MALAY_HINT.search(ms) else None
    return {"name_en": en, "name_ms": ms or None, "code": code, "aliases": sorted(set(aliases) - {en, ms})}


def drafts(cfg: Config, elements: list[dict], area) -> list[dict]:
    out: list[dict] = []
    seen: set[tuple[str, int, int]] = set()
    for el in elements:
        tags = el.get("tags") or {}
        building = osmdata.is_building(el)
        if not building and not any(k in tags for k in ("amenity", "shop", "highway", "leisure", "office", "tourism")):
            continue
        if tags.get("amenity") in ("parking_space", "shelter", "post_box", "bench", "waste_basket", "motorcycle_parking") \
                and not tags.get("name"):
            continue
        if building and (not tags.get("name") or tags["name"].strip().lower() in ("bus stop", "shelter")):
            continue  # unnamed buildings / shelters are context footprints only
        names = _names(tags, building)
        if not names:
            if tags.get("amenity") in ("toilets", "atm", "parking") or tags.get("highway") == "bus_stop":
                cat = category_for(tags, "", False)
                from .categories import CATEGORIES
                names = {"name_en": CATEGORIES[cat][0], "name_ms": CATEGORIES[cat][1], "code": None, "aliases": []}
            else:
                continue
        cat = category_for(tags, (tags.get("name") or "") + " " + names["name_en"], building)
        if cat is None:
            continue
        g = osmdata.geometry(el)
        if g is None or g.is_empty:
            continue
        pt = osmdata.anchor(g)
        if not area.contains(pt):
            continue
        # one draft per name per ~10 m cell (OSM often has the same library as node + way)
        key = (names["name_en"].lower(), round(pt.x * 1e4), round(pt.y * 1e4))
        if key in seen:
            continue
        seen.add(key)
        levels = tags.get("building:levels")
        try:
            floors = max(1, min(30, int(float(levels)))) if levels else None
        except ValueError:
            floors = None
        props = {
            "campus_id": cfg.campus["id"],
            "kind": "building" if building else "poi",
            "category": cat,
            "name_en": names["name_en"],
            "name_ms": names["name_ms"],
            "aliases": names["aliases"],
            "code": names["code"],
            "source": "osm",
            "verified": False,
            "last_verified": None,
            "osm_ref": osmdata.ref(el),
        }
        if building and floors:
            props["floors"] = floors
        out.append({"type": "Feature", "id": osmdata.feature_id(el),
                     "geometry": {"type": "Point", "coordinates": [round(pt.x, 7), round(pt.y, 7)]},
                     "properties": props})
    return _tidy(out)


def _tidy(drafts: list[dict], cluster_m: float = 150.0, near_m: float = 250.0) -> list[dict]:
    """Collapse same-name buildings (one place, several outlines), drop POIs that repeat a building,
    and give generic POIs ("Car park") a "near <building>" suffix so search results are tellable apart."""
    buildings = [d for d in drafts if d["properties"]["kind"] == "building"]
    pois = [d for d in drafts if d["properties"]["kind"] == "poi"]
    kept_b: list[dict] = []
    for b in buildings:
        p = b["properties"]
        twin = next((k for k in kept_b if k["properties"]["name_en"] == p["name_en"]
                     and k["properties"].get("code") == p.get("code")
                     and geo.haversine_m(k["geometry"]["coordinates"], b["geometry"]["coordinates"]) < cluster_m), None)
        if twin:
            twin["properties"].setdefault("_osm_refs", []).append(p["osm_ref"])
            continue
        kept_b.append(b)
    names = {}
    for b in kept_b:
        p = b["properties"]
        for n in [p["name_en"], p.get("name_ms")] + list(p.get("aliases") or []):
            if n:
                names.setdefault(n.lower(), []).append(b)
    kept_p = []
    for d in pois:
        p = d["properties"]
        dup = [b for b in names.get(p["name_en"].lower(), [])
               if geo.haversine_m(b["geometry"]["coordinates"], d["geometry"]["coordinates"]) < near_m]
        if dup:
            bp = dup[0]["properties"]
            if p["category"] in ("cafe", "library", "surau", "mosque") and bp["category"] == "building_other":
                bp["category"] = p["category"]
            continue
        kept_p.append(d)
    generic = {v.lower() for v in _GENERIC_NAMES.values()} | {"atm", "toilet", "bus stop", "parking"}
    named_b = [b for b in kept_b if not b["properties"]["name_en"].startswith("Building ")]
    for d in kept_p:
        p = d["properties"]
        if p["name_en"].lower() not in generic or not named_b:
            continue
        dist, b = min(((geo.haversine_m(b["geometry"]["coordinates"], d["geometry"]["coordinates"]), b) for b in named_b),
                      key=lambda x: x[0])
        if dist <= near_m:
            label = b["properties"].get("aliases") and next((a for a in b["properties"]["aliases"] if a.isupper()), None)
            label = label or b["properties"]["name_en"]
            p["name_en"] = f"{p['name_en']} near {label}"
            if p.get("name_ms"):
                p["name_ms"] = f"{p['name_ms']} berhampiran {b['properties'].get('name_ms') or label}"
    return kept_b + kept_p


def merge_drafts(features: list[dict], new_drafts: list[dict]) -> tuple[list[dict], int, int]:
    """Replace all previous OSM drafts with new ones, except drafts already claimed by a survey feature."""
    kept = [f for f in features if f["properties"]["source"] != "osm"]
    claimed = {f["properties"].get("osm_ref") for f in kept if f["properties"].get("osm_ref")}
    claimed |= {f["id"] for f in kept}
    added = [d for d in new_drafts if d["properties"]["osm_ref"] not in claimed and d["id"] not in claimed]
    return kept + added, len(added), len(new_drafts) - len(added)


def seed(cfg: Config, work: Path, refresh: bool = False) -> str:
    from .ingest import load_features, save_features
    els = osmdata.fetch(cfg, refresh=refresh)
    area = campus_area(cfg, refresh=refresh)
    new = drafts(cfg, els, area)
    features = load_features(work)
    merged, added, skipped = merge_drafts(features, new)
    save_features(work, merged)
    cats: dict[str, int] = {}
    for d in new:
        cats[d["properties"]["category"]] = cats.get(d["properties"]["category"], 0) + 1
    top = ", ".join(f"{k} {v}" for k, v in sorted(cats.items(), key=lambda kv: -kv[1]))
    return (f"seed: {added} OSM draft places written ({skipped} already replaced by walked features)\n"
            f"  categories: {top}\n  next: atlas footprints")


# ---------------------------------------------------------------- suggestion pack

WIKIDATA_SPARQL = "https://query.wikidata.org/sparql"


def wikidata_labels(cfg: Config, refresh: bool = False) -> list[dict]:
    lon, lat = cfg.campus["center"]
    cache = CACHE / "wikidata.json"
    import time
    if cache.exists() and not refresh and time.time() - cache.stat().st_mtime < 30 * 86400:
        return json.loads(cache.read_text(encoding="utf-8"))
    q = f"""SELECT ?item ?coord ?en ?ms WHERE {{
  SERVICE wikibase:around {{ ?item wdt:P625 ?coord . bd:serviceParam wikibase:center "Point({lon} {lat})"^^geo:wktLiteral .
                             bd:serviceParam wikibase:radius "2.5" . }}
  OPTIONAL {{ ?item rdfs:label ?en FILTER(LANG(?en) = "en") }}
  OPTIONAL {{ ?item rdfs:label ?ms FILTER(LANG(?ms) = "ms") }}
}}"""
    try:
        r = requests.get(WIKIDATA_SPARQL, params={"query": q, "format": "json"},
                         headers={"User-Agent": osm.USER_AGENT, "Accept": "application/sparql-results+json"}, timeout=60)
        r.raise_for_status()
        rows = r.json()["results"]["bindings"]
    except (requests.RequestException, ValueError, KeyError) as e:
        print(f"  wikidata unavailable ({e}); suggestion pack uses OSM only")
        return json.loads(cache.read_text(encoding="utf-8")) if cache.exists() else []
    out = []
    for row in rows:
        m = re.match(r"Point\(([-\d.]+) ([-\d.]+)\)", row["coord"]["value"])
        if not m:
            continue
        en = row.get("en", {}).get("value")
        ms = row.get("ms", {}).get("value")
        if not (en or ms):
            continue
        out.append({"name": en or ms, "name_ms": ms if ms and ms != en else None, "source": "wikidata",
                    "ref": row["item"]["value"].rsplit("/", 1)[-1], "lon": float(m.group(1)), "lat": float(m.group(2))})
    cache.parent.mkdir(parents=True, exist_ok=True)
    cache.write_text(json.dumps(out), encoding="utf-8")
    return out


def suggestion_pack(cfg: Config, out: Path, refresh: bool = False) -> int:
    """Every named OSM element in the bbox + Wikidata labels nearby, as points. The Surveyor filters to ~60 m."""
    els = osmdata.fetch(cfg, refresh=refresh)
    items = []
    for el in els:
        tags = el.get("tags") or {}
        name = (tags.get("name") or "").strip()
        if not name:
            continue
        g = osmdata.geometry(el)
        if g is None or g.is_empty:
            continue
        pt = osmdata.anchor(g)
        code, rest = split_code(name)
        items.append({"name": name, "name_ms": tags.get("name:ms"), "name_en": tags.get("name:en"), "code": code,
                      "source": "osm", "ref": osmdata.ref(el), "lon": round(pt.x, 7), "lat": round(pt.y, 7),
                      "hint": category_for(tags, name, osmdata.is_building(el))})
    items += wikidata_labels(cfg, refresh=refresh)
    pack = {"format": "uthm-atlas-suggestions", "version": 1, "campus_id": cfg.campus["id"], "created_at": _now(),
            "attribution": "© OpenStreetMap contributors (ODbL); Wikidata (CC0)", "items": items}
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(pack, ensure_ascii=False), encoding="utf-8")
    return len(items)
