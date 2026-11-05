// Freies Spiel: Zufallsrätsel mit wählbarer Größe und Stufe.
import { mulberry32, generateOne, assignColors } from '../logic/generator.js';
import { BOUNDS, tierFor } from './scoring.js';

export const FREE_SIZES = [6, 7, 8, 9, 10];

/** Score-Bereich einer Stufe für eine Brettgröße. */
export function tierRange(n, tier) {
  const b = BOUNDS[n] || BOUNDS[10];
  return [tier === 0 ? 0 : b[tier - 1], tier >= 4 ? Infinity : b[tier]];
}

/**
 * Erzeugt ein Rätsel; nimmt den ersten Kandidaten im Zielbereich, sonst den nächstgelegenen.
 * pause() wird zwischen den Versuchen aufgerufen, damit die Seite bedienbar bleibt.
 */
export async function generateFree(n, tier, seed, pause = null) {
  const rng = mulberry32(seed >>> 0);
  const [lo, hi] = tierRange(n, tier);
  const maxCands = n >= 9 ? 5 : 10;
  const cands = [];
  let p = null;
  for (let tries = 0; !p && cands.length < maxCands && tries < 200; tries++) {
    const c = generateOne(n, rng, n >= 7 ? 2 : 1);
    if (c) {
      cands.push(c);
      if (c.grade.score >= lo && c.grade.score < hi) p = c;
    }
    if (pause) await pause();
  }
  if (!p) {
    const dist = (c) => (c.grade.score < lo ? lo - c.grade.score : Math.max(0, c.grade.score - hi));
    p = cands.sort((a, b) => dist(a) - dist(b))[0];
  }
  if (!p) throw new Error('Kein Rätsel gefunden');
  const level = {
    id: `F${seed}`,
    n: p.n,
    regions: p.regions.map((k) => k.toString(36)).join(''),
    colors: assignColors(p.n, p.regions, rng),
    solution: p.solution,
    score: p.grade.score,
    maxTech: p.grade.maxTech,
  };
  level.tier = tierFor(level);
  return level;
}
