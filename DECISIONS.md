# Decisions

Judgement calls made during the unattended build. Newest last. Kickoff answers (2026-10-20) are in PLAN.md.

- **D1 Kickoff answers.** PWA look = clean transit guide (wayfinding-signage style); Surveyor HUD accent = amber;
  Android Studio installed via winget; debug APK may be adb-installed on the Redmi 10C; before the walk the site
  shows OSM draft places badged "Not walked yet"; owner creates the empty GitHub repo and I push after M4;
  MapLibre / pmtiles.js are vendored in `site/vendor/` (the brief said CDN; the PWA skill's rule wins so the first
  visit already caches everything); photos are published without a hold, but the build report lists every photo
  not yet marked reviewed, and `curation.json → photos.exclude` removes any with faces or plates. Scope M1–M9.
- **D2 Schema split.** One folder, five JSON Schemas (feature, survey manifest, track, bundle payload, curation),
  all Draft 2020-12 and carrying `x-schema-version`. The engine refuses to run if they disagree with its
  `SCHEMA_VERSION`.
- **D3 Coordinates in the bundle are integer micro-degrees** with delta-coded polygon rings (see
  schema/BUNDLE_FORMAT.md). Header integers are big-endian.
- **D4 campus_id** for Parit Raja is `parit-raja`.
- **D5 Repo-local git identity** = Schwi2807 / GitHub no-reply address (36848830+Schwi2807@users.noreply.github.com).
- **D6 OSM drafts are made readable.** Bare OSM names are mostly Malay; `atlas/glossary.py` adds an English name
  only when every word is in its small glossary (proper names are never guessed). Faculty acronyms (FKEE, FSKTM …)
  get their public full names. Same-name outlines collapse into one place; generic POIs get "near <building>".
  Building codes are split from building names only ("G3 Kompleks Dewan Kuliah" -> code G3).
- **D7 Basemap = Protomaps daily build, extracted** with the pmtiles CLI (C:\Dev\tools\pmtiles\1.31.2, checked
  against GitHub's published sha256 digest). Glyph ranges are computed from the label fields of z>=12 tiles plus all
  place names, so only ~4 ranges per font ship (map assets ~1.2 MB).
- **D8 Tiles at runtime:** the site probes `Range: bytes=0-15`; 206 -> pmtiles range reads, otherwise it loads the
  (small) archive whole into memory. The service worker answers Range requests from its cached copy with 206
  slices, so offline uses the same path. The z/x/y export (`atlas basemap --xyz`) stays available for M4.
- **D9 Service worker precache list is generated** (`atlas build` / `atlas sw` rewrite ASSETS + a content-hash
  VERSION in site/sw.js). Any data or code change -> new VERSION -> update toast.
- **D10 MapLibre 6.12 is ESM-only** (maplibre-gl.mjs + shared + worker); markers are drawn on demand through
  `setMissingStyleImageResolver`. pmtiles uses its IIFE build (the ESM build imports the bare "fflate" specifier).
- **D11 pwa-skill check.js "external URL" errors are links, not requests:** Google Maps walking directions (brief
  requirement) and the OSM/Protomaps attribution links. Its "email" warnings are sprite file names (`light@2x.png`).
- **D12 `?rafshim` test hook** in index.html: the preview pane freezes requestAnimationFrame and MapLibre 6 waits
  one frame before loading a style, so pane tests open the page with `?rafshim`. No effect otherwise.
- **D13 First Week tour** (engine/curation.json) has 12 stops keyed by OSM refs, so they keep resolving after a
  walked feature replaces the draft (it inherits `osm_ref`). Texts are written generic on purpose (no opening hours
  or facilities that could be wrong); edit them freely.
- **D14 Typeface:** Overpass (OFL), the open font modelled on US highway signage, fits the transit-guide brief.
