// Erzeugt 100 Level mit eindeutiger Lösung und aufsteigender Schwierigkeit.
// Aufruf: npm run generate   (schreibt src/logic/levels.js)
import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { mulberry32, shuffle, generateOne, assignColors } from '../src/logic/generator.js';

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

/**
 * Bis zur Farbumstellung wurden die Farben direkt beim Erzeugen vergeben. Damit die 100 Rätsel
 * (Regionen und Lösungen) identisch bleiben, verbraucht das Skript an dieser Stelle die
 * Zufallszahlen genau wie damals. Die endgültigen Farben vergibt danach assignColors()
 * mit eigenem Startwert.
 */
const LEGACY_PAIRS = new Set([[2, 9], [0, 5], [3, 4], [6, 7], [1, 8], [8, 9], [2, 5], [5, 9]].flatMap(([a, b]) => [`${a}-${b}`, `${b}-${a}`]));
function legacyColorRng(n, regions, rng) {
  const orth = (i) => {
    const r = Math.floor(i / n);
    const c = i % n;
    return [r > 0 && i - n, r < n - 1 && i + n, c > 0 && i - 1, c < n - 1 && i + 1].filter((x) => x !== false);
  };
  const adj = Array.from({ length: n }, () => new Set());
  for (let i = 0; i < regions.length; i++) for (const j of orth(i)) if (regions[i] !== regions[j]) adj[regions[i]].add(regions[j]);
  const order = [...Array(n).keys()].sort((a, b) => adj[b].size - adj[a].size);
  for (let attempt = 0; attempt < 200; attempt++) {
    const pal = shuffle([...Array(10).keys()], rng);
    const colors = new Array(n).fill(-1);
    const used = new Set();
    let ok = true;
    for (const k of order) {
      const choice = pal.find((p) => !used.has(p) && [...adj[k]].every((o) => colors[o] < 0 || !LEGACY_PAIRS.has(`${p}-${colors[o]}`)));
      if (choice === undefined) { ok = false; break; }
      colors[k] = choice;
      used.add(choice);
    }
    if (ok) return;
  }
  shuffle([...Array(10).keys()], rng);
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
      colors: (legacyColorRng(p.n, p.regions, rng), null),
      solution: p.solution,
      score: p.grade.score,
      maxTech: p.grade.maxTech,
      tier: tierFor(id),
    });
  }
}

// Farben: eigener Zufallsstrom, damit sie sich ändern lassen, ohne die Rätsel zu verändern
const colorRng = mulberry32(20261010);
for (const l of levels) l.colors = assignColors(l.n, [...l.regions].map((ch) => parseInt(ch, 36)), colorRng);

const header = '// Automatisch erzeugt von tools/generate-levels.mjs – nicht von Hand bearbeiten.\n';
const body = `export const LEVELS = [\n${levels.map((l) => `  ${JSON.stringify(l)},`).join('\n')}\n];\n`;
writeFileSync(OUT, header + body);
console.log(`\n${levels.length} Level geschrieben nach ${OUT}`);
