# campus.bundle format (schema version 1)

`site/data/campus.bundle` is everything the PWA knows about the campus except the basemap and photos.
It is written by `atlas build` and read by `site/js/bundle.js`. The payload is checked against
[`bundle.schema.json`](bundle.schema.json) at build time and again in CI before deploy.

## Byte layout

| Offset | Size | Type | Value |
|---:|---:|---|---|
| 0 | 4 | ASCII | magic `UATL` (`55 41 54 4C`) |
| 4 | 2 | uint16, big-endian | schema version (`1`) |
| 6 | 4 | uint32, big-endian | build timestamp, Unix seconds (UTC) |
| 10 | … | gzip stream (RFC 1952) | MessagePack payload (one map) |

Readers must reject a wrong magic and any schema version they don't know. The header timestamp equals
`payload.build.ts`; the site uses it to notice a new bundle and show the update toast.

Decoding in the browser:

```js
const buf = await (await fetch('data/campus.bundle')).arrayBuffer();
const dv = new DataView(buf);
// check magic, read dv.getUint16(4), dv.getUint32(6)
const stream = new Blob([buf.slice(10)]).stream().pipeThrough(new DecompressionStream('gzip'));
const payload = msgpackDecode(new Uint8Array(await new Response(stream).arrayBuffer()));
```

## Payload conventions

- **Coordinates are integers in micro-degrees** (`round(deg * 1e6)`, ~0.11 m at the equator), always `[lon, lat]`.
- **Polygons are delta-coded**: a ring is one flat int array `[x0, y0, x1-x0, y1-y0, x2-x1, …]` (the closing vertex
  is omitted). Small deltas make the MessagePack ints 1–3 bytes each, and gzip does the rest.
- **References are array indices** (`par`, `fp`, `ents`, `ph`, `pl`), not ids, to keep the payload small.
  The only string id is the place `id` (first 8 hex digits of the feature uuid), which is stable across builds and
  used in deep links (`#p=<id>`).
- Short keys (`en`, `ms`, `p`, `c`, `k`) because they repeat for every place. Full meaning is in the schema.

## Top-level keys

| Key | Content |
|---|---|
| `v` | schema version (same as header) |
| `build` | `ts`, `date` (YYYY-MM-DD), `engine` version, `tiles` (`pmtiles` or `xyz` fallback), `counts` |
| `campus` | `id`, names `en`/`ms`, `center`, `bbox` [w,s,e,n], start `zoom`, `bearing`, `tz` |
| `categories` | `id`, `en`, `ms`, filter `group`, `icon` name, `color`, `chip` (shown as a filter chip) |
| `places` | buildings, POIs and path notes (entrances live in `entrances`) |
| `entrances` | `pl` building index, `p` point, `b` bearing into the building, names, `main`, `sf` step-free, `snap` |
| `footprints` | `pl` owner (null = unlinked context building), `h` metres, `src` (`osm`/`override`), `r` rings |
| `search` | `[term, place index, weight]`; terms are lower-case and accent-folded; weight 3 code, 2 name, 1 alias/room |
| `tour` | First Week steps: `pl`, title `en`/`ms`, story text `t_en`/`t_ms` |
| `photos` | `f` path under `data/` (`photos/<12 hex>.webp`), `w`, `h`, `cap` |
| `paths` | optional walking graph (delta-coded polylines) |

### Place fields

`id` short id · `k` kind (`building`/`poi`/`path_note`) · `c` category · `en`/`ms` names · `code` · `al` aliases ·
`p` point · `par` parent building · `fl` floor (POI) · `nf` storeys · `h` extrusion height (m) ·
`rooms` `[name, code, floor, kind]` · `fp` footprint · `ents` entrances · `ph` photos · `lv` last verified date ·
`src` (`survey`, `osm` draft, `manual`) · `ok` walked and confirmed · `acc` capture accuracy (m) · `sf` step-free ·
`note`/`note_ms`.

Places with `ok: false` are drafts (OSM names, not walked yet); the site badges them "Not walked yet".

## Versioning

Bump the version (header, `v`, every schema's `x-schema-version`, `SCHEMA_VERSION` in the engine, the Surveyor and
`site/js/bundle.js`) when a change would break an older reader: removing or renaming a key, changing a type or
the coordinate encoding. Adding an optional key is not breaking.

## Budgets

Set in `engine/atlas.toml` and enforced by `atlas build` (it fails, not warns): bundle < 2 MB, `campus.pmtiles`
< 25 MB, each photo < 150 KB.
