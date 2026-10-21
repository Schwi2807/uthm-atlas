# Progress

| Milestone | State | Tag |
|---|---|---|
| M1 skeleton + schema + BUNDLE_FORMAT.md | done | m1-done |
| M2 engine build + round-trip test | done (31 tests) | m2-done |
| M3 PWA MVP | done: offline after one visit (server stopped), search aliases/typos, cards, deep links, tour, EN/BM | m3-done |
| M4 Pages deploy | prepared (workflow, Range probe + xyz fallback, README, licence, identity check); waiting for the owner to create the empty repo Schwi2807/uthm-atlas (Pages source = GitHub Actions), then push | |
| M5 Surveyor MVP | done: HUD + skyplot, averaged capture (honest 95% ring), form, export; on the Redmi 10C a real export validated + ingested by the engine; 11 unit tests incl. export-vs-schema | m5-done |
| M6 Surveyor sensors + map | done: offline MapLibre map (same PMTiles), track foreground service, coverage layer, CameraX, compass/barometer with fallbacks (Redmi 10C has neither: chips + manual floor), suggestion pack (download test waits for Pages) | m6-done |
| M7 engine ingest pipeline | code + 44 tests done (dedupe, drafts, snapping, wall-squared door arrows, overrides); real 10-place walk pending | |
| M8 PWA polish | done: Lighthouse mobile (gzip like Pages) perf 74 / a11y 100 / best practices 100 / SEO 100; update toast verified (new SW -> toast -> reload -> new bundle, old cache deleted); install on a real phone = hand test | m8-done |
| M9 nice-to-haves | started: reverify/CampusBundle.kt (Kotlin bundle + MessagePack reader, NOT yet built or wired). Next: add campus.bundle to synced assets, Re-verify screen (stale places, oldest first, distance), then voice notes/GPX, footpath graph, floor selector + accessibility layer, QR codes | |

## Testing the site in the preview pane

`atlas preview` (launch.json entry `uthm-atlas`, port 8810), open `/?rafshim` (see DECISIONS D12).
