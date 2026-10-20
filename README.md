# UTHM Atlas

An **unofficial**, solo-surveyed, offline campus map of UTHM Parit Raja, made by **Schwi2807** for newcomers.
Not affiliated with or endorsed by the university; no university logo or branding is used.

Three parts in one repo:

| Part | Folder | What it does |
|---|---|---|
| **Surveyor** | `collector/` | Android field tool (Kotlin + Compose): live GNSS HUD + skyplot, averaged position capture, compass / barometer / camera, offline map with breadcrumb + coverage layers, exports one `.uthmsurvey` file per session. |
| **Engine** | `engine/` | `atlas` command-line tool (Python): validates and merges exports, links OSM building outlines, snaps entrances, extracts the basemap, strips photo metadata and compiles `site/data/campus.bundle`. |
| **Site** | `site/` | The public map (vanilla JS PWA on GitHub Pages): 3D campus lit by the real sun, instant EN/BM search, place cards with "Walk there", First Week tour, works offline after one visit, installable. |

The single source of truth for data shapes is [`schema/`](schema/) (JSON Schemas + [BUNDLE_FORMAT.md](schema/BUNDLE_FORMAT.md)).

```
phone walk -> .uthmsurvey -> atlas ingest / footprints / build -> site/data/ -> git push -> Actions -> GitHub Pages
```

## Release flow (what you do after a walk)

1. **Export from the phone.** Surveyor -> Sessions -> the session -> *Export*. Save the `.uthmsurvey` file and copy
   it to the laptop into `surveys/` (USB, Drive, anything).
2. **Ingest:** `atlas ingest` (all files in `surveys/`) or `atlas ingest surveys/<file>.uthmsurvey`.
   Then `atlas footprints` to link buildings to their outlines and snap entrances.
3. **Build:** `atlas build`. Read the report: weak captures, buildings that need tracing, photos to review for
   faces or number plates (mark them in `engine/curation.json` -> `photos.reviewed` or `photos.exclude`).
4. **Preview:** `atlas preview`, open <http://127.0.0.1:8000/>. Check the new places, try search, try offline
   (DevTools -> Network -> Offline, reload).
5. **Publish:** `git add -A && git commit -m "Survey <date>" && git push`. The *Pages* workflow runs the tests and
   the deploy gate (`atlas check`), deploys, then confirms the live site answers PMTiles Range requests (and
   switches to static z/x/y tiles by itself if it ever doesn't). Live at `https://schwi2807.github.io/uthm-atlas/`
   about two minutes later. Phones that already have the app get a "New map data is ready" toast.

## Setup on a new laptop

```bash
cd engine
python -m venv .venv
.venv/Scripts/python -m pip install -e ".[test]"
.venv/Scripts/python -m pytest -q
```

Add `engine/.venv/Scripts` to PATH (or call `engine/.venv/Scripts/atlas`). The first `atlas basemap` downloads
the `pmtiles` CLI into `C:\Dev\tools\pmtiles\` (checked against GitHub's published sha256).

The Surveyor builds with Android Studio (open `collector/`) or `collector/gradlew assembleDebug`; see
[collector/README.md](collector/README.md).

## Engine commands

| Command | Does |
|---|---|
| `atlas ingest [files]` | Validate `.uthmsurvey` exports against the schema and merge them into `engine/work/` (dedupe by radius: the better capture wins, conflicts go to `work/conflicts.json`). |
| `atlas seed` | Add OSM draft places (named buildings and amenities, badged "Not walked yet"). Walked features replace them. |
| `atlas footprints` | Fetch OSM building outlines (cached), add hand-traced ones from `engine/overrides/*.geojson`, link building anchors, snap entrances to the nearest wall. |
| `atlas suggest` | Write the Surveyor's offline name-suggestion pack (OSM names + Wikidata labels). |
| `atlas basemap [--xyz]` | Extract `site/data/campus.pmtiles` for the campus from the newest Protomaps build; ship the needed glyphs and sprites. |
| `atlas build` | Compile `site/data/campus.bundle`, process photos, print the build report. Fails on schema or size-budget violations. |
| `atlas check` | Deploy gate: decode the bundle, validate it, cross-check `version.json`, photos and tiles. |
| `atlas preview` | Serve `site/` locally with HTTP Range support. |
| `atlas sw` | Refresh the service worker's precache list after editing site code. |
| `atlas xyz` / `atlas retile` | Static z/x/y tile fallback for hosts without Range support (the Pages workflow uses these automatically). |

Settings (campus bbox, dedupe radii, budgets) live in [`engine/atlas.toml`](engine/atlas.toml); hand edits to
names, aliases, floors and the First Week tour live in [`engine/curation.json`](engine/curation.json).

## Privacy

- The site never stores or sends the visitor's location; "you are here" lives in memory only. No analytics.
- Photos are re-encoded to WebP from pixels only: no EXIF, GPS, camera or author metadata survives (tested).
- Raw survey files, original photos, walked tracks and fix samples are git-ignored by default, so a public repo
  only carries the processed map.
- The only name in this project is the handle Schwi2807. `python tools/identity_check.py` checks commits and
  files (set `ATLAS_FORBIDDEN_NAMES` locally to add names it must never find).

## Credits and licences

- Code: MIT, © 2026 Schwi2807 (see [LICENSE](LICENSE)).
- Map data © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright), ODbL. The campus bundle and
  basemap contain OSM-derived data and are shared under the ODbL.
- Basemap tiles and style: [Protomaps](https://protomaps.com) (BSD-3 / ODbL data).
- MapLibre GL JS (BSD-3), pmtiles (BSD-3), Overpass font (OFL), Noto Sans glyphs (OFL).
