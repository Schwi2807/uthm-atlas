// campus.bundle reader. Format: schema/BUNDLE_FORMAT.md
//   'UATL' | uint16 BE schema version | uint32 BE build time | gzip(MessagePack payload)
import { decode } from './msgpack.js';

export const SCHEMA_VERSION = 1;

export async function gunzip(bytes) {
  const stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream('gzip'));
  return new Uint8Array(await new Response(stream).arrayBuffer());
}

export function readHeader(buf) {
  const dv = new DataView(buf);
  if (buf.byteLength < 10) throw new Error('campus.bundle: too short');
  const magic = String.fromCharCode(dv.getUint8(0), dv.getUint8(1), dv.getUint8(2), dv.getUint8(3));
  if (magic !== 'UATL') throw new Error(`campus.bundle: bad magic ${magic}`);
  return { version: dv.getUint16(4), ts: dv.getUint32(6) };
}

export async function parseBundle(buf) {
  const { version, ts } = readHeader(buf);
  if (version !== SCHEMA_VERSION) throw new Error(`campus.bundle: schema ${version}, this app reads ${SCHEMA_VERSION}`);
  const payload = decode(await gunzip(new Uint8Array(buf, 10)));
  if (payload.build?.ts !== ts) throw new Error('campus.bundle: header and payload disagree');
  return enrich(payload);
}

// --- coordinate helpers (integers in micro-degrees -> floats)
export const deg = (p) => [p[0] / 1e6, p[1] / 1e6];

export function ringCoords(flat) {
  const out = [];
  let x = 0, y = 0;
  for (let i = 0; i < flat.length; i += 2) {
    if (i === 0) { x = flat[0]; y = flat[1]; } else { x += flat[i]; y += flat[i + 1]; }
    out.push([x / 1e6, y / 1e6]);
  }
  if (out.length) out.push(out[0]);
  return out;
}

// Pre-compute what the UI needs often: lon/lat, children, category lookups.
function enrich(b) {
  b.cat = Object.fromEntries(b.categories.map((c) => [c.id, c]));
  b.places.forEach((p, i) => {
    p.i = i;
    p.ll = deg(p.p);
    p.kids = [];
  });
  b.places.forEach((p) => { if (p.par != null && b.places[p.par]) b.places[p.par].kids.push(p.i); });
  b.entrances.forEach((e, i) => { e.i = i; e.ll = deg(e.p); });
  b.byId = new Map(b.places.map((p) => [p.id, p]));
  return b;
}

export function footprintsGeoJSON(b) {
  return {
    type: 'FeatureCollection',
    features: b.footprints.map((f, i) => {
      const pl = f.pl != null ? b.places[f.pl] : null;
      return {
        type: 'Feature',
        id: i,
        geometry: { type: 'Polygon', coordinates: f.r.map(ringCoords) },
        properties: {
          i, pl: f.pl ?? -1, h: f.h, c: pl ? pl.c : 'context',
          color: pl ? (b.cat[pl.c]?.color || '#64748b') : '#94a3b8', ok: pl ? pl.ok : false,
        },
      };
    }),
  };
}

export function placesGeoJSON(b) {
  return {
    type: 'FeatureCollection',
    features: b.places.map((p) => ({
      type: 'Feature',
      id: p.i,
      geometry: { type: 'Point', coordinates: p.ll },
      properties: {
        i: p.i, k: p.k, c: p.c, en: p.en, ms: p.ms || p.en, code: p.code || '', ok: p.ok,
        rank: p.k === 'building' ? (p.c === 'faculty' || p.c === 'library' ? 0 : 1) : (b.cat[p.c]?.chip ? 2 : 3),
      },
    })),
  };
}

export function entrancesGeoJSON(b) {
  return {
    type: 'FeatureCollection',
    features: b.entrances.map((e) => ({
      type: 'Feature',
      id: e.i,
      geometry: { type: 'Point', coordinates: e.ll },
      properties: { i: e.i, pl: e.pl, b: e.b, main: !!e.main, sf: !!e.sf },
    })),
  };
}
