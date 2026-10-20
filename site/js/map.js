// MapLibre map: Protomaps basemap (campus.pmtiles) + the bundle's footprints, places and entrances.
import * as maplibregl from '../vendor/maplibre-gl.mjs';
import { layers as pmLayers, namedFlavor } from '../vendor/protomaps-basemaps.mjs';
import { footprintsGeoJSON, placesGeoJSON, entrancesGeoJSON } from './bundle.js';
import { markerImage, arrowImage } from './icons.js';
import { campusLight, shadowGeoJSON } from './sun.js';

const BASE = new URL('.', document.baseURI).href; // site root, works under github.io/<repo>/
const reduceMotion = matchMedia('(prefers-reduced-motion: reduce)').matches;

// Transit-guide palette for the basemap: quiet paper ground, white streets, clear water and greens.
const FLAVORS = {
  light: {
    background: '#f1f0ea', earth: '#f1f0ea', park_a: '#dcebd2', park_b: '#c6e2b5', wood_a: '#d6e6cc', wood_b: '#bfdcae',
    scrub_a: '#e0ead6', scrub_b: '#cfe3c0', school: '#ecebe4', pedestrian: '#e9e7de', hospital: '#efe3e1',
    industrial: '#e6e6e2', water: '#9fd0ef', sand: '#ece6d6', beach: '#efe9d4', zoo: '#d9e8d7', military: '#e7e6e2',
    minor_service: '#ffffff', minor_a: '#ffffff', minor_b: '#ffffff', link: '#ffffff', major: '#ffffff',
    highway: '#fff3c4', other: '#f7f6f1', minor_casing: '#d8d6cd', minor_service_casing: '#d8d6cd',
    major_casing_early: '#cfccc2', major_casing_late: '#cfccc2', highway_casing_early: '#d7c27a', highway_casing_late: '#d7c27a',
    link_casing: '#d8d6cd', roads_label_minor: '#6b6f76', roads_label_major: '#4d5158', railway: '#9aa1a6',
  },
  dark: {
    background: '#14171c', earth: '#14171c', park_a: '#1b2a20', park_b: '#203326', wood_a: '#1b2a20', wood_b: '#1f3324',
    scrub_a: '#1a2620', scrub_b: '#1e2c23', school: '#191c22', pedestrian: '#1c1f25', hospital: '#241c1e',
    industrial: '#1a1d22', water: '#173247', sand: '#211f1a', beach: '#221f19', military: '#1b1d21',
    minor_service: '#2a2f37', minor_a: '#2a2f37', minor_b: '#2a2f37', link: '#333944', major: '#3a414c',
    highway: '#4a4632', other: '#232831', minor_casing: '#14171c', minor_service_casing: '#14171c',
    major_casing_early: '#14171c', major_casing_late: '#14171c', highway_casing_early: '#14171c', highway_casing_late: '#14171c',
    link_casing: '#14171c', roads_label_minor: '#9aa3ad', roads_label_major: '#c3c9d0', railway: '#59616b',
  },
};

const PAL = {
  light: { ctx: '#d9d7cf', ctxTop: '#e4e2da', line: '#9b988d', shadow: '#2a2f3a', shadowOp: 0.14, sel: '#ffc400', text: '#121417', halo: '#ffffff', me: '#1565d8' },
  dark: { ctx: '#2b3038', ctxTop: '#323843', line: '#59606b', shadow: '#000000', shadowOp: 0.38, sel: '#ffc400', text: '#f2f2ee', halo: '#0d0f12', me: '#5aa2ff' },
};

function mix(hex, toward, amt) {
  const p = (h) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16));
  const a = p(hex), b = p(toward);
  return '#' + a.map((v, i) => Math.round(v + (b[i] - v) * amt).toString(16).padStart(2, '0')).join('');
}

export class CampusMap {
  constructor(el, bundle, { theme, lang, tilesUrl, onSelect, onMove }) {
    this.b = bundle;
    this.theme = theme;
    this.lang = lang;
    this.onSelect = onSelect;
    this.is3d = true;
    this.selected = null;
    this.filter = null;
    this.fp = footprintsGeoJSON(bundle);
    this.places = placesGeoJSON(bundle);
    this.ents = entrancesGeoJSON(bundle);
    this.sun = campusLight(new Date(), bundle.places[0]?.ll[1] ?? 1.86, bundle.places[0]?.ll[0] ?? 103.08);
    const [w, s, e, n] = bundle.campus.bbox.map((v) => v / 1e6);
    const padLon = (e - w) * 0.35, padLat = (n - s) * 0.35;
    this.map = new maplibregl.Map({
      container: el,
      style: this.style(tilesUrl),
      center: bundle.campus.center.map((v) => v / 1e6),
      zoom: bundle.campus.zoom,
      pitch: 50,
      bearing: -18,
      minZoom: 13.5,
      maxZoom: 20,
      maxPitch: 70,
      maxBounds: [[w - padLon, s - padLat], [e + padLon, n + padLat]],
      attributionControl: false,
      localIdeographFontFamily: 'sans-serif',
      fadeDuration: reduceMotion ? 0 : 200,
    });
    this.tilesUrl = tilesUrl;
    this.map.addControl(new maplibregl.AttributionControl({ compact: true, customAttribution: '<a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">© OpenStreetMap</a> · <a href="https://protomaps.com" target="_blank" rel="noopener">Protomaps</a>' }), 'bottom-right');
    this.map.on('styleimagemissing', (ev) => this.makeImage(ev.id));
    this.map.on('load', () => this.wire());
    if (onMove) this.map.on('moveend', onMove);
    this.sunTimer = setInterval(() => this.updateSun(), 5 * 60 * 1000);
  }

  style(tilesUrl) {
    const theme = this.theme;
    const pal = PAL[theme];
    const flavor = { ...namedFlavor(theme), ...FLAVORS[theme] };
    const base = pmLayers('pm', flavor, { lang: this.lang === 'ms' ? 'ms' : 'en' })
      .filter((l) => l.id !== 'buildings' && l.id !== 'pois');
    const labelStart = base.findIndex((l) => l.type === 'symbol');
    const below = base.slice(0, labelStart), labels = base.slice(labelStart);
    const nameExpr = ['coalesce', ['get', this.lang === 'ms' ? 'ms' : 'en'], ['get', 'en']];
    const label = ['case', ['!=', ['get', 'code'], ''], ['concat', ['get', 'code'], '  ', nameExpr], nameExpr];
    const tint = theme === 'dark' ? '#1b1f27' : '#ffffff';
    const fpColor = ['case', ['==', ['get', 'pl'], -1], pal.ctx,
      ['match', ['get', 'c'], ...this.b.categories.flatMap((c) => [c.id, mix(c.color, tint, theme === 'dark' ? 0.55 : 0.62)]), pal.ctx]];
    const sel = ['boolean', ['feature-state', 'sel'], false];
    const placeLayer = (rank, minzoom) => ({
      id: `places-${rank}`, type: 'symbol', source: 'places', minzoom,
      filter: ['==', ['get', 'rank'], rank],
      layout: {
        'icon-image': ['concat', 'cat-', ['get', 'c'], ['case', ['get', 'ok'], '', '-d']],
        'icon-size': ['interpolate', ['linear'], ['zoom'], 15, 0.7, 18, 1],
        'icon-allow-overlap': rank === 0, 'icon-padding': 2,
        'text-field': label, 'text-font': ['Noto Sans Medium'],
        'text-size': ['interpolate', ['linear'], ['zoom'], 15, 11, 18, rank === 0 ? 14.5 : 13],
        'text-offset': [0, 1.25], 'text-anchor': 'top', 'text-optional': true, 'text-max-width': 9,
        'symbol-sort-key': ['get', 'i'],
      },
      paint: { 'text-color': pal.text, 'text-halo-color': pal.halo, 'text-halo-width': 1.6 },
    });
    return {
      version: 8,
      glyphs: `${BASE}map/fonts/{fontstack}/{range}.pbf`,
      sprite: `${BASE}map/sprites/${theme}`,
      sources: {
        pm: { type: 'vector', url: tilesUrl, attribution: '' },
        fp: { type: 'geojson', data: this.fp },
        shadows: { type: 'geojson', data: shadowGeoJSON(this.fp, this.sun.shadow) },
        places: { type: 'geojson', data: this.places },
        ents: { type: 'geojson', data: this.ents },
        me: { type: 'geojson', data: { type: 'FeatureCollection', features: [] } },
        route: { type: 'geojson', data: { type: 'FeatureCollection', features: [] } },
      },
      light: this.sun.light,
      layers: [
        ...below,
        { id: 'shadows', type: 'fill', source: 'shadows', paint: { 'fill-color': pal.shadow, 'fill-opacity': pal.shadowOp, 'fill-antialias': false } },
        { id: 'fp-flat', type: 'fill', source: 'fp', layout: { visibility: this.is3d ? 'none' : 'visible' },
          paint: { 'fill-color': ['case', sel, pal.sel, fpColor], 'fill-opacity': 0.95 } },
        { id: 'fp-line', type: 'line', source: 'fp', layout: { visibility: this.is3d ? 'none' : 'visible' },
          paint: { 'line-color': ['case', sel, '#121417', pal.line], 'line-width': ['case', sel, 2.5, 0.8] } },
        { id: 'fp-3d', type: 'fill-extrusion', source: 'fp', layout: { visibility: this.is3d ? 'visible' : 'none' },
          paint: {
            'fill-extrusion-color': ['case', sel, pal.sel, fpColor],
            'fill-extrusion-height': ['interpolate', ['linear'], ['zoom'], 14, 0, 15.5, ['get', 'h']],
            'fill-extrusion-base': 0, 'fill-extrusion-opacity': 0.94, 'fill-extrusion-vertical-gradient': true,
          } },
        { id: 'route', type: 'line', source: 'route', layout: { 'line-cap': 'round', 'line-join': 'round' },
          paint: { 'line-color': '#121417', 'line-width': 4, 'line-dasharray': [0.1, 2] } },
        ...labels,
        { id: 'ents', type: 'symbol', source: 'ents', minzoom: 17.2,
          layout: { 'icon-image': ['case', ['get', 'main'], 'ent-main', 'ent'], 'icon-rotate': ['get', 'b'],
            'icon-rotation-alignment': 'map', 'icon-pitch-alignment': 'map', 'icon-allow-overlap': true,
            'icon-size': ['interpolate', ['linear'], ['zoom'], 17, 0.55, 20, 1] } },
        { id: 'ents-sel', type: 'symbol', source: 'ents', filter: ['==', ['get', 'pl'], -2],
          layout: { 'icon-image': 'ent-sel', 'icon-rotate': ['get', 'b'], 'icon-rotation-alignment': 'map',
            'icon-pitch-alignment': 'map', 'icon-allow-overlap': true, 'icon-ignore-placement': true,
            'icon-size': ['interpolate', ['linear'], ['zoom'], 15, 0.6, 19, 1.15] } },
        placeLayer(3, 17.3), placeLayer(2, 16.4), placeLayer(1, 15.6), placeLayer(0, 14.2),
        { id: 'me-acc', type: 'fill', source: 'me', filter: ['==', ['get', 'k'], 'acc'],
          paint: { 'fill-color': pal.me, 'fill-opacity': 0.12 } },
        { id: 'me-dot', type: 'circle', source: 'me', filter: ['==', ['get', 'k'], 'dot'],
          paint: { 'circle-radius': 8, 'circle-color': pal.me, 'circle-stroke-color': '#ffffff', 'circle-stroke-width': 3,
            'circle-pitch-alignment': 'map' } },
      ],
    };
  }

  makeImage(id) {
    if (this.map.hasImage(id)) return;
    const ratio = Math.min(3, Math.max(2, devicePixelRatio || 1));
    if (id.startsWith('cat-')) {
      const draft = id.endsWith('-d');
      const cid = id.slice(4, draft ? -2 : undefined);
      const c = this.b.cat[cid] || { icon: 'pin', color: '#475569' };
      this.map.addImage(id, markerImage(c.icon, c.color, { ratio, draft }), { pixelRatio: ratio });
    } else if (id === 'ent' || id === 'ent-main' || id === 'ent-sel') {
      const fill = id === 'ent-sel' ? '#ffc400' : id === 'ent-main' ? '#0a7a3e' : '#3c4450';
      this.map.addImage(id, arrowImage(fill, { ratio }), { pixelRatio: ratio });
    }
  }

  wire() {
    const m = this.map;
    const clickable = ['places-0', 'places-1', 'places-2', 'places-3'];
    m.on('click', (ev) => {
      const pad = 8;
      const box = [[ev.point.x - pad, ev.point.y - pad], [ev.point.x + pad, ev.point.y + pad]];
      const hits = m.queryRenderedFeatures(box, { layers: clickable });
      if (hits.length) return this.onSelect(hits[0].properties.i, 'map');
      const fps = m.queryRenderedFeatures(ev.point, { layers: [this.is3d ? 'fp-3d' : 'fp-flat'] });
      const pl = fps.find((f) => f.properties.pl >= 0);
      if (pl) return this.onSelect(pl.properties.pl, 'map');
      this.onSelect(null, 'map');
    });
    for (const id of clickable) {
      m.on('mouseenter', id, () => { m.getCanvas().style.cursor = 'pointer'; });
      m.on('mouseleave', id, () => { m.getCanvas().style.cursor = ''; });
    }
    this.applyFilter();
    this.ready = true;
    this.map.fire('atlas:ready');
  }

  setTheme(theme) {
    if (theme === this.theme) return;
    this.theme = theme;
    this.restyle();
  }

  setLang(lang) {
    if (lang === this.lang) return;
    this.lang = lang;
    this.restyle();
  }

  restyle() {
    const sel = this.selected;
    this.map.setStyle(this.style(this.tilesUrl), { diff: false });
    this.map.once('styledata', () => {
      this.applyFilter();
      if (sel != null) this.highlight(sel);
      if (this.meData) this.map.getSource('me')?.setData(this.meData);
      if (this.routeData) this.map.getSource('route')?.setData(this.routeData);
    });
  }

  updateSun(date = new Date()) {
    const [lon, lat] = this.b.campus.center.map((v) => v / 1e6);
    this.sun = campusLight(date, lat, lon);
    if (!this.map.isStyleLoaded()) return this.sun;
    this.map.setLight(this.sun.light);
    this.map.getSource('shadows')?.setData(shadowGeoJSON(this.fp, this.sun.shadow));
    return this.sun;
  }

  set3d(on) {
    this.is3d = on;
    const vis = (id, v) => this.map.getLayer(id) && this.map.setLayoutProperty(id, 'visibility', v ? 'visible' : 'none');
    vis('fp-3d', on); vis('fp-flat', !on); vis('fp-line', !on);
    this.map.easeTo({ pitch: on ? 50 : 0, bearing: on ? this.map.getBearing() || -18 : 0, duration: reduceMotion ? 0 : 900 });
  }

  // category chip filter: null = everything
  setFilter(cats) {
    this.filter = cats;
    this.applyFilter();
  }

  applyFilter() {
    if (!this.map.getLayer('places-0')) return;
    for (let r = 0; r < 4; r++) {
      const f = ['all', ['==', ['get', 'rank'], r]];
      if (this.filter) f.push(['in', ['get', 'c'], ['literal', this.filter]]);
      this.map.setFilter(`places-${r}`, f);
    }
    // with a filter active, every matching place shows from campus zoom
    for (let r = 0; r < 4; r++) this.map.setLayerZoomRange(`places-${r}`, this.filter ? 14 : [14.2, 15.6, 16.4, 17.3][r], 24);
  }

  highlight(i) {
    const m = this.map;
    if (this.selFp != null) for (const f of this.selFp) m.setFeatureState({ source: 'fp', id: f }, { sel: false });
    this.selected = i;
    this.selFp = [];
    if (i == null) { m.setFilter('ents-sel', ['==', ['get', 'pl'], -2]); return; }
    const p = this.b.places[i];
    const bi = p.k === 'building' ? i : (p.par ?? i);
    this.fp.features.forEach((f) => { if (f.properties.pl === bi) { this.selFp.push(f.id); m.setFeatureState({ source: 'fp', id: f.id }, { sel: true }); } });
    m.setFilter('ents-sel', ['==', ['get', 'pl'], bi]);
  }

  flyToPlace(i, { offsetY = 0 } = {}) {
    const p = this.b.places[i];
    const zoom = p.k === 'building' ? 17.6 : 18.4;
    this.map.flyTo({
      center: p.ll, zoom: Math.max(this.map.getZoom(), zoom), pitch: this.is3d ? 55 : 0,
      bearing: this.is3d ? this.map.getBearing() : 0, offset: [0, -offsetY / 2],
      duration: reduceMotion ? 0 : 1400, essential: false, curve: 1.3,
    });
  }

  orbitTo(i, { offsetY = 0 } = {}) {
    const p = this.b.places[i];
    const bearing = (this.map.getBearing() + 60) % 360;
    this.map.flyTo({ center: p.ll, zoom: 17.8, pitch: this.is3d ? 58 : 0, bearing: this.is3d ? bearing : 0,
      offset: [0, -offsetY / 2], duration: reduceMotion ? 0 : 2200, curve: 1.2 });
  }

  resetNorth() { this.map.easeTo({ bearing: 0, duration: reduceMotion ? 0 : 600 }); }

  setMe(pos) {
    if (!pos) { this.meData = { type: 'FeatureCollection', features: [] }; }
    else {
      const [lon, lat] = pos.ll;
      const r = Math.min(pos.acc || 0, 150);
      const pts = [];
      for (let k = 0; k <= 48; k++) {
        const a = (k / 48) * 2 * Math.PI;
        pts.push([lon + (r * Math.sin(a)) / (111320 * Math.cos((lat * Math.PI) / 180)), lat + (r * Math.cos(a)) / 111320]);
      }
      this.meData = { type: 'FeatureCollection', features: [
        { type: 'Feature', geometry: { type: 'Polygon', coordinates: [pts] }, properties: { k: 'acc' } },
        { type: 'Feature', geometry: { type: 'Point', coordinates: [lon, lat] }, properties: { k: 'dot' } },
      ] };
    }
    this.map.getSource('me')?.setData(this.meData);
  }

  setRoute(coords) {
    this.routeData = { type: 'FeatureCollection', features: coords && coords.length > 1
      ? [{ type: 'Feature', geometry: { type: 'LineString', coordinates: coords }, properties: {} }] : [] };
    this.map.getSource('route')?.setData(this.routeData);
  }
}

// --- PMTiles wiring: range requests when the host supports them, else load the (small) archive whole.
export async function registerTiles() {
  const pm = window.pmtiles;
  const protocol = new pm.Protocol();
  maplibregl.addProtocol('pmtiles', protocol.tile);
  const url = new URL('data/campus.pmtiles', document.baseURI).href;
  let ranged = false;
  try {
    const r = await fetch(url, { headers: { Range: 'bytes=0-15' } });
    ranged = r.status === 206;
    if (r.body) await r.arrayBuffer();
  } catch { /* offline without cache: handled by the caller */ }
  if (ranged) {
    protocol.add(new pm.PMTiles(url));
    return { url: `pmtiles://${url}`, mode: 'range' };
  }
  const buf = await (await fetch(url)).arrayBuffer();
  const key = `${url}#memory`;
  protocol.add(new pm.PMTiles({
    getKey: () => key,
    getBytes: async (offset, length) => ({ data: buf.slice(offset, offset + length) }),
  }));
  return { url: `pmtiles://${key}`, mode: 'memory' };
}

export { maplibregl };
