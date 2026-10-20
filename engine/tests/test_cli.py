import json
import threading
import urllib.request
from functools import partial

from atlas import build, check, preview


def test_check_site_passes_after_build(cfg, sample_work):
    work, cur, out = sample_work
    data, rep = build.build(cfg, work, out, cur, ts=1760950000, tiles="pmtiles")
    build.write(data, rep, out, "pmtiles")
    (out / "campus.pmtiles").write_bytes(b"PMTiles")
    assert check.check_site(out) == []


def test_check_site_catches_problems(cfg, sample_work):
    work, cur, out = sample_work
    data, rep = build.build(cfg, work, out, cur, ts=1760950000)
    build.write(data, rep, out, "pmtiles")
    # no pmtiles, and a tampered version.json
    v = json.loads((out / "version.json").read_text())
    v["ts"] = 1
    (out / "version.json").write_text(json.dumps(v))
    problems = check.check_site(out)
    assert any("pmtiles" in p for p in problems)
    assert any("version.json" in p for p in problems)
    (out / "campus.bundle").write_bytes(b"JUNKJUNKJUNK")
    assert any("magic" in p for p in check.check_site(out))


def test_preview_serves_ranges(tmp_path):
    (tmp_path / "f.bin").write_bytes(bytes(range(256)))
    handler = partial(preview.RangeHandler, directory=str(tmp_path))
    srv = preview.Server(("127.0.0.1", 0), handler)
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    try:
        base = f"http://127.0.0.1:{srv.server_address[1]}/f.bin"
        req = urllib.request.Request(base, headers={"Range": "bytes=10-19"})
        with urllib.request.urlopen(req) as r:
            assert r.status == 206
            assert r.headers["Content-Range"] == "bytes 10-19/256"
            assert r.read() == bytes(range(10, 20))
        req = urllib.request.Request(base, headers={"Range": "bytes=-4"})
        with urllib.request.urlopen(req) as r:
            assert r.read() == bytes([252, 253, 254, 255])
        with urllib.request.urlopen(base) as r:
            assert r.status == 200 and len(r.read()) == 256
    finally:
        srv.shutdown()
