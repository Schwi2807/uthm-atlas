import io
import json

import pytest
from PIL import Image

from atlas import SCHEMA_VERSION, build, bundle, geo, schema, search


def test_round_trip_and_header():
    payload = {"v": 1, "x": [1, -2, 3.5, None, True, "Café", {"nested": [0] * 40}], "big": 2**40, "neg": -2**33}
    data = bundle.encode(payload, ts=1760000000)
    assert data[:4] == b"UATL"
    assert data[4:6] == SCHEMA_VERSION.to_bytes(2, "big")
    assert data[6:10] == (1760000000).to_bytes(4, "big")
    assert data[10:12] == b"\x1f\x8b"  # gzip magic
    b = bundle.decode(data)
    assert (b.version, b.ts, b.payload) == (SCHEMA_VERSION, 1760000000, payload)


def test_encoding_is_deterministic():
    p = {"a": list(range(100))}
    assert bundle.encode(p, 5) == bundle.encode(p, 5)


@pytest.mark.parametrize("data, msg", [
    (b"XXXX\x00\x01\x00\x00\x00\x00", "magic"),
    (b"UATL\x00\x63\x00\x00\x00\x00" + b"\x1f\x8b", "version"),
    (b"UATL", "short"),
    (b"UATL\x00\x01\x00\x00\x00\x00notgzip", "gzip"),
])
def test_decode_rejects_bad_input(data, msg):
    with pytest.raises(bundle.BundleError, match=msg):
        bundle.decode(data)


def test_delta_ring_round_trip():
    ring = [(103.085, 1.858), (103.0852, 1.858), (103.0852, 1.8582), (103.085, 1.8582), (103.085, 1.858)]
    flat = geo.delta_ring(ring)
    assert flat[:2] == [103085000, 1858000] and flat[2:4] == [200, 0]
    back = geo.undelta_ring(flat)
    assert back == [(round(x, 6), round(y, 6)) for x, y in ring]


def test_search_terms():
    terms = dict(search.place_terms({"code": "G3", "en": "Faculty of Électrical Eng.", "ms": None, "al": ["FKEE", "old hall"],
                                     "rooms": [["Makmal DSP", "D2-1", 2, "lab"]]}))
    assert terms["g3"] == 3 and terms["blok g3"] == 3 and terms["blok g"] == 1
    assert terms["faculty of electrical eng"] == 2
    assert terms["fkee"] == 3 and terms["old hall"] == 1
    assert terms["makmal dsp"] == 1 and terms["d2 1"] == 1


def test_build_sample(cfg, sample_work):
    work, cur, out = sample_work
    data, rep = build.build(cfg, work, out, cur, ts=1760950000)
    b = bundle.decode(data)
    p = b.payload
    assert schema.errors("bundle", p) == []
    assert b.ts == p["build"]["ts"] == 1760950000
    names = [x["en"] for x in p["places"]]
    # buildings first (alphabetical), then POIs, then path notes; entrances are separate
    assert names == ["Sample Faculty of Engineering", "Sample Library", "Sample Cafe", "Covered walkway"]
    fac, lib, cafe = p["places"][0], p["places"][1], p["places"][2]
    assert fac["ok"] is True and lib["ok"] is False and lib["src"] == "osm"
    assert fac["h"] == pytest.approx(4 * 3.6)          # survey floors beat OSM levels
    assert lib["h"] == pytest.approx(2 * 3.6)          # default storeys
    assert cafe["par"] == 0
    assert "Blok Kejuruteraan" in fac["al"]            # curation add_aliases
    # entrances: explicit parent, and nearest-building fallback
    assert [p["entrances"][i]["en"] for i in fac["ents"]] == ["Main entrance"]
    assert p["entrances"][fac["ents"][0]]["main"] is True
    assert [p["entrances"][i]["en"] for i in lib["ents"]] == ["Side door"]
    # unlinked context footprint kept
    assert sum(1 for f in p["footprints"] if f["pl"] is None) == 1
    # tour resolved by code and short id
    assert [t["pl"] for t in p["tour"]] == [0, 2]
    # search: alias, code variants, BM name
    terms = {(t[0], t[1]) for t in p["search"]}
    assert ("fkx", 0) in terms and ("blok g", 0) in terms and ("kafe contoh", 2) in terms
    assert "1 photo" not in rep.text()
    assert "Photos to review" in rep.text()


def test_photo_is_resized_and_clean(cfg, sample_work):
    work, cur, out = sample_work
    data, rep = build.build(cfg, work, out, cur, ts=1760950000)
    p = bundle.decode(data).payload
    ph = p["photos"][0]
    raw = (out / ph["f"]).read_bytes()
    assert len(raw) <= 150 * 1024
    with Image.open(io.BytesIO(raw)) as im:
        assert im.format == "WEBP"
        assert max(im.size) <= 1280
        assert im.size[1] > im.size[0]  # orientation 6 was baked in (landscape source -> portrait)
        assert len(im.getexif()) == 0
        assert not im.info.get("exif") and not im.info.get("icc_profile") and not im.info.get("xmp")
    for needle in (b"Example Author", b"Example Copyright", b"GPS"):
        assert needle not in raw


def test_photo_exclusion(cfg, sample_work):
    work, cur, out = sample_work
    c = json.loads(cur.read_text())
    c["photos"]["exclude"] = ["a1.jpg"]
    cur.write_text(json.dumps(c))
    data, _ = build.build(cfg, work, out, cur, ts=1)
    assert bundle.decode(data).payload["photos"] == []


def test_build_fails_on_schema_violation(cfg, sample_work):
    work, cur, out = sample_work
    fc = json.loads((work / "features.geojson").read_text())
    fc["features"][0]["properties"]["category"] = "spaceport"
    (work / "features.geojson").write_text(json.dumps(fc))
    with pytest.raises(build.BuildError, match="spaceport|category"):
        build.build(cfg, work, out, cur)


def test_build_fails_on_budget(cfg, sample_work):
    work, cur, out = sample_work
    cfg.raw["budget"]["bundle_kb"] = 0
    with pytest.raises(build.BuildError, match="budget"):
        build.build(cfg, work, out, cur)


def test_build_fails_on_bad_tour_ref(cfg, sample_work):
    work, cur, out = sample_work
    c = json.loads(cur.read_text())
    c["tour"].append({"ref": "NOPE", "title_en": "Missing"})
    cur.write_text(json.dumps(c))
    with pytest.raises(build.BuildError, match="NOPE"):
        build.build(cfg, work, out, cur)


def test_write_outputs(cfg, sample_work):
    work, cur, out = sample_work
    data, rep = build.build(cfg, work, out, cur, ts=1760950000)
    (out / "photos" / "stale.webp").write_bytes(b"x")
    build.write(data, rep, out, "pmtiles")
    assert (out / "campus.bundle").read_bytes() == data
    assert json.loads((out / "version.json").read_text()) == {"v": 1, "ts": 1760950000, "bytes": len(data), "tiles": "pmtiles"}
    assert not (out / "photos" / "stale.webp").exists()
