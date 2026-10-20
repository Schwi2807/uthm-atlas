# UTHM Atlas: build plan

Solo-surveyed, offline-first campus map for UTHM Parit Raja. Three parts in one repo:
**Surveyor** (Android, Kotlin/Compose) → **Engine** (Python `atlas` CLI) → **Site** (vanilla-JS PWA on GitHub Pages).
Owner/author everywhere: **Schwi2807**. Status: kickoff, waiting for the go-ahead (2026-10-20).

## What the laptop has today

| Need | State | Plan |
|---|---|---|
| Git 2.55 + git-lfs | installed | repo-local identity `Schwi2807` + GitHub no-reply email |
| Python | 3.13 (brief asks 3.11+) | engine venv in `engine/.venv` |
| Node 24 | installed | only for the PWA checks (pwa skill `check.js`) and headless tests |
| adb | `C:\code_usermade\platform-tools` | install/launch the debug APK on the Redmi 10C |
| JDK 17, Android SDK, Gradle | **missing** | portable Temurin 17 + Android cmdline-tools/SDK in `C:\Dev\tools` (no UAC), Gradle wrapper |
| `pmtiles` CLI | missing | engine downloads the release binary into `C:\Dev\tools\pmtiles` on first use |
| `gh` CLI | missing | only needed if I create the GitHub repo myself (see decisions) |

## Repo layout

```
uthm-atlas/
  collector/   Android Surveyor (Gradle, io.github.schwi2807.atlas)
  engine/      atlas CLI (pyproject), tests/, overrides/, atlas.toml
  schema/      feature.schema.json, survey-manifest.schema.json, BUNDLE_FORMAT.md
  site/        PWA root: index.html, js/, css/, sw.js, manifest, vendor/, data/
  surveys/     raw .uthmsurvey exports (photos gitignored, processed WebP committed)
  .github/workflows/pages.yml
  DECISIONS.md  PROGRESS.md  README.md  MANUAL_TESTING.md
```

## Key technical choices (mine, change any in the form)

- **One schema** (`schema/*.json`, JSON Schema 2020-12) is used by the engine (jsonschema), the Surveyor (generated Kotlin
  constants + a schema copy in assets, checked by a unit test) and the site's CI decode check.
- **Bundle**: `UATL` + uint16 schema version + uint32 build time + gzip(MessagePack). Decoded in the browser with
  `DecompressionStream('gzip')` and a ~150-line MessagePack decoder. Spec in `schema/BUNDLE_FORMAT.md`.
- **Basemap**: `pmtiles extract` of the campus bbox (+ margin) from a Protomaps daily build (range reads, no tile
  scraping), Protomaps style flavour re-coloured to the app palette, glyphs + sprites vendored for offline.
  Same `campus.pmtiles` is bundled into the Surveyor's assets.
- **Footprints**: one cached Overpass query for the bbox; hand-traced polygons in `engine/overrides/*.geojson` win.
- **3D**: `fill-extrusion` from floor count × 3.6 m; MapLibre light position = real sun azimuth/altitude for
  Batu Pahat (SunCalc-style formula, no library), refreshed every few minutes.
- **Walk there**: `https://www.google.com/maps/dir/?api=1&destination=<lat,lng>&travelmode=walking` to the best
  entrance (a link only, nothing from Google is fetched).
- **Privacy**: visitor GPS stays in memory; no analytics; photos re-encoded to WebP (EXIF dropped by re-encode) and
  held back until reviewed (see decisions).
- **Graceful sensors**: every sensor checked via `SensorManager`/`PackageManager`; no barometer → manual floor stepper;
  no raw GNSS → toggle disabled with a reason.

## Milestones (each ends with tests + a commit; tags `m1-done` … )

1. Skeleton + schema + BUNDLE_FORMAT.md; schema validates a hand-written sample.
2. Engine `build` from sample data + round-trip test; pytest green.
3. PWA MVP: bundle + PMTiles, search, place card, offline after first load (browser pane + offline test).
4. Pages workflow (decode + schema check gate), range-request check with automatic z/x/y fallback.
5. Surveyor MVP: GNSS HUD + skyplot, averaged capture ring, feature form, export `.uthmsurvey`.
6. Surveyor: offline map, breadcrumb (foreground service), coverage heat layer, compass/barometer/camera, suggestion pack.
7. Engine: ingest/dedupe/footprint link/entrance snap/photo pipeline/build report.
8. PWA polish: 3D + sun, First Week tour, BM toggle, deep links, update toast, install, Lighthouse.
9. Nice-to-haves in brief order.

## What only you can do

- Walk the campus (≥10 places) with the Surveyor, then export → the "done" test.
- Open the live URL offline on a second phone.
- Any GitHub login prompt.

## Risks

- Redmi 10C (Helio G85 era) GNSS: no dual-frequency, raw measurements may be partial; HUD shows what exists.
- Protomaps build URLs change daily; engine finds the newest build and caches the extract.
- OSM footprint coverage of UTHM may be partial → manual tracing list in the build report.
- Brief says MapLibre/pmtiles.js "from CDN"; the pwa skill rule is vendor everything (first load then works fully
  offline, no CDN dependency). Default: vendor.
