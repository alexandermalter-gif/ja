// Erzeugt 100 Level mit eindeutiger Lösung und aufsteigender Schwierigkeit.
// Aufruf: npm run generate   (schreibt src/logic/levels.js)
import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { makeGeometry, countSolutions, gradePuzzle } from '../src/logic/solver.js';
import { PALETTE, SIMILAR_PAIRS } from '../src/logic/palette.js';

const here = dirname(fileURLToPath(import.meta.url));
const OUT = join(here, '..', 'src', 'logic', 'levels.js');

// Größe → Anzahl Level, Pool-Größe und Ausschnitt der Schwierigkeitsverteilung.
const BANDS = [
  { n: 5, count: 8, pool: 400, minSize: 1, from: 0.0, to: 0.85 },
  { n: 6, count: 12, pool: 500, minSize: 1, from: 0.2, to: 0.92 },
  { n: 7, count: 15, pool: 500, minSize: 2, from: 0.3, to: 0.94 },
  { n: 8, count: 20, pool: 500, minSize: 2, from: 0.4, to: 0.96 },
  { n: 9, count: 22, pool: 320, minSize: 3, from: 0.5, to: 0.98 },
  { n: 10, count: 23, pool: 260, minSize: 3, from: 0.6, to: 0.99 },
];

function mulberry32(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const shuffle = (arr, rng) => {
  for (let i = arr.length - 1; i > 0; i--) {
    const j = Math.floor(rng() * (i + 1));
    [arr[i], arr[j]] = [arr[j], arr[i]];
  }
  return arr;
};

/** Zufällige Katzenpositionen: Permutation ohne benachbarte Spalten in aufeinanderfolgenden Zeilen. */
function randomCats(n, rng) {
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
function growRegions(n, catCells, rng, minSize) {
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
function makeUnique(n, regions, catCells, rng, minSize) {
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

/** Wählt Palettenfarben so, dass benachbarte Regionen sich deutlich unterscheiden. */
function assignColors(n, regions, rng) {
  const adj = Array.from({ length: n }, () => new Set());
  for (let i = 0; i < regions.length; i++) {
    for (const j of orth(n, i)) if (regions[i] !== regions[j]) adj[regions[i]].add(regions[j]);
  }
  const similar = new Set(SIMILAR_PAIRS.flatMap(([a, b]) => [`${a}-${b}`, `${b}-${a}`]));
  const order = [...Array(n).keys()].sort((a, b) => adj[b].size - adj[a].size);
  for (let attempt = 0; attempt < 200; attempt++) {
    const pal = shuffle([...Array(PALETTE.length).keys()], rng);
    const colors = new Array(n).fill(-1);
    const used = new Set();
    let ok = true;
    for (const k of order) {
      const choice = pal.find((p) => !used.has(p) && [...adj[k]].every((o) => colors[o] < 0 || !similar.has(`${p}-${colors[o]}`)));
      if (choice === undefined) { ok = false; break; }
      colors[k] = choice;
      used.add(choice);
    }
    if (ok) return colors;
  }
  return shuffle([...Array(PALETTE.length).keys()], rng).slice(0, n);
}

function generateOne(n, rng, minSize) {
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

function pickFromPool(pool, band) {
  pool.sort((a, b) => a.grade.score - b.grade.score);
  const out = [];
  const usedIdx = new Set();
  for (let i = 0; i < band.count; i++) {
    const t = band.count === 1 ? band.to : band.from + (band.to - band.from) * (i / (band.count - 1));
    const want = Math.min(pool.length - 1, Math.round(t * (pool.length - 1)));
    let idx = -1;
    for (let d = 0; d < pool.length && idx < 0; d++) {
      if (want - d >= 0 && !usedIdx.has(want - d)) idx = want - d;
      else if (want + d < pool.length && !usedIdx.has(want + d)) idx = want + d;
    }
    if (idx < 0) throw new Error(`Pool zu klein für ${band.n}×${band.n}`);
    usedIdx.add(idx);
    out.push(pool[idx]);
  }
  return out.sort((a, b) => a.grade.score - b.grade.score);
}

function tierFor(levelNo) {
  if (levelNo <= 15) return 0;
  if (levelNo <= 40) return 1;
  if (levelNo <= 70) return 2;
  if (levelNo <= 90) return 3;
  return 4;
}

const rng = mulberry32(20261009);
const levels = [];
for (const band of BANDS) {
  const pool = [];
  const seen = new Set();
  let tries = 0;
  const t0 = Date.now();
  while (pool.length < band.pool && tries < band.pool * 40) {
    tries++;
    // Ab 7×7 keine Einzelfeld-Farben mehr (das wäre eine geschenkte Katze).
    const p = generateOne(band.n, rng, band.minSize);
    if (!p) continue;
    const key = p.regions.join('');
    if (seen.has(key)) continue;
    seen.add(key);
    pool.push(p);
  }
  const chosen = pickFromPool(pool, band);
  const scores = pool.map((p) => p.grade.score);
  console.log(
    `${band.n}×${band.n}: Pool ${pool.length} (${tries} Versuche, ${((Date.now() - t0) / 1000).toFixed(1)}s)`,
    `Score min ${Math.min(...scores)} max ${Math.max(...scores)}`,
    '→ gewählt', chosen.map((p) => `${p.grade.score}/T${p.grade.maxTech}`).join(' '),
  );
  for (const p of chosen) {
    const id = levels.length + 1;
    levels.push({
      id,
      n: p.n,
      regions: p.regions.map((k) => k.toString(36)).join(''),
      colors: assignColors(p.n, p.regions, rng),
      solution: p.solution,
      score: p.grade.score,
      maxTech: p.grade.maxTech,
      tier: tierFor(id),
    });
  }
}

const header = '// Automatisch erzeugt von tools/generate-levels.mjs – nicht von Hand bearbeiten.\n';
const body = `export const LEVELS = [\n${levels.map((l) => `  ${JSON.stringify(l)},`).join('\n')}\n];\n`;
writeFileSync(OUT, header + body);
console.log(`\n${levels.length} Level geschrieben nach ${OUT}`);
