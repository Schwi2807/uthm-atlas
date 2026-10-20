// Sun position (azimuth from north, clockwise; altitude above horizon), NOAA / SunCalc-style low-precision formula.
// Accurate to well under a degree: plenty for lighting and shadows.

const rad = Math.PI / 180;
const dayMs = 86400000;
const J1970 = 2440588, J2000 = 2451545;
const e = rad * 23.4397; // obliquity of the Earth

const toDays = (date) => date.valueOf() / dayMs - 0.5 + J1970 - J2000;

function sunCoords(d) {
  const M = rad * (357.5291 + 0.98560028 * d);
  const C = rad * (1.9148 * Math.sin(M) + 0.02 * Math.sin(2 * M) + 0.0003 * Math.sin(3 * M));
  const L = M + C + rad * 102.9372 + Math.PI;
  return {
    dec: Math.asin(Math.sin(e) * Math.sin(L)),
    ra: Math.atan2(Math.sin(L) * Math.cos(e), Math.cos(L)),
  };
}

export function sunPosition(date, lat, lon) {
  const lw = rad * -lon, phi = rad * lat, d = toDays(date);
  const c = sunCoords(d);
  const H = rad * (280.16 + 360.9856235 * d) - lw - c.ra;
  const alt = Math.asin(Math.sin(phi) * Math.sin(c.dec) + Math.cos(phi) * Math.cos(c.dec) * Math.cos(H));
  // atan2 gives azimuth from south, westward; convert to from north, clockwise
  const azS = Math.atan2(Math.sin(H), Math.cos(H) * Math.sin(phi) - Math.tan(c.dec) * Math.cos(phi));
  return { altitude: alt / rad, azimuth: ((azS / rad) + 180 + 360) % 360 };
}

// Light settings for MapLibre's setLight + a shadow vector, for "now" on campus.
export function campusLight(date, lat, lon) {
  const { altitude, azimuth } = sunPosition(date, lat, lon);
  const day = altitude > 2;
  const golden = altitude > 2 && altitude < 18;
  return {
    altitude, azimuth, day,
    light: {
      anchor: 'map',
      position: [1.5, day ? azimuth : 200, day ? Math.max(5, 90 - altitude) : 60],
      color: day ? (golden ? '#ffd9a8' : '#ffffff') : '#9fb3ff',
      intensity: day ? (golden ? 0.45 : 0.32) : 0.18,
    },
    // shadow length per metre of height; points away from the sun
    shadow: day ? { k: Math.min(4, 1 / Math.tan(Math.max(altitude, 14) * rad)), dir: (azimuth + 180) % 360 } : null,
  };
}

// Convex hull (monotone chain) of [x, y] points.
function hull(pts) {
  const p = [...pts].sort((a, b) => a[0] - b[0] || a[1] - b[1]);
  const cross = (o, a, b) => (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
  const lower = [], upper = [];
  for (const q of p) { while (lower.length >= 2 && cross(lower.at(-2), lower.at(-1), q) <= 0) lower.pop(); lower.push(q); }
  for (const q of p.reverse()) { while (upper.length >= 2 && cross(upper.at(-2), upper.at(-1), q) <= 0) upper.pop(); upper.push(q); }
  return lower.slice(0, -1).concat(upper.slice(0, -1));
}

// Ground shadows: each footprint swept along the shadow direction by height * k. Equirectangular metres.
export function shadowGeoJSON(footprintsFC, shadow) {
  const features = [];
  if (shadow) {
    const lat0 = footprintsFC.features[0]?.geometry.coordinates[0][0][1] ?? 0;
    const mPerDegLat = 111320, mPerDegLon = 111320 * Math.cos(lat0 * rad);
    const dx = Math.sin(shadow.dir * rad), dy = Math.cos(shadow.dir * rad);
    for (const f of footprintsFC.features) {
      const len = f.properties.h * shadow.k;
      const ox = (dx * len) / mPerDegLon, oy = (dy * len) / mPerDegLat;
      const ring = f.geometry.coordinates[0];
      const pts = ring.concat(ring.map(([x, y]) => [x + ox, y + oy]));
      const h = hull(pts);
      if (h.length >= 3) features.push({ type: 'Feature', geometry: { type: 'Polygon', coordinates: [[...h, h[0]]] }, properties: {} });
    }
  }
  return { type: 'FeatureCollection', features };
}
