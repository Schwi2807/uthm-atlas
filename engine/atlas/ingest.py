"""atlas ingest: validate .uthmsurvey exports and merge them into engine/work/.

work/
  features.geojson   merged feature set (walked + OSM drafts); the build reads this
  sessions.json      which exports were ingested (by session id and file hash)
  conflicts.json     dedupe decisions worth a human look
  tracks/<sid>.geojson, fixes/<sid>.csv   breadcrumb + raw fix samples per session (coverage, footpaths)
  photos/            original photos (git-ignored; only processed WebP is published)

Dedupe rule: an incoming walked feature within the kind's radius (atlas.toml survey.dedupe_radius_m) of an existing
feature of the same kind is the same thing. Walked beats OSM draft. Between two walked captures, the better capture
(lower best accuracy, then lower std-dev) keeps its position; names, aliases and photos are merged and every
disagreement is logged in conflicts.json.
"""

from __future__ import annotations

import csv
import hashlib
import io
import json
import zipfile
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath

from . import SCHEMA_VERSION, geo, schema
from .config import Config

FIXES_HEADER = ["t_ms", "lat", "lon", "alt_m", "acc_m", "speed_mps", "bearing_deg", "provider", "sats_used",
                "sats_visible", "feature_id"]


class IngestError(ValueError):
    pass


def load_features(work: Path) -> list[dict]:
    p = work / "features.geojson"
    if not p.exists():
        return []
    return json.loads(p.read_text(encoding="utf-8")).get("features", [])


def save_features(work: Path, features: list[dict]) -> None:
    work.mkdir(parents=True, exist_ok=True)
    features = sorted(features, key=lambda f: (f["properties"]["kind"], f["properties"]["name_en"].lower(), f["id"]))
    doc = {"type": "FeatureCollection", "features": features}
    # one feature per line: readable diffs in git
    lines = ",\n".join(json.dumps(f, ensure_ascii=False, sort_keys=True) for f in features)
    (work / "features.geojson").write_text('{"type": "FeatureCollection", "features": [\n' + lines + "\n]}\n",
                                           encoding="utf-8")
    assert json.loads((work / "features.geojson").read_text(encoding="utf-8")) == json.loads(json.dumps(doc))


def _read_json(path: Path, default):
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else default


def quality(f: dict) -> tuple[float, float]:
    c = f["properties"].get("capture") or {}
    return (c.get("best_acc_m", 1e9), c.get("std_m", 1e9))


@dataclass
class Survey:
    path: Path
    sha256: str
    manifest: dict
    features: list[dict]
    track: dict
    fixes: list[dict]
    photos: dict[str, bytes]


@dataclass
class IngestResult:
    sessions: list[str] = field(default_factory=list)
    skipped: list[str] = field(default_factory=list)
    errors: list[str] = field(default_factory=list)
    added: int = 0
    merged: int = 0
    replaced_drafts: int = 0
    updated: int = 0
    conflicts: int = 0
    photos: int = 0

    @property
    def ok(self) -> bool:
        return not self.errors

    def summary(self) -> str:
        out = [f"ingest: {len(self.sessions)} session(s) ingested, {len(self.skipped)} skipped"]
        out += [f"  skipped {s}" for s in self.skipped]
        out.append(f"  features: {self.added} new, {self.updated} updated, {self.merged} merged duplicates, "
                   f"{self.replaced_drafts} OSM drafts replaced; {self.photos} photos; {self.conflicts} conflict(s)")
        out += [f"  ERROR {e}" for e in self.errors]
        if self.ok and self.sessions:
            out.append("  next: atlas footprints && atlas build")
        return "\n".join(out)


def read_survey(path: Path) -> Survey:
    """Open and fully validate one export. Raises IngestError listing every problem."""
    raw = path.read_bytes()
    try:
        z = zipfile.ZipFile(io.BytesIO(raw))
    except zipfile.BadZipFile as e:
        raise IngestError(f"{path.name}: not a zip ({e})") from e
    names = set(z.namelist())
    for n in names:
        pp = PurePosixPath(n)
        if pp.is_absolute() or ".." in pp.parts:
            raise IngestError(f"{path.name}: unsafe path in zip: {n}")
    for need in ("manifest.json", "features.geojson", "track.geojson", "fixes.csv"):
        if need not in names:
            raise IngestError(f"{path.name}: missing {need}")
    try:
        manifest = json.loads(z.read("manifest.json"))
        fc = json.loads(z.read("features.geojson"))
        track = json.loads(z.read("track.geojson"))
    except json.JSONDecodeError as e:
        raise IngestError(f"{path.name}: bad JSON ({e})") from e

    problems = [f"manifest: {m}" for m in schema.errors("manifest", manifest)]
    if manifest.get("schema_version") != SCHEMA_VERSION:
        problems.append(f"manifest: schema_version {manifest.get('schema_version')} (engine supports {SCHEMA_VERSION})")
    if fc.get("type") != "FeatureCollection" or not isinstance(fc.get("features"), list):
        problems.append("features.geojson: not a FeatureCollection")
        features = []
    else:
        features = fc["features"]
        for i, f in enumerate(features):
            label = (f.get("properties") or {}).get("name_en") or f.get("id") or f"#{i}"
            problems += [f"feature {i} ({label}): {m}" for m in schema.errors("feature", f)]
            if (f.get("properties") or {}).get("source") != "survey":
                problems.append(f"feature {i} ({label}): exports may only contain source=survey features")
    problems += [f"track: {m}" for m in schema.errors("track", track)]

    text = z.read("fixes.csv").decode("utf-8-sig")
    rows = list(csv.DictReader(io.StringIO(text)))
    header = text.splitlines()[0].split(",") if text.strip() else []
    if header[: len(FIXES_HEADER) - 1] != FIXES_HEADER[:-1]:
        problems.append(f"fixes.csv: header {header} does not start with {FIXES_HEADER[:-1]}")

    photos: dict[str, bytes] = {}
    for f in features:
        for ph in (f.get("properties") or {}).get("photos") or []:
            if ph.get("file") not in names:
                problems.append(f"photo {ph.get('file')} referenced by {f.get('id')} is not in the zip")
            else:
                data = z.read(ph["file"])
                if ph.get("sha256") and hashlib.sha256(data).hexdigest() != ph["sha256"]:
                    problems.append(f"photo {ph['file']} sha256 mismatch")
                photos[ph["file"]] = data
    sid = (manifest.get("session") or {}).get("id")
    ids = [f.get("id") for f in features]
    if len(ids) != len(set(ids)):
        problems.append("duplicate feature ids inside the export")
    if problems:
        raise IngestError(f"{path.name}: {len(problems)} problem(s)\n    " + "\n    ".join(problems[:30]))
    return Survey(path, hashlib.sha256(raw).hexdigest(), manifest, features, track, rows, photos)


def _merge_into(winner: dict, loser: dict) -> list[str]:
    """Fold loser's extra info into winner. Returns disagreements."""
    wp, lp = winner["properties"], loser["properties"]
    notes = []
    for key in ("name_en", "name_ms", "code", "category"):
        if lp.get(key) and wp.get(key) and lp[key] != wp[key]:
            notes.append(f"{key}: kept {wp[key]!r}, other capture said {lp[key]!r}")
        elif lp.get(key) and not wp.get(key):
            wp[key] = lp[key]
    aliases = set(wp.get("aliases") or []) | set(lp.get("aliases") or [])
    for key in ("name_en", "name_ms"):
        if lp.get(key) and lp[key] != wp.get(key) and lp[key] != wp.get("name_en"):
            aliases.add(lp[key])
    aliases -= {wp.get("name_en"), wp.get("name_ms"), None}
    wp["aliases"] = sorted(aliases)
    files = {p["file"] for p in wp.get("photos") or []}
    wp["photos"] = list(wp.get("photos") or []) + [p for p in lp.get("photos") or [] if p["file"] not in files]
    for key in ("floors", "floor", "bearing_deg", "accessible", "notes", "parent_id", "rooms"):
        if wp.get(key) in (None, [], "") and lp.get(key) not in (None, [], ""):
            wp[key] = lp[key]
    dates = [d for d in (wp.get("last_verified"), lp.get("last_verified")) if d]
    wp["last_verified"] = max(dates) if dates else None
    if lp.get("osm_ref") and not wp.get("osm_ref"):
        wp["osm_ref"] = lp["osm_ref"]
    return notes


def merge(cfg: Config, existing: list[dict], incoming: list[dict], conflicts: list[dict], session: str,
          res: IngestResult) -> list[dict]:
    by_id = {f["id"]: f for f in existing}
    for f in incoming:
        p = f["properties"]
        if f["id"] in by_id:  # the same feature re-exported (edited / re-captured on the phone)
            old = by_id[f["id"]]
            if (p.get("updated_at") or "") >= (old["properties"].get("updated_at") or ""):
                if old["properties"].get("osm_ref") and not p.get("osm_ref"):
                    p["osm_ref"] = old["properties"]["osm_ref"]
                by_id[f["id"]] = f
                res.updated += 1
            continue
        radius = cfg.dedupe_radius(p["kind"])
        pt = f["geometry"]["coordinates"][:2]
        near = []
        for g in by_id.values():
            gp = g["properties"]
            if gp["kind"] != p["kind"]:
                continue
            if p["kind"] == "poi" and gp["category"] != p["category"]:
                continue
            if p["kind"] == "entrance" and p.get("parent_id") and gp.get("parent_id") and gp["parent_id"] != p["parent_id"]:
                continue
            d = geo.haversine_m(pt, g["geometry"]["coordinates"][:2])
            if d <= radius:
                near.append((d, g))
        if not near:
            by_id[f["id"]] = f
            res.added += 1
            continue
        d, g = min(near, key=lambda x: x[0])
        if g["properties"]["source"] == "osm":
            # walked replaces draft; keep the draft's extra names as aliases and its OSM link
            _merge_into(f, g)
            p["osm_ref"] = g["properties"].get("osm_ref")
            del by_id[g["id"]]
            by_id[f["id"]] = f
            res.replaced_drafts += 1
            conflicts.append({"type": "replaced_draft", "session": session, "kept": f["id"], "dropped": g["id"],
                              "distance_m": round(d, 1), "kept_name": p["name_en"], "dropped_name": g["properties"]["name_en"]})
            continue
        winner, loser = (f, g) if quality(f) < quality(g) else (g, f)
        notes = _merge_into(winner, loser)
        by_id.pop(loser["id"], None)
        by_id[winner["id"]] = winner
        res.merged += 1
        conflicts.append({"type": "duplicate", "session": session, "kept": winner["id"], "dropped": loser["id"],
                          "distance_m": round(d, 1), "kept_name": winner["properties"]["name_en"],
                          "dropped_name": loser["properties"]["name_en"],
                          "kept_best_acc_m": quality(winner)[0], "dropped_best_acc_m": quality(loser)[0],
                          "disagreements": notes})
        res.conflicts += 1 if notes else 0
    # entrances/POIs whose parent was merged away point at the survivor
    alive = set(by_id)
    redirect = {c["dropped"]: c["kept"] for c in conflicts}
    for g in by_id.values():
        par = g["properties"].get("parent_id")
        while par and par not in alive and par in redirect:
            par = redirect[par]
        if g["properties"].get("parent_id") != par:
            g["properties"]["parent_id"] = par
    return list(by_id.values())


def ingest(cfg: Config, files: list[Path], work: Path, replace: bool = False) -> IngestResult:
    res = IngestResult()
    work.mkdir(parents=True, exist_ok=True)
    sessions = _read_json(work / "sessions.json", {})
    conflicts = _read_json(work / "conflicts.json", [])
    features = load_features(work)
    for path in files:
        try:
            s = read_survey(path)
        except IngestError as e:
            res.errors.append(str(e))
            continue
        sid = s.manifest["session"]["id"]
        if sid in sessions and not replace:
            same = sessions[sid]["sha256"] == s.sha256
            res.skipped.append(f"{path.name} (session {sid[:8]} {'already ingested' if same else 'ingested before; use --replace'})")
            continue
        prefix = sid[:8]
        # photos: copy with a session prefix so two phones' "IMG_1.jpg" never collide
        (work / "photos").mkdir(exist_ok=True)
        for f in s.features:
            for ph in f["properties"].get("photos") or []:
                name = f"{prefix}-{PurePosixPath(ph['file']).name}"
                (work / "photos" / name).write_bytes(s.photos[ph["file"]])
                ph["file"] = f"photos/{name}"
                res.photos += 1
        (work / "tracks").mkdir(exist_ok=True)
        (work / "tracks" / f"{sid}.geojson").write_text(json.dumps(s.track), encoding="utf-8")
        (work / "fixes").mkdir(exist_ok=True)
        with open(work / "fixes" / f"{sid}.csv", "w", newline="", encoding="utf-8") as fh:
            w = csv.DictWriter(fh, fieldnames=FIXES_HEADER, extrasaction="ignore")
            w.writeheader()
            w.writerows(s.fixes)
        features = merge(cfg, features, s.features, conflicts, sid, res)
        m = s.manifest
        sessions[sid] = {"file": path.name, "sha256": s.sha256,
                         "ingested_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
                         "started_at": m["session"].get("started_at"), "app_version": m.get("app_version"),
                         "device": m["device"].get("model"), "features": len(s.features),
                         "fixes": len(s.fixes), "sensors": m.get("sensors")}
        res.sessions.append(sid)
    if res.sessions:
        schema.check_features([{**f, "properties": {k: v for k, v in f["properties"].items()
                                                    if not k.startswith("_") and k != "osm_ref"}} for f in features],
                              "merged features")
        save_features(work, features)
        (work / "sessions.json").write_text(json.dumps(sessions, indent=1) + "\n", encoding="utf-8")
        (work / "conflicts.json").write_text(json.dumps(conflicts, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
    return res
