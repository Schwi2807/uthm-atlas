"""ingest (validation, merge, dedupe, draft replacement) and footprints (linking, entrance snapping)."""

import copy
import io
import json
import zipfile

import pytest

from atlas import footprints, ingest, seed
from conftest import capture, feature, fid

SID1 = "0f7e3a1c-5b2d-4e8f-9a10-000000000001"
SID2 = "0f7e3a1c-5b2d-4e8f-9a10-000000000002"
HEADER = "t_ms,lat,lon,alt_m,acc_m,speed_mps,bearing_deg,provider,sats_used,sats_visible,feature_id\n"


def manifest(sid, n_features):
    return {
        "format": "uthmsurvey", "schema_version": 1, "app_version": "0.1.0",
        "session": {"id": sid, "campus_id": "parit-raja", "title": "Walk", "started_at": "2026-10-21T08:00:00+08:00",
                    "ended_at": None, "exported_at": "2026-10-21T09:00:00+08:00"},
        "device": {"manufacturer": "Example", "model": "Phone", "android_sdk": 33, "gnss_hardware": None},
        "sensors": {"barometer": False, "compass": True, "camera": True, "raw_gnss": False, "gnss_status": True},
        "settings": {"capture_samples": 30},
        "counts": {"features": n_features, "track_points": 2, "fix_samples": 1, "photos": 0},
        "files": {"features": "features.geojson", "track": "track.geojson", "fixes": "fixes.csv", "raw_gnss": None,
                  "photos_dir": "photos/"},
    }


def make_survey(path, sid, features, photos=None, mutate=None):
    m = manifest(sid, len(features))
    track = {"type": "FeatureCollection", "features": [{"type": "Feature", "geometry": {"type": "LineString",
             "coordinates": [[103.085, 1.858], [103.0851, 1.8581]]}, "properties": {"segment": 0, "t": [1, 2], "acc": [3.0, 4.0]}}]}
    files = {
        "manifest.json": json.dumps(m),
        "features.geojson": json.dumps({"type": "FeatureCollection", "features": features}),
        "track.geojson": json.dumps(track),
        "fixes.csv": HEADER + "1,1.858,103.085,10,3.5,0.1,,gps,20,30,\n",
    }
    if mutate:
        mutate(files)
    with zipfile.ZipFile(path, "w") as z:
        for k, v in files.items():
            z.writestr(k, v)
        for k, v in (photos or {}).items():
            z.writestr(k, v)
    return path


def walked(n, kind, cat, name, lon, lat, best=3.0, **props):
    f = feature(n, kind, cat, name, lon, lat, **props)
    f["properties"]["capture"] = capture(best=best)
    f["properties"]["created_at"] = f["properties"]["updated_at"] = "2026-10-21T08:10:00+08:00"
    return f


def test_ingest_validates_and_merges(cfg, tmp_path):
    work = tmp_path / "work"
    b = walked(1, "building", "faculty", "Sample Faculty", 103.0850, 1.8580, code="G3")
    e = walked(2, "entrance", "entrance", "Main entrance", 103.08500, 1.85781, parent_id=fid(1), bearing_deg=10.0)
    p = walked(3, "poi", "cafe", "Cafe", 103.0852, 1.8582, photos=[{"file": "photos/a.jpg"}])
    s = make_survey(tmp_path / "a.uthmsurvey", SID1, [b, e, p], photos={"photos/a.jpg": b"jpegbytes"})
    res = ingest.ingest(cfg, [s], work)
    assert res.ok, res.summary()
    assert (res.added, res.merged, res.photos) == (3, 0, 1)
    feats = ingest.load_features(work)
    assert {f["properties"]["name_en"] for f in feats} == {"Sample Faculty", "Main entrance", "Cafe"}
    cafe = next(f for f in feats if f["properties"]["kind"] == "poi")
    assert cafe["properties"]["photos"][0]["file"] == f"photos/{SID1[:8]}-a.jpg"
    assert (work / "photos" / f"{SID1[:8]}-a.jpg").read_bytes() == b"jpegbytes"
    assert (work / "tracks" / f"{SID1}.geojson").exists() and (work / "fixes" / f"{SID1}.csv").exists()
    # same file again: skipped, nothing duplicated
    res2 = ingest.ingest(cfg, [s], work)
    assert res2.skipped and not res2.sessions
    assert len(ingest.load_features(work)) == 3


def test_dedupe_keeps_best_capture_and_logs_conflict(cfg, tmp_path):
    work = tmp_path / "work"
    first = walked(1, "poi", "cafe", "Kafe A", 103.08500, 1.85800, best=6.0, name_ms="Kafe A")
    second = walked(9, "poi", "cafe", "Cafe A", 103.08502, 1.85801, best=2.5, aliases=["Arked"])  # ~2.5 m away
    ingest.ingest(cfg, [make_survey(tmp_path / "1.uthmsurvey", SID1, [first])], work)
    res = ingest.ingest(cfg, [make_survey(tmp_path / "2.uthmsurvey", SID2, [second])], work)
    assert res.merged == 1 and res.conflicts == 1
    feats = ingest.load_features(work)
    assert len(feats) == 1
    kept = feats[0]
    assert kept["id"] == fid(9)                                  # better capture wins the position
    assert kept["properties"]["capture"]["best_acc_m"] == 2.5
    assert "Arked" in kept["properties"]["aliases"]
    assert kept["properties"]["name_ms"] == "Kafe A"             # filled from the other capture
    conflicts = json.loads((work / "conflicts.json").read_text())
    assert conflicts[-1]["type"] == "duplicate" and any("name_en" in d for d in conflicts[-1]["disagreements"])


def test_different_categories_are_not_merged(cfg, tmp_path):
    work = tmp_path / "work"
    a = walked(1, "poi", "cafe", "Kiosk", 103.08500, 1.85800)
    b = walked(2, "poi", "atm", "ATM", 103.08501, 1.85800)
    res = ingest.ingest(cfg, [make_survey(tmp_path / "1.uthmsurvey", SID1, [a, b])], work)
    assert res.added == 2 and res.merged == 0


def test_walked_feature_replaces_osm_draft(cfg, tmp_path):
    work = tmp_path / "work"
    draft = feature(5, "poi", "cafe", "Old Cafe Name", 103.08600, 1.85850, source="osm", verified=False, last_verified=None,
                    osm_ref="osm:node/42", aliases=["Kafe Lama"])
    draft["properties"].pop("capture")
    ingest.save_features(work, [draft])
    w = walked(6, "poi", "cafe", "New Cafe", 103.08601, 1.85851)
    res = ingest.ingest(cfg, [make_survey(tmp_path / "w.uthmsurvey", SID1, [w])], work)
    assert res.replaced_drafts == 1
    feats = ingest.load_features(work)
    assert len(feats) == 1
    p = feats[0]["properties"]
    assert p["source"] == "survey" and p["osm_ref"] == "osm:node/42"
    assert "Old Cafe Name" in p["aliases"] and "Kafe Lama" in p["aliases"]
    # re-seeding must not bring the draft back
    merged, added, skipped = seed.merge_drafts(feats, [draft])
    assert added == 0 and skipped == 1


@pytest.mark.parametrize("mutate, message", [
    (lambda f: f.update({"manifest.json": json.dumps({**json.loads(f["manifest.json"]), "schema_version": 9})}), "schema_version"),
    (lambda f: f.pop("fixes.csv"), "missing fixes.csv"),
    (lambda f: f.update({"fixes.csv": "time,lat\n"}), "header"),
    (lambda f: f.update({"features.geojson": json.dumps({"type": "FeatureCollection", "features": [{"type": "Feature"}]})}), "feature 0"),
])
def test_bad_exports_are_rejected(cfg, tmp_path, mutate, message):
    good = walked(1, "poi", "cafe", "Cafe", 103.085, 1.858)
    s = make_survey(tmp_path / "bad.uthmsurvey", SID1, [good], mutate=mutate)
    res = ingest.ingest(cfg, [s], tmp_path / "work")
    assert not res.ok and message in res.errors[0]


def test_zip_slip_is_rejected(cfg, tmp_path):
    s = make_survey(tmp_path / "evil.uthmsurvey", SID1, [walked(1, "poi", "cafe", "Cafe", 103.085, 1.858)],
                    photos={"../../evil.txt": b"x"})
    res = ingest.ingest(cfg, [s], tmp_path / "work")
    assert not res.ok and "unsafe path" in res.errors[0]


def _way(i, lon, lat, half=0.0002, name=None, levels=None):
    ring = [(lon - half, lat - half), (lon + half, lat - half), (lon + half, lat + half), (lon - half, lat + half), (lon - half, lat - half)]
    tags = {"building": "university"}
    if name:
        tags["name"] = name
    if levels:
        tags["building:levels"] = str(levels)
    return {"type": "way", "id": i, "tags": tags, "geometry": [{"lon": x, "lat": y} for x, y in ring]}


def test_footprints_link_and_snap(cfg, tmp_path):
    work = tmp_path / "work"
    b = walked(1, "building", "faculty", "Walked Faculty", 103.08500, 1.85800)             # inside way 100
    lonely = walked(2, "building", "hall", "Far Hall", 103.09500, 1.86500)                # nowhere near a footprint
    e = walked(3, "entrance", "entrance", "Door", 103.08500, 1.85775, parent_id=fid(1), bearing_deg=0.0)  # ~2.8 m outside south wall
    far_e = walked(4, "entrance", "entrance", "Gate", 103.08500, 1.85700, parent_id=fid(1), bearing_deg=0.0)  # ~100 m away
    draft = feature(7, "building", "library", "Draft Library", 103.0870, 1.8590, source="osm", verified=False,
                    last_verified=None, osm_ref="osm:way/200")
    draft["properties"].pop("capture")
    ingest.save_features(work, [b, lonely, e, far_e, draft])
    els = [_way(100, 103.0850, 1.8580, levels=3), _way(200, 103.0870, 1.8590, name="Draft Library")]
    res = footprints.run(cfg, work, elements=els, overrides_dir=tmp_path / "none")
    assert res.linked == 2
    assert res.needs_tracing == ["Far Hall"]
    assert res.snapped == 1 and res.not_snapped and res.not_snapped[0].startswith("Gate")
    feats = {f["id"]: f for f in ingest.load_features(work)}
    door = feats[fid(3)]
    assert door["properties"]["_snapped"] is True
    assert door["geometry"]["coordinates"][1] == pytest.approx(1.8578, abs=2e-6)       # moved onto the south wall
    assert door["properties"]["_orig"] == [103.085, 1.85775]
    fps = json.loads((work / "footprints.geojson").read_text())["features"]
    assert {f["properties"]["ref"]: f["properties"]["linked"] for f in fps} == {"osm:way/100": fid(1), "osm:way/200": fid(7)}
    # idempotent: running again snaps from the original capture, not from the snapped point
    footprints.run(cfg, work, elements=els, overrides_dir=tmp_path / "none")
    again = {f["id"]: f for f in ingest.load_features(work)}[fid(3)]
    assert again["geometry"]["coordinates"] == door["geometry"]["coordinates"]


def test_walked_building_takes_over_draft_footprint(cfg, tmp_path):
    work = tmp_path / "work"
    draft = feature(7, "building", "library", "Draft Library", 103.0870, 1.8590, source="osm", verified=False,
                    last_verified=None, osm_ref="osm:way/200", aliases=["PTTA"])
    draft["properties"].pop("capture")
    poi = walked(8, "poi", "cafe", "Library cafe", 103.08701, 1.85901, parent_id=fid(7))
    walked_b = walked(9, "building", "library", "Tunku Tun Aminah Library", 103.08705, 1.85905)
    ingest.save_features(work, [draft, poi, walked_b])
    res = footprints.run(cfg, work, elements=[_way(200, 103.0870, 1.8590)], overrides_dir=tmp_path / "none")
    assert res.drafts_replaced == 1
    feats = {f["id"]: f for f in ingest.load_features(work)}
    assert fid(7) not in feats
    assert "PTTA" in feats[fid(9)]["properties"]["aliases"]
    assert feats[fid(8)]["properties"]["parent_id"] == fid(9)      # re-parented to the walked building


def test_override_polygon_replaces_osm_outline(cfg, tmp_path):
    work = tmp_path / "work"
    b = walked(1, "building", "hall", "Traced Hall", 103.0850, 1.8580, code="H1")
    ingest.save_features(work, [b])
    ov = tmp_path / "ov"
    ov.mkdir()
    ring = [[103.0849, 1.8579], [103.0851, 1.8579], [103.0851, 1.8581], [103.0849, 1.8581], [103.0849, 1.8579]]
    (ov / "hall.geojson").write_text(json.dumps({"type": "FeatureCollection", "features": [
        {"type": "Feature", "geometry": {"type": "Polygon", "coordinates": [ring]}, "properties": {"link": "H1", "levels": 2}}]}))
    res = footprints.run(cfg, work, elements=[_way(100, 103.0850, 1.8580)], overrides_dir=ov)
    assert res.overrides == 1 and res.footprints == 1 and res.linked == 1
    fp = json.loads((work / "footprints.geojson").read_text())["features"][0]["properties"]
    assert fp["src"] == "override" and fp["linked"] == fid(1)


def test_door_arrows_are_squared_to_the_wall(cfg, tmp_path):
    work = tmp_path / "work"
    b = walked(1, "building", "faculty", "Faculty", 103.08500, 1.85800)
    rough = walked(2, "entrance", "entrance", "South door", 103.08500, 1.85775, parent_id=fid(1), bearing_deg=20.0)
    east = walked(3, "entrance", "entrance", "East door", 103.08525, 1.85800, parent_id=fid(1), bearing_deg=315.0)
    odd = walked(4, "entrance", "entrance", "Odd door", 103.08500, 1.85776, parent_id=fid(1), bearing_deg=120.0)
    ingest.save_features(work, [b, rough, east, odd])
    els = [_way(100, 103.0850, 1.8580)]
    res = footprints.run(cfg, work, elements=els, overrides_dir=tmp_path / "none")
    feats = {f["id"]: f["properties"] for f in ingest.load_features(work)}
    assert feats[fid(2)]["bearing_deg"] == pytest.approx(0.0, abs=0.5)     # south wall -> facing north into it
    assert feats[fid(2)]["_bearing_orig"] == 20.0
    assert feats[fid(3)]["bearing_deg"] == pytest.approx(270.0, abs=0.5)   # east wall -> facing west
    assert feats[fid(4)]["bearing_deg"] == 120.0                            # too far off: left as recorded
    assert res.squared == 2
    footprints.run(cfg, work, elements=els, overrides_dir=tmp_path / "none")  # idempotent
    assert {f["id"]: f["properties"] for f in ingest.load_features(work)}[fid(2)]["_bearing_orig"] == 20.0
