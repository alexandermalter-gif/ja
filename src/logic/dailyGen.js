// Erzeugung der Tagesrätsel (Version 3, ab 6. November 2026).
// Normalerweise kommen die Rätsel aus der vorberechneten Tabelle (dailies.js); dieser
// Generator dient zum Erzeugen der Tabelle und als Rückfall für Tage außerhalb davon.
import { mulberry32, generateOne, assignColors } from './generator.js';

/**
 * Ziel je Wochentag (Index = Date.getDay(), 0 = Sonntag): Brettgröße, Zielwert des Lösers,
 * Mindesttechnik. Montags leicht, zum Wochenende hin schwerer, sonntags am schwersten.
 */
export const DAILY_TARGETS = [
  { n: 9, target: 190, minTech: 5 }, // So
  { n: 6, target: 11, minTech: 0 }, // Mo
  { n: 6, target: 30, minTech: 0 }, // Di
  { n: 7, target: 55, minTech: 0 }, // Mi
  { n: 7, target: 78, minTech: 0 }, // Do
  { n: 8, target: 100, minTech: 0 }, // Fr
  { n: 8, target: 130, minTech: 4 }, // Sa
];

/** Größte Farbfläche höchstens 45 % des Bretts (ab 7 × 7). */
export const MAX_REGION_SHARE = 0.45;

export function hashString(s) {
  let h = 0x811c9dc5;
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return h >>> 0;
}

const weekday = (key) => {
  const [y, m, d] = key.split('-').map(Number);
  return new Date(y, m - 1, d, 12).getDay();
};

/**
 * Erzeugt das Rätsel eines Tages: mehrere Kandidaten, der dem Zielwert nächste gewinnt.
 * pause() wird zwischen den Versuchen aufgerufen (Browser bleibt bedienbar).
 */
export async function generateDailyV3(key, pause = null) {
  const plan = DAILY_TARGETS[weekday(key)];
  const rng = mulberry32(hashString(`katzen-sudoku-v3-${key}`));
  const want = plan.n >= 9 ? 6 : 10; // Kandidaten
  const opts = plan.n >= 7 ? { maxRegionShare: MAX_REGION_SHARE } : {};
  const cands = [];
  for (let tries = 0; cands.length < want && tries < 600; tries++) {
    const c = generateOne(plan.n, rng, plan.n >= 7 ? 2 : 1, opts);
    if (c && c.grade.maxTech >= plan.minTech) cands.push(c);
    if (pause) await pause();
  }
  // Notfalls ohne Mindesttechnik
  for (let tries = 0; !cands.length && tries < 600; tries++) {
    const c = generateOne(plan.n, rng, 1, opts);
    if (c) cands.push(c);
  }
  let best = cands[0];
  for (const c of cands) if (Math.abs(c.grade.score - plan.target) < Math.abs(best.grade.score - plan.target)) best = c;
  return {
    id: key,
    n: best.n,
    regions: best.regions.map((k) => k.toString(36)).join(''),
    colors: assignColors(best.n, best.regions, rng),
    solution: best.solution,
    score: best.grade.score,
    maxTech: best.grade.maxTech,
  };
}

// --- kompakte Speicherung: „regions|colors|solution|score|maxTech“ --------------------

export function encodeDaily(l) {
  return `${l.regions}|${l.colors.join('')}|${l.solution.map((c) => c.toString(36)).join('')}|${l.score}|${l.maxTech}`;
}

export function decodeDaily(key, s) {
  const [regions, colors, solution, score, maxTech] = s.split('|');
  const n = Math.round(Math.sqrt(regions.length));
  return {
    id: key,
    n,
    regions,
    colors: [...colors].map(Number),
    solution: [...solution].map((c) => parseInt(c, 36)),
    score: Number(score),
    maxTech: Number(maxTech),
  };
}
