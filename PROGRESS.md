# Progress

| Milestone | State | Tag |
|---|---|---|
| M1 skeleton + schema + BUNDLE_FORMAT.md | done | m1-done |
| M2 engine build + round-trip test | done (31 tests) | m2-done |
| M3 PWA MVP | done: offline after one visit (server stopped), search aliases/typos, cards, deep links, tour, EN/BM | m3-done |
| M4 Pages deploy | | |
| M5 Surveyor MVP | done: HUD + skyplot, averaged capture (honest 95% ring), form, export; on the Redmi 10C a real export validated + ingested by the engine; 11 unit tests incl. export-vs-schema | m5-done |
| M6 Surveyor sensors + map | done: offline MapLibre map (same PMTiles), track foreground service, coverage layer, CameraX, compass/barometer with fallbacks (Redmi 10C has neither: chips + manual floor), suggestion pack (download test waits for Pages) | m6-done |
| M7 engine ingest pipeline | code + 44 tests done (dedupe, drafts, snapping, wall-squared door arrows, overrides); real 10-place walk pending | |
| M8 PWA polish | 3D + sun + tour + BM + deep links + update toast already in M3; Lighthouse pass left | |
| M9 nice-to-haves | | |

## Testing the site in the preview pane

`atlas preview` (launch.json entry `uthm-atlas`, port 8810), open `/?rafshim` (see DECISIONS D12).
