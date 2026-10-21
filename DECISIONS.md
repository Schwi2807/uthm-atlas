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
- **D15 Public-repo privacy defaults:** `surveys/*.uthmsurvey`, `engine/work/photos|tracks|fixes/` are git-ignored
  (they hold the owner's walked GPS trail and unreviewed photos). The merged `engine/work/features.geojson`,
  footprints and the built site are committed, so any machine can rebuild the bundle. Opt in by editing .gitignore.
- **D16 Pages fallback without a rebuild:** if the live Range probe fails, CI runs `atlas xyz --drop-pmtiles` +
  `atlas retile --tiles xyz` (re-encodes the committed bundle with tiles=xyz and a new timestamp) and redeploys,
  so photos that only exist on the laptop are never needed in CI. The site reads `build.tiles` and loads
  `data/tiles/{z}/{x}/{y}.mvt` (tiles stored inflated, since static hosts don't send Content-Encoding).
- **D17 Identity check** (`tools/identity_check.py`, also a CI step): commit identities must be Schwi2807 with a
  no-reply address; tracked files may not contain home-directory paths or real e-mail addresses. Names to look for
  are passed in `ATLAS_FORBIDDEN_NAMES` at run time and are never written into the repo.
- **D18 Android toolchain:** Android Studio (winget) + SDK via its wizard (licences accepted by the owner).
  Builds use a portable Temurin JDK 17 (`C:\Dev\tools\jdk`, sha256 checked) because Studio's bundled JBR is Java 25,
  and `collector/build.ps1` points TEMP at `C:\Dev\tmp`: Java's Windows "loopback" pipe is a Unix-domain socket file
  in TEMP and Claude's long TEMP path makes Gradle fail ("Unable to establish loopback connection").
  AGP 9.4.1 + Gradle 9.8.2 (Compose 1.12 needs AGP >= 9.1), Kotlin 2.4.21 built into AGP, KSP 2.3.12,
  compileSdk 37 / targetSdk 36 / minSdk 29, `android.newDsl=false` (classic DSL for sourceSets/testOptions).
- **D19 Surveyor positions come from LocationManager's GPS provider**, not the fused provider: every capture is a
  real satellite fix, and no Google Play services dependency. Averaging is inverse-variance weighted (1/acc^2)
  in a local tangent plane; the ring shows the 95% radius of the mean (2.45 sigma / sqrt(n_eff)); stop at N fixes or
  when that radius <= 4 m and the mean moved < 0.35 m over 5 fixes.
- **D20 Photos:** in-app CameraX capture, falling back to the system camera app if CameraX can't bind. Originals
  keep their EXIF on the phone and in the export; the engine strips everything when publishing.
- **D21 Suggestion pack** = `site/data/suggestions.json` (OSM names + Wikidata labels, ~50 KB), downloaded by the
  Surveyor from the Pages site on Wi-Fi or imported from a file. Chips fill the form fields only.
- **D22 Honest capture ring:** on the real Redmi 10C the GPS provider repeats one position indoors, so raw scatter
  claimed ±0.1 m. The 95% radius is floored at half the (weighted) mean reported accuracy, and "stable" early stop
  only happens when that floored radius is <= 4 m (regression test `repeatedIdenticalFixesDoNotFakePrecision`).
- **D23 The Redmi 10C has no magnetometer and no barometer** (only an accelerometer). Door direction: 8-point chips
  plus "toward <building>" and typed degrees; the engine squares snapped door arrows to the wall's inward normal
  when the recorded bearing is within 60° (original kept as `_bearing_orig`). Floors: manual stepper.
- **D24 Exports to Downloads use MIME application/octet-stream** (with application/zip MediaStore appended ".zip");
  `atlas ingest` also picks up `*.uthmsurvey.zip`.
- **D25 Load order (Lighthouse):** MapLibre CSS loads non-blocking, pmtiles.js is deferred, and the map module is
  imported in parallel with the bundle download. `atlas preview` now gzips text like GitHub Pages so local
  Lighthouse numbers are realistic (without it FCP looked 2.5x worse). Remaining TBT is MapLibre's own ~1.7 s
  script evaluation on a 4x-throttled CPU.
