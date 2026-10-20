// Instant search over the bundle's term index: EN/BM names, codes ("G3", "Blok G"), aliases ("FKEE"), rooms.
// normalize() mirrors engine/atlas/search.py so terms built there match queries typed here.

export function normalize(s) {
  return (s || '').normalize('NFKD').replace(/[̀-ͯ]/g, '').toLowerCase()
    .replace(/[^a-z0-9]+/g, ' ').trim();
}

// Optimal string alignment distance (Levenshtein + adjacent swaps), early exit above max.
export function editDistance(a, b, max = 2) {
  if (Math.abs(a.length - b.length) > max) return max + 1;
  const prev2 = new Array(b.length + 1).fill(0);
  let prev = Array.from({ length: b.length + 1 }, (_, j) => j);
  for (let i = 1; i <= a.length; i++) {
    const cur = [i];
    let rowMin = i;
    for (let j = 1; j <= b.length; j++) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1;
      let v = Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost);
      if (i > 1 && j > 1 && a[i - 1] === b[j - 2] && a[i - 2] === b[j - 1]) v = Math.min(v, prev2[j - 2] + 1);
      cur[j] = v;
      rowMin = Math.min(rowMin, v);
    }
    prev2.splice(0, prev2.length, ...prev);
    prev = cur;
    if (rowMin > max) return max + 1;
  }
  return prev[b.length];
}

// Score one index term against the query (both normalized). 0 = no match.
function termScore(term, q, qTokens) {
  if (term === q) return 100;
  if (term.startsWith(q)) return 85 - Math.min(15, term.length - q.length);
  const words = term.split(' ');
  // every query token must start some word of the term ("dew kul" -> "dewan kuliah")
  if (qTokens.length > 1 && qTokens.every((t) => words.some((w) => w.startsWith(t)))) return 70;
  if (qTokens.length === 1 && words.some((w) => w.startsWith(q))) return 65;
  if (q.length >= 3 && term.includes(q)) return 45;
  // typos: compare against each word and against the whole term
  const max = q.length >= 8 ? 2 : q.length >= 4 ? 1 : 0;
  if (max) {
    if (editDistance(q, term.slice(0, q.length + 1), max) <= max) return 35;
    if (qTokens.every((t) => t.length < 4 || words.some((w) => editDistance(t, w.slice(0, t.length + 1), t.length >= 8 ? 2 : 1) <= (t.length >= 8 ? 2 : 1)))
      && qTokens.some((t) => t.length >= 4)) return 30;
  }
  return 0;
}

export function makeSearch(bundle) {
  const terms = bundle.search; // [term, place index, weight]
  return function search(query, { limit = 12, near = null } = {}) {
    const q = normalize(query);
    if (!q) return [];
    const qTokens = q.split(' ');
    const best = new Map();
    for (const [term, i, w] of terms) {
      const s = termScore(term, q, qTokens);
      if (!s) continue;
      const score = s * (0.7 + 0.15 * w);
      const prev = best.get(i);
      if (!prev || score > prev.score) best.set(i, { i, score, term });
    }
    const out = [...best.values()].map((r) => {
      const p = bundle.places[r.i];
      let s = r.score + (p.ok ? 4 : 0) + (p.k === 'building' ? 3 : 0);
      if (near) s -= Math.min(10, distanceM(near, p.ll) / 150);
      return { ...r, score: s, place: p };
    });
    out.sort((a, b) => b.score - a.score || a.place.en.localeCompare(b.place.en));
    return out.slice(0, limit);
  };
}

export function distanceM(a, b) {
  const R = 6371008.8, rad = Math.PI / 180;
  const dLat = (b[1] - a[1]) * rad, dLon = (b[0] - a[0]) * rad;
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(a[1] * rad) * Math.cos(b[1] * rad) * Math.sin(dLon / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h));
}
