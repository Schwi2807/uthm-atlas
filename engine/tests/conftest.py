"""Shared fixtures: a tiny fake campus (two buildings, entrances, POIs, footprints, a photo with GPS EXIF)."""

import json

import pytest
from PIL import Image

from atlas import config


def fid(n: int) -> str:
    return f"{n:02x}c0ffee-0000-4000-8000-{n:012x}"


def capture(std=1.5, best=3.0):
    return {"method": "averaged", "n": 30, "std_m": std, "mean_acc_m": 4.0, "best_acc_m": best, "sats_used": 20,
            "sats_visible": 28, "stop_reason": "count", "started_at": "2026-10-20T08:00:00+08:00",
            "ended_at": "2026-10-20T08:00:30+08:00", "recaptures": 0}


def feature(n, kind, cat, name, lon, lat, **props):
    p = {"campus_id": "parit-raja", "kind": kind, "category": cat, "name_en": name, "source": "survey",
         "verified": True, "last_verified": "2026-10-20", "capture": capture(), "aliases": []}
    p.update(props)
    return {"type": "Feature", "id": fid(n), "geometry": {"type": "Point", "coordinates": [lon, lat]}, "properties": p}


def square(lon, lat, half=0.0002):
    return [[[lon - half, lat - half], [lon + half, lat - half], [lon + half, lat + half], [lon - half, lat + half],
             [lon - half, lat - half]]]


@pytest.fixture
def cfg():
    return config.load()


@pytest.fixture
def sample_work(tmp_path):
    work = tmp_path / "work"
    (work / "photos").mkdir(parents=True)
    # photo with orientation + GPS + author EXIF that must not survive
    img = Image.new("RGB", (2400, 1600), (40, 120, 200))
    for x in range(0, 2400, 40):
        for y in range(0, 1600, 160):
            img.putpixel((x, y), (255, 255, 0))
    exif = Image.Exif()
    exif[0x013B] = "Example Author"      # Artist
    exif[0x8298] = "Example Copyright"   # Copyright
    exif[0x0112] = 6                     # Orientation: rotate 90
    exif[0x8825] = {1: "N", 2: (1.0, 51.0, 30.0), 3: "E", 4: (103.0, 5.0, 2.0)}  # GPS IFD
    img.save(work / "photos" / "a1.jpg", "JPEG", exif=exif, quality=95)

    b1 = feature(1, "building", "faculty", "Sample Faculty of Engineering", 103.0850, 1.8580,
                 name_ms="Fakulti Kejuruteraan Contoh", code="G3", aliases=["FKX"], floors=4,
                 rooms=[{"name": "Lecture Room 1", "code": "BK1", "floor": 1, "kind": "lecture_room"}],
                 photos=[{"file": "photos/a1.jpg"}])
    b2 = feature(2, "building", "library", "Sample Library", 103.0870, 1.8590, source="osm", verified=False,
                 last_verified=None, capture=None)
    b2["properties"].pop("capture")
    e1 = feature(3, "entrance", "entrance", "Main entrance", 103.0850, 1.85781, name_ms="Pintu utama",
                 parent_id=fid(1), bearing_deg=0.0, accessible=True)
    e2 = feature(4, "entrance", "entrance", "Side door", 103.08702, 1.8590, bearing_deg=270.0)
    poi = feature(5, "poi", "cafe", "Sample Cafe", 103.0860, 1.8585, name_ms="Kafe Contoh", parent_id=fid(1),
                  floor=0)
    note = feature(6, "path_note", "path_note", "Covered walkway", 103.0855, 1.8583,
                   notes="Shaded route when it rains")
    fc = {"type": "FeatureCollection", "features": [b1, b2, e1, e2, poi, note]}
    (work / "features.geojson").write_text(json.dumps(fc), encoding="utf-8")

    fps = {"type": "FeatureCollection", "features": [
        {"type": "Feature", "geometry": {"type": "Polygon", "coordinates": square(103.0850, 1.8580)},
         "properties": {"ref": "osm:way/1", "levels": 3, "linked": fid(1), "src": "osm"}},
        {"type": "Feature", "geometry": {"type": "Polygon", "coordinates": square(103.0870, 1.8590)},
         "properties": {"ref": "osm:way/2", "levels": None, "linked": fid(2), "src": "osm"}},
        {"type": "Feature", "geometry": {"type": "Polygon", "coordinates": square(103.0880, 1.8600, 0.0001)},
         "properties": {"ref": "osm:way/3", "levels": 2, "linked": None, "src": "osm"}},
    ]}
    (work / "footprints.geojson").write_text(json.dumps(fps), encoding="utf-8")

    cur = {"tour": [{"ref": "G3", "title_en": "Your faculty", "title_ms": "Fakulti anda", "text_en": "Start here."},
                    {"ref": fid(5)[:8], "title_en": "Coffee"}],
           "places": {"G3": {"add_aliases": ["Blok Kejuruteraan"]}},
           "photos": {"reviewed": []}}
    (tmp_path / "curation.json").write_text(json.dumps(cur), encoding="utf-8")
    return work, tmp_path / "curation.json", tmp_path / "site_data"
