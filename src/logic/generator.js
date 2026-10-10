// Rätsel-Generator: wird vom Level-Skript (Node) und im Browser (Tägliches Rätsel) genutzt.
import { makeGeometry, countSolutions, gradePuzzle } from './solver.js';
import { PALETTE, PAIR_DIST, DARKNESS } from './palette.js';

export function mulberry32(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export const shuffle = (arr, rng) => {
  for (let i = arr.length - 1; i > 0; i--) {
    const j = Math.floor(rng() * (i + 1));
    [arr[i], arr[j]] = [arr[j], arr[i]];
  }
  return arr;
};

/** Zufällige Katzenpositionen: Permutation ohne benachbarte Spalten in aufeinanderfolgenden Zeilen. */
export function randomCats(n, rng) {
  const perm = [];
  const used = new Array(n).fill(false);
  const rec = (r) => {
    if (r === n) return true;
    const cols = shuffle([...Array(n).keys()], rng);
    for (const c of cols) {
      if (used[c]) continue;
      if (r > 0 && Math.abs(perm[r - 1] - c) <= 1) continue;
      used[c] = true;
      perm.push(c);
      if (rec(r + 1)) return true;
      perm.pop();
      used[c] = false;
    }
    return false;
  };
  rec(0);
  return perm;
}

const orth = (n, i) => {
  const r = Math.floor(i / n);
  const c = i % n;
  const out = [];
  if (r > 0) out.push(i - n);
  if (r < n - 1) out.push(i + n);
  if (c > 0) out.push(i - 1);
  if (c < n - 1) out.push(i + 1);
  return out;
};

/**
 * Lässt die Farbregionen von den Katzenfeldern aus wachsen – Feld für Feld und nur so,
 * dass das Rätsel eindeutig lösbar bleibt (ungefärbte Felder gelten dabei als gesperrt).
 * Geht das irgendwann nicht mehr, wird der Rest zufällig verteilt und anschließend repariert.
 */
export function growRegions(n, catCells, rng, minSize) {
  const size = n * n;
  const regions = new Int8Array(size).fill(-1);
  const style = rng();
  const weights = catCells.map(() => (style < 0.45 ? 0.6 + rng() * 0.8 : 0.15 + rng() * rng() * 4));
  const members = catCells.map((c, k) => { regions[c] = k; return [c]; });
  let left = size - n;
  while (left > 0) {
    const pairs = [];
    for (let k = 0; k < n; k++) {
      const boost = members[k].length < minSize ? 50 : 1;
      const seen = new Set();
      for (const m of members[k]) {
        for (const x of orth(n, m)) {
          if (regions[x] !== -1 || seen.has(x)) continue;
          seen.add(x);
          // Gewichtete Zufallsreihenfolge (Efraimidis–Spirakis).
          pairs.push({ x, k, key: Math.pow(rng(), 1 / (weights[k] * boost)) });
        }
      }
    }
    pairs.sort((a, b) => b.key - a.key);
    let ok = false;
    for (const p of pairs) {
      regions[p.x] = p.k;
      if (countSolutions(makeGeometry(n, regions), 2).count === 1) { members[p.k].push(p.x); ok = true; break; }
      regions[p.x] = -1;
    }
    if (!ok) {
      while (left > 0) {
        const opts = [];
        for (let k = 0; k < n; k++) for (const m of members[k]) for (const x of orth(n, m)) if (regions[x] === -1) opts.push({ x, k });
        const o = opts[Math.floor(rng() * opts.length)];
        regions[o.x] = o.k;
        members[o.k].push(o.x);
        left--;
      }
      if (!makeUnique(n, regions, catCells, rng, minSize)) return null;
      break;
    }
    left--;
  }
  const sizes = new Array(n).fill(0);
  for (const k of regions) sizes[k]++;
  if (Math.min(...sizes) < minSize) return null;
  return regions;
}

function regionConnectedWithout(n, regions, k, removed) {
  const cells = [];
  for (let i = 0; i < regions.length; i++) if (regions[i] === k && i !== removed) cells.push(i);
  if (!cells.length) return false;
  const seen = new Set([cells[0]]);
  const stack = [cells[0]];
  while (stack.length) {
    const x = stack.pop();
    for (const y of orth(n, x)) {
      if (y !== removed && regions[y] === k && !seen.has(y)) { seen.add(y); stack.push(y); }
    }
  }
  return seen.size === cells.length;
}

/**
 * Verändert Regionen so lange, bis nur noch die gewünschte Lösung übrig ist:
 * Ein Feld einer Alternativlösung wechselt in eine Nachbarregion (gierig: der Zug,
 * der die meisten Lösungen eliminiert, gewinnt).
 */
export function makeUnique(n, regions, catCells, rng, minSize) {
  const isCat = new Set(catCells);
  const LIMIT = 60;
  let current = countSolutions(makeGeometry(n, regions), LIMIT);
  for (let iter = 0; iter < 120; iter++) {
    if (current.count === 1) return true;
    const sizes = new Array(n).fill(0);
    for (const k of regions) sizes[k]++;
    const freq = new Map();
    for (const sol of current.solutions) for (const c of sol) if (!isCat.has(c)) freq.set(c, (freq.get(c) || 0) + 1);
    const moves = [];
    for (const [c] of freq) {
      const from = regions[c];
      if (sizes[from] - 1 < minSize) continue;
      if (!regionConnectedWithout(n, regions, from, c)) continue;
      for (const to of new Set(orth(n, c).map((y) => regions[y]))) if (to !== from) moves.push({ c, from, to });
    }
    if (!moves.length) return false;
    shuffle(moves, rng);
    let best = null;
    for (const mv of moves.slice(0, 10)) {
      regions[mv.c] = mv.to;
      const res = countSolutions(makeGeometry(n, regions), LIMIT);
      regions[mv.c] = mv.from;
      if (res.count >= 1 && (!best || res.count < best.res.count)) best = { mv, res };
      if (best && best.res.count === 1) break;
    }
    if (!best) return false;
    regions[best.mv.c] = best.mv.to;
    current = best.res;
  }
  return false;
}

/**
 * Wählt Palettenfarben so, dass benachbarte Farbflächen sich möglichst deutlich unterscheiden.
 * Bewertung (in dieser Reihenfolge):
 *   1. kleinster Farbabstand (PAIR_DIST) zwischen angrenzenden Regionen, in 2er-Stufen (15–16, 17–18 …)
 *   2. dunkle Farben eher auf kleinen Flächen (große, dunkle Flächen wirken schwer)
 *   3. Summe der Abstände aller angrenzenden Paare
 * Zufallsstart + lokale Suche (Tauschen/Ersetzen), mehrere Durchläufe.
 */
export function assignColors(n, regions, rng) {
  const P = PALETTE.length;
  const adj = Array.from({ length: n }, () => new Set());
  const size = new Array(n).fill(0);
  for (let i = 0; i < regions.length; i++) {
    size[regions[i]]++;
    for (const j of orth(n, i)) if (regions[i] !== regions[j]) adj[regions[i]].add(regions[j]);
  }
  const edges = [];
  for (let a = 0; a < n; a++) for (const b of adj[a]) if (a < b) edges.push([a, b]);
  const evaluate = (cols) => {
    let min = Infinity;
    let sum = 0;
    for (const [a, b] of edges) {
      const d = PAIR_DIST[cols[a]][cols[b]];
      if (d < min) min = d;
      sum += Math.min(d, 40);
    }
    let dark = 0;
    for (let k = 0; k < n; k++) dark += size[k] * DARKNESS[cols[k]];
    return { min: Math.floor(min / 2), dark, sum };
  };
  const better = (x, y) => {
    if (x.min !== y.min) return x.min > y.min;
    if (Math.abs(x.dark - y.dark) > 1e-9) return x.dark < y.dark;
    return x.sum > y.sum + 1e-9;
  };
  let best = null;
  for (let attempt = 0; attempt < 30; attempt++) {
    const order = shuffle([...Array(P).keys()], rng);
    const cols = order.slice(0, n);
    const pool = order.slice(n);
    let sc = evaluate(cols);
    for (let improved = true; improved;) {
      improved = false;
      for (let a = 0; a < n; a++) {
        for (let b = a + 1; b < n; b++) {
          [cols[a], cols[b]] = [cols[b], cols[a]];
          const s2 = evaluate(cols);
          if (better(s2, sc)) { sc = s2; improved = true; } else [cols[a], cols[b]] = [cols[b], cols[a]];
        }
        for (let u = 0; u < pool.length; u++) {
          [cols[a], pool[u]] = [pool[u], cols[a]];
          const s2 = evaluate(cols);
          if (better(s2, sc)) { sc = s2; improved = true; } else [cols[a], pool[u]] = [pool[u], cols[a]];
        }
      }
    }
    if (!best || better(sc, best.sc)) best = { cols: [...cols], sc };
  }
  return best.cols;
}

/** Kleinster Farbabstand zwischen angrenzenden Regionen (für Tests). */
export function minAdjacentDistance(n, regions, colors) {
  let min = Infinity;
  for (let i = 0; i < regions.length; i++) {
    for (const j of orth(n, i)) {
      if (regions[i] !== regions[j]) min = Math.min(min, PAIR_DIST[colors[regions[i]]][colors[regions[j]]]);
    }
  }
  return min;
}

export function generateOne(n, rng, minSize) {
  const perm = randomCats(n, rng);
  const catCells = perm.map((c, r) => r * n + c);
  const regions = growRegions(n, catCells, rng, minSize);
  if (!regions) return null;
  const geo = makeGeometry(n, regions);
  const grade = gradePuzzle(geo);
  if (!grade.solved) return null;
  const sizes = new Array(n).fill(0);
  for (const k of regions) sizes[k]++;
  return { n, regions: Array.from(regions), solution: perm, grade, minRegion: Math.min(...sizes) };
}

