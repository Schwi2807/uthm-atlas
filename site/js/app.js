// UTHM Atlas app shell: boot, search, place cards, chips, First Week tour, location, settings, offline status.
import { parseBundle } from './bundle.js';
import { CampusMap, registerTiles } from './map.js';
import { makeSearch, distanceM } from './search.js';
import { t, setLang, getLang, pname, altName, catName, fmtDate, floorLabel } from './i18n.js';
import { svg } from './icons.js';

const APP = 'uthm-atlas';
const $ = (s, r = document) => r.querySelector(s);
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const store = {
  get(k, d) { try { const v = localStorage.getItem(`${APP}:${k}`); return v == null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem(`${APP}:${k}`, JSON.stringify(v)); } catch { /* private mode */ } },
};

const state = {
  b: null, map: null, search: null, me: null, watch: null, filter: null, selected: null,
  tourStep: -1, themePref: store.get('theme', 'auto'), recent: store.get('recent', []),
};

// ---------------------------------------------------------------- small UI helpers
let toastTimer;
function toast(msg, action) {
  const el = $('#toast');
  el.innerHTML = `<span>${esc(msg)}</span>`;
  if (action) {
    const b = document.createElement('button');
    b.type = 'button'; b.textContent = action.label; b.onclick = () => { el.classList.remove('show'); action.run(); };
    el.append(b);
  }
  el.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove('show'), action ? 12000 : 2200);
}

function bullet(cat, { lg = false, draft = false } = {}) {
  const c = state.b.cat[cat] || { icon: 'pin', color: '#475569' };
  return `<span class="bullet${lg ? ' lg' : ''}${draft ? ' draft' : ''}" style="background:${c.color}">${svg(c.icon, { size: lg ? 28 : 21, stroke: 2.2 })}</span>`;
}

function sheetHeight() {
  const s = $('#sheet');
  return s.hidden || matchMedia('(min-width: 900px)').matches ? 0 : s.getBoundingClientRect().height;
}

function openSheet(html, { onOpen } = {}) {
  const s = $('#sheet');
  $('#sheetBody').innerHTML = html;
  $('#sheetBody').scrollTop = 0;
  s.hidden = false;
  s.classList.remove('closing');
  onOpen?.(s);
}

function closeSheet() {
  const s = $('#sheet');
  if (s.hidden) return;
  s.classList.add('closing');
  setTimeout(() => { if (s.classList.contains('closing')) s.hidden = true; }, 260);
  if (state.selected != null) { state.selected = null; state.map?.highlight(null); setHash(''); }
}

function setHash(h) {
  const url = location.pathname + location.search + (h ? `#${h}` : '');
  history.replaceState(null, '', url);
}

// ---------------------------------------------------------------- texts that follow the language
function applyTexts() {
  $('#search').placeholder = t('search');
  $('#search').setAttribute('aria-label', t('searchLabel'));
  $('#menuBtn').setAttribute('aria-label', t('menu'));
  $('#menuBtn').innerHTML = svg('menu');
  $('#searchClear').setAttribute('aria-label', t('close'));
  $('#searchClear').innerHTML = svg('close');
  $('.searchbar-ico').innerHTML = svg('search', { size: 24, stroke: 2.6 });
  $('#locBtn').setAttribute('aria-label', t('locate'));
  $('#locBtn').innerHTML = svg('locate', { size: 24 });
  $('#northBtn').setAttribute('aria-label', t('north'));
  $('#viewBtn').setAttribute('aria-label', state.map?.is3d === false ? t('view3d') : t('view2d'));
  $('#tourBtn').innerHTML = `${svg('star', { size: 22, stroke: 2.4 })}<span>${esc(t('tour'))}</span>`;
  $('#chips').setAttribute('aria-label', t('filterBy'));
  if (state.b) $('#footText').textContent = t('footer', fmtDate(state.b.build.date));
  renderChips();
}

// ---------------------------------------------------------------- chips
function renderChips() {
  if (!state.b) return;
  const cats = state.b.categories.filter((c) => c.chip && state.b.places.some((p) => p.c === c.id));
  const nav = $('#chips');
  nav.innerHTML = `<button type="button" class="chip chip-all" data-cat="" aria-pressed="${!state.filter}">${esc(t('all'))}</button>` +
    cats.map((c) => `<button type="button" class="chip" data-cat="${c.id}" aria-pressed="${state.filter?.[0] === c.id}">${bullet(c.id)}${esc(catName(c))}</button>`).join('');
}

function chooseChip(cat) {
  state.filter = cat ? [cat] : null;
  state.map.setFilter(state.filter);
  renderChips();
  if (!cat) { closeSheet(); return; }
  const near = state.me?.ll || state.b.campus.center.map((v) => v / 1e6);
  const list = state.b.places.filter((p) => p.c === cat).map((p) => ({ p, d: distanceM(near, p.ll) })).sort((a, b) => a.d - b.d);
  const c = state.b.cat[cat];
  openSheet(`
    <div class="card-sign">${bullet(cat, { lg: true })}<div class="grow"><h2 id="sheetTitle">${esc(catName(c))}</h2>
      <div class="alt">${esc(t('places', list.length))}</div></div>
      <button class="iconbtn" type="button" data-act="close" aria-label="${esc(t('close'))}">${svg('close')}</button></div>
    <ul class="list" style="margin-top:12px">${list.map(({ p, d }) => rowHtml(p, state.me ? d : null)).join('')}</ul>`);
}

// ---------------------------------------------------------------- search
function rowHtml(p, dist = null) {
  const c = state.b.cat[p.c];
  const sub = [catName(c), altName(p), p.ok ? null : t('draft')].filter(Boolean).join(' · ');
  return `<li><button type="button" class="row" data-place="${p.i}">${bullet(p.c, { draft: !p.ok })}
    <span class="row-main"><span class="row-title">${p.code ? `<span class="row-code">${esc(p.code)}</span>` : ''}<span>${esc(pname(p))}</span></span>
    <span class="row-sub">${esc(sub)}</span></span>${dist != null ? `<span class="row-dist">${esc(t('distance', dist))}</span>` : ''}</button></li>`;
}

function renderResults() {
  const q = $('#search').value;
  const panel = $('#results');
  $('#searchClear').hidden = !q;
  if (!q.trim()) {
    const rec = state.recent.map((id) => state.b.byId.get(id)).filter(Boolean);
    panel.innerHTML = rec.length
      ? `<h2>${esc(t('recent'))}</h2><ul class="list">${rec.map((p) => rowHtml(p)).join('')}</ul>`
      : `<div class="empty"><strong>${esc(t('searchLabel'))}</strong>${esc(t('tryAlso'))}</div>`;
    return;
  }
  const hits = state.search(q, { near: state.me?.ll });
  panel.innerHTML = hits.length
    ? `<h2>${esc(t('results'))}</h2><ul class="list">${hits.map((h) => rowHtml(h.place, state.me ? distanceM(state.me.ll, h.place.ll) : null)).join('')}</ul>`
    : `<div class="empty"><strong>${esc(t('noResults'))}</strong>${esc(t('tryAlso'))}</div>`;
}

function openResults() { $('#results').hidden = false; renderResults(); }
function closeResults() { $('#results').hidden = true; }

function remember(p) {
  state.recent = [p.id, ...state.recent.filter((x) => x !== p.id)].slice(0, 8);
  store.set('recent', state.recent);
}

// ---------------------------------------------------------------- place card
function bestEntrance(p) {
  const b = state.b;
  const bi = p.k === 'building' ? p : (p.par != null ? b.places[p.par] : p);
  const ents = (bi.ents || []).map((i) => b.entrances[i]);
  if (!ents.length) return null;
  if (state.me) return ents.reduce((a, e) => (distanceM(state.me.ll, e.ll) < distanceM(state.me.ll, a.ll) ? e : a));
  return ents.find((e) => e.main) || ents[0];
}

function walkUrl(p) {
  const e = p.k === 'poi' && p.par == null ? null : bestEntrance(p);
  const [lon, lat] = e ? e.ll : p.ll;
  return `https://www.google.com/maps/dir/?api=1&destination=${lat.toFixed(6)},${lon.toFixed(6)}&travelmode=walking`;
}

function shareUrl(p) { return `${location.origin}${location.pathname}#p=${p.id}`; }

function placeHtml(p) {
  const b = state.b;
  const c = b.cat[p.c];
  const alt = altName(p);
  const photo = p.ph?.length ? b.photos[p.ph[0]] : null;
  const parts = [];
  if (photo) {
    parts.push(`<div class="card-photo"><button type="button" data-photo="${p.ph[0]}" aria-label="${esc(t('photo'))}">
      <img src="data/${esc(photo.f)}" width="${photo.w}" height="${photo.h}" alt="${esc(photo.cap || pname(p))}" loading="lazy"></button></div>`);
  }
  parts.push(`<div class="card-sign">${p.code ? `<span class="code">${esc(p.code)}</span>` : bullet(p.c, { lg: true, draft: !p.ok })}
    <div class="grow"><h2 id="sheetTitle">${esc(pname(p))}</h2>${alt ? `<div class="alt">${esc(alt)}</div>` : ''}</div>
    <button class="iconbtn" type="button" data-act="close" aria-label="${esc(t('close'))}">${svg('close')}</button></div>`);
  parts.push(`<div class="card-meta">${p.code ? bullet(p.c, { draft: !p.ok }) : ''}<span class="tag">${esc(catName(c))}</span>
    ${p.ok ? `<span class="tag ok">${svg('check', { size: 16, stroke: 3 })}${esc(t('verified'))}</span>`
    : `<span class="tag draft">${svg('info', { size: 16 })}${esc(t('draft'))}</span>`}</div>`);
  if (!p.ok) parts.push(`<p class="note">${esc(t('draftNote'))}</p>`);
  if (p.note) parts.push(`<p class="note">${esc(getLang() === 'ms' && p.note_ms ? p.note_ms : p.note)}</p>`);
  parts.push(`<div class="actions">
    <a class="btn primary" href="${esc(walkUrl(p))}" target="_blank" rel="noopener">${svg('walk')}${esc(t('walkThere'))}</a>
    <button class="btn" type="button" data-act="share">${svg('share')}${esc(t('share'))}</button></div>`);

  const facts = [];
  if (p.k === 'building' && (p.nf || p.rooms.length)) {
    const n = p.nf || Math.max(1, ...p.rooms.map((r) => (r[2] ?? 0) + 1));
    const fl = Array.from({ length: n }, (_, k) => `<span class="floor">${esc(floorLabel(k))}</span>`).join('');
    facts.push(`<div class="fact">${svg('layers')}<div><b>${esc(t('floors'))} · ${esc(t('storeys', n))}</b><div class="floors">${fl}</div></div></div>`);
  }
  if (p.k === 'poi' && p.fl != null) facts.push(`<div class="fact">${svg('layers')}<div><b>${esc(t('floor'))}</b>${esc(floorLabel(p.fl))}</div></div>`);
  if (p.rooms.length) {
    const rooms = [...p.rooms].sort((a, b2) => (a[2] ?? 0) - (b2[2] ?? 0));
    facts.push(`<div class="fact">${svg('door')}<div><b>${esc(t('rooms'))}</b><ul class="rooms">${rooms.map((r) =>
      `<li><span class="fl">${esc(floorLabel(r[2]))}</span><span>${esc(r[0])}${r[1] ? ` <span class="row-code">${esc(r[1])}</span>` : ''}</span></li>`).join('')}</ul></div></div>`);
  }
  if (p.ents.length) {
    facts.push(`<div class="fact">${svg('door')}<div><b>${esc(t('entrances'))}</b>${p.ents.map((i) => {
      const e = b.entrances[i];
      const name = getLang() === 'ms' ? (e.ms || e.en) : e.en;
      return `<div class="ent"><span class="ent-arrow" style="transform:rotate(${e.b}deg)">${svg('arrowUp', { size: 18, stroke: 2.6 })}</span>${esc(name || (e.main ? t('mainEntrance') : ''))}${e.sf ? ` · ${esc(t('stepFree'))}` : ''}</div>`;
    }).join('')}</div></div>`);
  }
  if (p.par != null) {
    const par = b.places[p.par];
    facts.push(`<div class="fact">${svg('building')}<div><b>${esc(t('partOf'))}</b><ul class="list" style="margin-top:6px">${rowHtml(par)}</ul></div></div>`);
  }
  if (p.kids.length) {
    facts.push(`<div class="fact">${svg('building')}<div style="flex:1"><b>${esc(t('inside'))}</b><ul class="list" style="margin-top:6px">${p.kids.map((k) => rowHtml(b.places[k])).join('')}</ul></div></div>`);
  }
  if (p.lv) facts.push(`<div class="fact">${svg('clock')}<div><b>${esc(t('lastVerified'))}</b>${esc(fmtDate(p.lv))}${p.acc != null ? ` · ±${p.acc} m` : ''}</div></div>`);
  if (facts.length) parts.push(`<div class="facts">${facts.join('')}</div>`);
  return parts.join('');
}

function selectPlace(i, from = 'ui') {
  if (i == null) { closeSheet(); return; }
  const p = state.b.places[i];
  state.selected = i;
  closeResults();
  $('#search').blur();
  remember(p);
  state.map.highlight(i);
  openSheet(placeHtml(p));
  setHash(`p=${p.id}`);
  if (from !== 'tour') state.map.flyToPlace(i, { offsetY: sheetHeight() });
}

async function sharePlace(p) {
  const url = shareUrl(p);
  try {
    if (navigator.share) { await navigator.share({ title: pname(p), text: `${pname(p)} · UTHM Atlas`, url }); return; }
  } catch (e) { if (e.name === 'AbortError') return; }
  try { await navigator.clipboard.writeText(url); toast(t('copied')); } catch { prompt('Link', url); }
}

function openPhoto(k) {
  const ph = state.b.photos[k];
  const v = $('#viewer');
  v.innerHTML = `<img src="data/${esc(ph.f)}" alt="${esc(ph.cap || '')}"><button class="iconbtn" type="button" aria-label="${esc(t('close'))}">${svg('close', { size: 26 })}</button>`;
  v.hidden = false;
  v.onclick = () => { v.hidden = true; v.innerHTML = ''; };
}

// ---------------------------------------------------------------- First Week tour
function renderTour() {
  const steps = state.b.tour;
  const el = $('#tour');
  if (!steps.length) { toast(t('tourEmpty')); return; }
  const k = state.tourStep;
  const s = steps[k];
  const p = state.b.places[s.pl];
  const ms = getLang() === 'ms';
  el.innerHTML = `<div class="story">
    <div class="story-bars" aria-hidden="true">${steps.map((_, j) => `<i class="${j <= k ? 'on' : ''}"></i>`).join('')}</div>
    <div class="story-head"><span class="grow">${esc(t('step', k + 1, steps.length))}</span>
      <button class="iconbtn" type="button" data-tour="close" aria-label="${esc(t('close'))}">${svg('close')}</button></div>
    <div class="story-main"><h2 id="tourTitle">${esc(ms && s.ms ? s.ms : s.en)}</h2>
      <div class="place">${bullet(p.c, { draft: !p.ok })}<span>${p.code ? esc(p.code) + ' · ' : ''}${esc(pname(p))}</span></div>
      <p>${esc((ms && s.t_ms) ? s.t_ms : (s.t_en || ''))}</p></div>
    <div class="story-nav"><button class="btn" type="button" data-tour="prev" ${k === 0 ? 'disabled' : ''}>${esc(t('tourPrev'))}</button>
      <button class="btn primary" type="button" data-tour="next">${esc(k === steps.length - 1 ? t('tourDone') : t('tourNext'))}</button></div></div>`;
  el.hidden = false;
  state.map.highlight(s.pl);
  state.map.setRoute(steps.slice(0, k + 1).map((st) => state.b.places[st.pl].ll));
  state.map.orbitTo(s.pl, { offsetY: el.getBoundingClientRect().height });
  setHash(`tour=${k + 1}`);
  el.querySelector('[data-tour="next"]').focus({ preventScroll: true });
}

function startTour(step = 0) {
  closeSheet(); closeResults();
  if (!state.b.tour.length) { toast(t('tourEmpty')); return; }
  state.tourStep = Math.max(0, Math.min(step, state.b.tour.length - 1));
  renderTour();
}

function endTour() {
  state.tourStep = -1;
  $('#tour').hidden = true;
  state.map.setRoute(null);
  state.map.highlight(null);
  setHash('');
}

// ---------------------------------------------------------------- you are here (never stored, never sent)
function toggleLocate() {
  const btn = $('#locBtn');
  if (state.watch != null) {
    navigator.geolocation.clearWatch(state.watch);
    state.watch = null; state.me = null;
    state.map.setMe(null);
    btn.setAttribute('aria-pressed', 'false');
    return;
  }
  if (!('geolocation' in navigator)) { toast(t('locOff')); return; }
  toast(t('locating'));
  btn.setAttribute('aria-pressed', 'true');
  let first = true;
  state.watch = navigator.geolocation.watchPosition((pos) => {
    state.me = { ll: [pos.coords.longitude, pos.coords.latitude], acc: pos.coords.accuracy };
    state.map.setMe(state.me);
    if (first) {
      first = false;
      toast(t('locNote'));
      state.map.map.flyTo({ center: state.me.ll, zoom: Math.max(state.map.map.getZoom(), 17) });
    }
  }, (err) => {
    toast(err.code === 1 ? t('locDenied') : t('locOff'));
    btn.setAttribute('aria-pressed', 'false');
    if (state.watch != null) navigator.geolocation.clearWatch(state.watch);
    state.watch = null;
  }, { enableHighAccuracy: true, maximumAge: 5000, timeout: 20000 });
}

// ---------------------------------------------------------------- menu (theme, language, about, install)
let installEvt = null;
addEventListener('beforeinstallprompt', (e) => { e.preventDefault(); installEvt = e; });

function openMenu() {
  closeResults();
  const lang = getLang();
  const c = state.b.build.counts;
  const isIos = /iPad|iPhone|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
  const standalone = matchMedia('(display-mode: standalone)').matches || navigator.standalone;
  openSheet(`
    <div class="card-sign"><span class="code">UA</span><div class="grow"><h2 id="sheetTitle">UTHM Atlas</h2>
      <div class="alt">${esc(t('unofficial'))}</div></div>
      <button class="iconbtn" type="button" data-act="close" aria-label="${esc(t('close'))}">${svg('close')}</button></div>
    <div class="menu-grid">
      <h3>${esc(t('language'))}</h3>
      <div class="seg" role="group" aria-label="${esc(t('language'))}">
        <button type="button" data-lang="en" aria-pressed="${lang === 'en'}">English</button>
        <button type="button" data-lang="ms" aria-pressed="${lang === 'ms'}">Bahasa Melayu</button></div>
      <h3>${esc(t('theme'))}</h3>
      <div class="seg" role="group" aria-label="${esc(t('theme'))}">
        ${['auto', 'light', 'dark'].map((m) => `<button type="button" data-theme="${m}" aria-pressed="${state.themePref === m}">${esc(t(m))}</button>`).join('')}</div>
      ${!standalone && (installEvt || isIos) ? `<button class="btn wide" type="button" data-act="install">${svg('download')}${esc(t('install'))}</button>
        ${isIos && !installEvt ? `<p class="note">${esc(t('installIos'))}</p>` : ''}` : ''}
      <h3>${esc(t('about'))}</h3>
      <div class="about"><p>${esc(t('aboutText'))}</p>
        <p>${esc(t('places', c.places))} · ${esc(t('walked', c.verified))} · ${esc(fmtDate(state.b.build.date))}</p>
        <p>${svg('sun', { size: 16 })} ${esc(t('sun'))}</p>
        <p>${esc(t('attribution'))}</p>
        <p>${esc(t('footer', fmtDate(state.b.build.date)))}</p></div>
    </div>`);
}

function applyTheme() {
  const dark = state.themePref === 'dark' || (state.themePref === 'auto' && matchMedia('(prefers-color-scheme: dark)').matches);
  const theme = dark ? 'dark' : 'light';
  document.documentElement.dataset.theme = theme;
  document.querySelector('meta[name="theme-color"]').content = dark ? '#000000' : '#121417';
  state.map?.setTheme(theme);
}

// ---------------------------------------------------------------- service worker: offline status + updates
function setPill(kind, text) {
  const p = $('#offlinePill');
  p.className = `pill${kind === 'ok' ? ' ok' : ''}`;
  p.innerHTML = `${svg(kind === 'ok' ? 'offline' : kind === 'off' ? 'cloudOff' : 'download', { size: 16 })}<span>${esc(text)}</span>`;
}

async function initSW() {
  if (!('serviceWorker' in navigator)) { setPill('no', t('offlineNo')); return; }
  navigator.serviceWorker.addEventListener('message', (e) => {
    const m = e.data || {};
    if (m.type === 'progress') setPill(m.done >= m.total ? 'ok' : 'saving', m.done >= m.total ? t('offlineReady') : t('offlineSaving', Math.round((100 * m.done) / m.total)));
  });
  let reloading = false;
  navigator.serviceWorker.addEventListener('controllerchange', () => { if (!reloading && state.updating) { reloading = true; location.reload(); } });
  try {
    const reg = await navigator.serviceWorker.register('sw.js');
    const offerUpdate = (w) => {
      toast(t('update'), { label: t('reload'), run: () => { state.updating = true; w.postMessage({ type: 'skip' }); } });
    };
    if (reg.waiting && navigator.serviceWorker.controller) offerUpdate(reg.waiting);
    reg.addEventListener('updatefound', () => {
      const w = reg.installing;
      w?.addEventListener('statechange', () => { if (w.state === 'installed' && navigator.serviceWorker.controller) offerUpdate(w); });
    });
    setInterval(() => reg.update().catch(() => {}), 30 * 60 * 1000);
    const ready = await navigator.serviceWorker.ready;
    ready.active?.postMessage({ type: 'status' });
  } catch {
    setPill('no', t('offlineNo'));
  }
  try { await navigator.storage?.persist?.(); } catch { /* informational */ }
}

addEventListener('offline', () => toast(t('offline')));

// ---------------------------------------------------------------- events
function wireUi() {
  const input = $('#search');
  input.addEventListener('focus', () => { endTourIfOpen(); closeSheet(); openResults(); });
  input.addEventListener('input', renderResults);
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') { input.value = ''; closeResults(); input.blur(); }
  });
  $('#searchForm').addEventListener('submit', (e) => {
    e.preventDefault();
    const first = $('#results [data-place]');
    if (first) selectPlace(+first.dataset.place);
  });
  $('#searchClear').addEventListener('click', () => { input.value = ''; renderResults(); input.focus(); });
  $('#menuBtn').addEventListener('click', openMenu);
  $('#locBtn').addEventListener('click', toggleLocate);
  $('#viewBtn').addEventListener('click', () => {
    const on = !state.map.is3d;
    state.map.set3d(on);
    $('#viewBtn').textContent = on ? '2D' : '3D';
    $('#viewBtn').setAttribute('aria-label', on ? t('view2d') : t('view3d'));
  });
  $('#northBtn').addEventListener('click', () => state.map.resetNorth());
  $('#tourBtn').addEventListener('click', () => startTour(0));
  $('#offlinePill').addEventListener('click', () => navigator.serviceWorker?.controller?.postMessage({ type: 'status' }));

  document.addEventListener('click', (e) => {
    const el = e.target.closest('[data-place],[data-act],[data-cat],[data-lang],[data-theme],[data-photo],[data-tour]');
    if (!el) return;
    if (el.dataset.place != null) { endTourIfOpen(); selectPlace(+el.dataset.place); }
    else if (el.dataset.cat != null) chooseChip(el.dataset.cat || null);
    else if (el.dataset.photo != null) openPhoto(+el.dataset.photo);
    else if (el.dataset.lang) {
      setLang(el.dataset.lang); store.set('lang', el.dataset.lang); state.map.setLang(el.dataset.lang); applyTexts(); openMenu();
    } else if (el.dataset.theme && el.closest('.seg')) {
      state.themePref = el.dataset.theme; store.set('theme', state.themePref); applyTheme(); openMenu();
    } else if (el.dataset.tour === 'next') {
      if (state.tourStep >= state.b.tour.length - 1) endTour(); else { state.tourStep++; renderTour(); }
    } else if (el.dataset.tour === 'prev') { state.tourStep = Math.max(0, state.tourStep - 1); renderTour(); }
    else if (el.dataset.tour === 'close') endTour();
    else if (el.dataset.act === 'close') closeSheet();
    else if (el.dataset.act === 'share') sharePlace(state.b.places[state.selected]);
    else if (el.dataset.act === 'install' && installEvt) { installEvt.prompt(); installEvt = null; closeSheet(); }
  });
  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape') return;
    if (!$('#viewer').hidden) $('#viewer').click();
    else if (!$('#tour').hidden) endTour();
    else closeSheet();
  });
  matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => state.themePref === 'auto' && applyTheme());
  addEventListener('hashchange', routeHash);

  // swipe the sheet down to close (phones)
  let y0 = null;
  const sheet = $('#sheet');
  sheet.addEventListener('touchstart', (e) => { y0 = $('#sheetBody').scrollTop <= 0 ? e.touches[0].clientY : null; }, { passive: true });
  sheet.addEventListener('touchmove', (e) => {
    if (y0 == null) return;
    const dy = e.touches[0].clientY - y0;
    if (dy > 0) sheet.style.transform = `translateY(${dy}px)`;
  }, { passive: true });
  sheet.addEventListener('touchend', (e) => {
    if (y0 == null) return;
    const dy = e.changedTouches[0].clientY - y0;
    sheet.style.transform = '';
    if (dy > 90) closeSheet();
    y0 = null;
  });
}

function endTourIfOpen() { if (state.tourStep >= 0) endTour(); }

function routeHash() {
  const h = new URLSearchParams(location.hash.slice(1));
  if (h.get('p')) {
    const p = state.b.byId.get(h.get('p'));
    if (p && state.selected !== p.i) selectPlace(p.i);
  } else if (h.has('tour')) {
    startTour((+h.get('tour') || 1) - 1);
  }
}

// ---------------------------------------------------------------- boot
async function boot() {
  setLang(store.get('lang', 'en'));
  applyTexts();
  initSW();
  try {
    const res = await fetch('data/campus.bundle');
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    state.b = await parseBundle(await res.arrayBuffer());
    state.search = makeSearch(state.b);
    const tiles = await registerTiles();
    state.b.tilesMode = tiles.mode;
    const theme = document.documentElement.dataset.theme;
    state.map = new CampusMap($('#map'), state.b, {
      theme, lang: getLang(), tilesUrl: tiles.url,
      onSelect: (i) => { if (state.tourStep >= 0) return; i == null ? (closeSheet(), closeResults()) : selectPlace(i, 'map'); },
    });
    const needle = $('#northBtn .needle');
    state.map.map.on('rotate', () => { needle.style.transform = `rotate(${-state.map.map.getBearing()}deg)`; });
    state.map.map.once('load', () => {
      document.querySelector('.maplibregl-ctrl-attrib')?.classList.remove('maplibregl-compact-show');
      $('#boot').classList.add('gone');
      setTimeout(() => $('#boot').remove(), 400);
      routeHash();
    });
    applyTexts();
    wireUi();
    window.atlas = state; // handy for debugging in DevTools
  } catch (err) {
    console.error(err);
    $('#bootMsg').innerHTML = `${esc(t('loadError'))}<br><button class="btn primary" type="button" onclick="location.reload()">${esc(t('retry'))}</button>`;
  }
}

boot();
