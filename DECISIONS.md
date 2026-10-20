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
