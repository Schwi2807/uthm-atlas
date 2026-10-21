# Atlas Surveyor (Android)

Field tool for walking the campus: live GNSS HUD + skyplot, averaged capture, feature form, offline map with
breadcrumb track and fix-quality coverage, export to one `.uthmsurvey` file per walk.

- Package `io.github.schwi2807.atlas`, minSdk 29, Kotlin + Jetpack Compose, Room, CameraX, MapLibre Native.
- No account, no cloud, no analytics. Location, photos and tracks stay on the phone until you export a walk.
- Every sensor is optional: no barometer -> floors by hand; no compass -> type the door bearing; no raw GNSS ->
  the toggle explains why.

## Build

From Android Studio: *Open* `collector/`, let it sync, Run on the phone.

From a terminal (Windows):

```bash
powershell -ExecutionPolicy Bypass -File collector/build.ps1
```

That runs `assembleDebug testDebugUnitTest` with the portable JDK 17 in `C:\Dev\tools\jdk` and a short TEMP folder
(long TEMP paths break Java's local "loopback" pipe on Windows). The APK lands in
`collector/app/build/outputs/apk/debug/app-debug.apk`.

Install on a phone with USB debugging on:

```bash
adb install -r collector/app/build/outputs/apk/debug/app-debug.apk
```

The build copies `site/data/campus.pmtiles`, `site/map/` (glyphs + sprites) and `schema/*.json` into the APK
assets, so the Surveyor's map matches the website. Run `atlas basemap` first on a fresh checkout. The basemap
style comes from `node tools/make-native-style.mjs` (same Protomaps code as the site).

## Walking a session

1. **Setup** tab: download the name-suggestion pack on Wi-Fi. If the phone has a barometer, stand on a ground
   floor and tap *Calibrate ground floor*.
2. **Walks** tab: *+ New walk*. Tap **● TRK** to record the breadcrumb track (it keeps running with the screen off;
   stop it from the notification or the same button).
3. **HUD**: wait for a 3D fix, stand still on the spot, tap **Capture**. The ring shrinks as the averaged position
   settles (default 30 fixes, or earlier when it is stable). Short tick = a good fix arrived.
4. **Describe**: pick the kind and category, type or confirm the name (suggestion chips only fill the field), set
   parent building, floor, door direction (stand in the doorway facing in, *Lock*), photos, notes. **Save**.
5. Weak capture (worse than 10 m)? The HUD says so and offers **Re-capture**. You can also re-capture any feature
   later from the Walks tab.
6. **Walks** tab -> the walk -> **Export** (share sheet) or **To Downloads** (`Downloads/UTHM Atlas/`).
   Copy the `.uthmsurvey` file to the laptop's `surveys/` folder and run `atlas ingest`.

## Tests

`ExportSchemaTest` exports a session and validates the manifest, every feature and the track against the shared
JSON Schemas in `/schema`. `AveragerTest` covers weighting, convergence, the stop rules and barometric floors.
