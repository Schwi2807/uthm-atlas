#!/usr/bin/env node
// Generate the Surveyor's basemap style (MapLibre Native) from the same Protomaps style code the site uses,
// so both apps draw the same campus.pmtiles the same way. Output: collector/app/src/main/assets/basemap-dark.json
// Usage: node tools/make-native-style.mjs
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const { layers, namedFlavor } = await import(pathToFileURL(path.join(root, 'site/vendor/protomaps-basemaps.mjs')).href);

// instrument-panel flavour: very dark ground, quiet roads, amber is reserved for the app's own overlays
const flavor = {
  ...namedFlavor('dark'),
  background: '#0b0c0e', earth: '#0f1114', park_a: '#122016', park_b: '#15261a', wood_a: '#122016', wood_b: '#15261a',
  water: '#0f2433', buildings: '#1f2329', minor_a: '#262a31', minor_b: '#262a31', minor_service: '#22262c', major: '#30353d',
  highway: '#3a3f47', other: '#1d2126', school: '#13161a', pedestrian: '#181b20',
};
const style = {
  version: 8,
  name: 'UTHM Atlas Surveyor (dark)',
  glyphs: 'asset://map/fonts/{fontstack}/{range}.pbf',
  sprite: 'asset://map/sprites/dark',
  sources: {
    // the app swaps __PMTILES__ for a pmtiles://file://... URL to its copy of campus.pmtiles
    pm: { type: 'vector', url: '__PMTILES__', attribution: '© OpenStreetMap contributors · Protomaps' },
  },
  layers: layers('pm', flavor, { lang: 'en' }).filter((l) => l.id !== 'pois'),
};
const out = path.join(root, 'collector/app/src/main/assets/basemap-dark.json');
fs.mkdirSync(path.dirname(out), { recursive: true });
fs.writeFileSync(out, JSON.stringify(style));
console.log(`${path.relative(root, out)}: ${style.layers.length} layers`);
